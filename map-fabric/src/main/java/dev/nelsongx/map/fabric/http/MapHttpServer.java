package dev.nelsongx.map.fabric.http;

import com.google.gson.JsonObject;
import dev.nelsongx.map.fabric.http.api.ApiHandlers;
import dev.nelsongx.map.fabric.http.api.ApiServices;
import dev.nelsongx.map.fabric.http.api.Reply;
import dev.nelsongx.map.fabric.http.api.StaticFiles;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin server for the web map: JSON API under {@code /api}, squaremap tiles under {@code /tiles},
 * and the bundled static web export at {@code /}. Minecraft-free: every backend comes through
 * {@link ApiServices}.
 *
 * <ul>
 *   <li>API errors are JSON {@code {"error": code, ...}}; unknown {@code /api} paths and methods are 404.
 *   <li>Asynchronous handlers run with {@code ctx.future}, a per-request timeout ({@code 503
 *       {"error":"timeout"}}) and at most {@code http.maxConcurrent} in flight ({@code 503
 *       {"error":"busy"}}). Request bodies are limited to {@link #MAX_BODY_BYTES}.
 *   <li>CORS is for development only: origins listed in {@code http.cors.origins} get
 *       credentialed CORS; {@code *} allows any origin without credentials.
 * </ul>
 */
// THREADING: start()/stop() bind or shut down Jetty and BLOCK — call them only on the dedicated
// lifecycle thread (HttpLifecycle) or a test thread, never on the Minecraft server thread. Request
// handlers run on Jetty's daemon pool and never touch ServerLevel/MinecraftServer; long work is
// delegated to ApiServices.executor() by ApiHandlers, and responses are written by the thread that
// completes the request future (Javalin resumes the pipeline after completion).
public final class MapHttpServer {

  /** Content-Type of every JSON response. */
  public static final String CONTENT_TYPE = "application/json; charset=utf-8";
  /** Health check path. */
  public static final String HEALTH_PATH = "/api/health";
  /** Maximum request body size. */
  public static final long MAX_BODY_BYTES = 1L << 20;

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");
  private static final String REPLIED = "squaremap-pro.replied";
  private static final Set<String> CORS_METHODS = Set.of("GET", "POST", "PUT", "DELETE");

  private final HttpConfig config;
  private final ApiServices services;
  private final ApiHandlers api;
  private final boolean anyOrigin;
  private final List<String> origins;
  private final Semaphore inFlight;
  private Javalin app;
  private QueuedThreadPool pool;

  /**
   * Creates a stopped server with no backends (health check only).
   *
   * @param config settings
   */
  public MapHttpServer(HttpConfig config) {
    this(config, ApiServices.unavailable());
  }

  /**
   * Creates a stopped server.
   *
   * @param config settings (bind, port, CORS, limits, cookies, speeds)
   * @param services backends
   */
  public MapHttpServer(HttpConfig config, ApiServices services) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    this.api = new ApiHandlers(config, services);
    this.origins = config.corsOrigins().stream().filter(o -> !o.equals("*")).toList();
    this.anyOrigin = config.corsOrigins().contains("*");
    this.inFlight = new Semaphore(config.maxConcurrent());
  }

  /**
   * Binds and starts Jetty. BLOCKING.
   *
   * @throws RuntimeException (e.g. {@code io.javalin.util.JavalinBindException}) if the server cannot
   *     start; resources are released before throwing
   */
  public synchronized void start() {
    if (app != null) {
      throw new IllegalStateException("already started");
    }
    QueuedThreadPool threads = new QueuedThreadPool(32, 2, 60_000);
    threads.setName("squaremap-pro-http");
    threads.setDaemon(true);
    Javalin created = Javalin.create(cfg -> {
      cfg.startup.showJavalinBanner = false;
      cfg.startup.showOldJavalinVersionWarning = false;
      cfg.startup.startupWatcherEnabled = false;
      cfg.jetty.threadPool = threads;
      cfg.http.maxRequestSize = MAX_BODY_BYTES;
      // Backstop only: our own orTimeout fires first and writes a proper JSON 503.
      cfg.http.asyncTimeout = config.timeoutMs() + 5_000;
      var r = cfg.routes;
      r.before(this::filter);

      r.get(HEALTH_PATH, ctx -> reply(ctx, Reply.json(200, okJson())));
      r.get("/api/worlds", ctx -> reply(ctx, api.worlds()));
      r.get("/api/worlds/{world}/features", ctx ->
          async(ctx, () -> api.listFeatures(ctx.pathParam("world"))));
      r.post("/api/worlds/{world}/features", ctx -> {
        String body = body(ctx);
        async(ctx, () -> api.createFeature(ctx.pathParam("world"), csrf(ctx), cookie(ctx), body));
      });
      r.put("/api/worlds/{world}/features/{id}", ctx -> {
        String body = body(ctx);
        async(ctx, () -> api.updateFeature(ctx.pathParam("world"), ctx.pathParam("id"), csrf(ctx),
            cookie(ctx), body));
      });
      r.delete("/api/worlds/{world}/features/{id}", ctx -> async(ctx, () ->
          api.deleteFeature(ctx.pathParam("world"), ctx.pathParam("id"),
              ctx.queryParam("revision"), csrf(ctx), cookie(ctx))));

      r.get("/api/auth/redeem", ctx -> async(ctx, () -> api.redeem(ctx.queryParam("token"))));
      r.get("/api/auth/me", ctx -> async(ctx, () -> api.me(cookie(ctx))));
      r.post("/api/auth/logout", ctx -> async(ctx, () -> api.logout(csrf(ctx), cookie(ctx))));

      r.get("/api/route", ctx -> async(ctx, () -> api.route(ctx.queryParam("world"),
          ctx.queryParam("from"), ctx.queryParam("to"), ctx.queryParam("modes"))));

      r.get("/tiles/<path>", this::tiles);
      r.get("/", this::site);
      r.get("/<path>", this::site);
      for (HandlerType t : List.of(HandlerType.POST, HandlerType.PUT, HandlerType.DELETE,
          HandlerType.PATCH)) {
        r.addHttpHandler(t, "/<path>", ctx -> notFound(ctx));
      }

      r.error(404, ctx -> {
        if (ctx.attribute(REPLIED) == null) {
          notFound(ctx);
        }
      });
      r.exception(HttpResponseException.class,
          (e, ctx) -> reply(ctx, Reply.error(e.getStatus(), e.getMessage())));
      r.exception(Exception.class, (e, ctx) -> {
        LOGGER.warn("HTTP request {} {} failed", ctx.method().name(), ctx.path(), e);
        reply(ctx, Reply.error(500, "internal error"));
      });
    });
    try {
      created.start(config.bind(), config.port());
    } catch (RuntimeException e) {
      safeStop(created, threads);
      throw e;
    }
    app = created;
    pool = threads;
  }

  /** Stops Jetty if running. BLOCKING; idempotent. */
  public synchronized void stop() {
    Javalin a = app;
    QueuedThreadPool p = pool;
    app = null;
    pool = null;
    if (a != null) {
      safeStop(a, p);
    }
  }

  /** @return the bound local port (useful with port 0); requires a started server */
  public synchronized int port() {
    if (app == null) {
      throw new IllegalStateException("not started");
    }
    return app.port();
  }

  private static void safeStop(Javalin a, QueuedThreadPool p) {
    try {
      a.stop();
    } catch (RuntimeException e) {
      LOGGER.warn("HTTP server did not stop cleanly", e);
    }
    try {
      if (p != null) {
        p.stop();
      }
    } catch (Exception e) {
      LOGGER.debug("HTTP thread pool stop failed", e);
    }
  }

  // ---- request plumbing (Jetty request threads) --------------------------------------------------

  /** Before-filter: security headers, CORS, preflight. */
  private void filter(Context ctx) {
    ctx.header("X-Content-Type-Options", "nosniff");
    String origin = ctx.header("Origin");
    boolean listed = origin != null && origins.contains(origin);
    if (listed) {
      ctx.header("Access-Control-Allow-Origin", origin);
      ctx.header("Access-Control-Allow-Credentials", "true");
    } else if (origin != null && anyOrigin) {
      ctx.header("Access-Control-Allow-Origin", "*");
    }
    if (!origins.isEmpty()) {
      ctx.header("Vary", "Origin");
    }
    if (HandlerType.OPTIONS.name().equals(ctx.method().name())) {
      String requested = ctx.header("Access-Control-Request-Method");
      boolean api = ctx.path().startsWith("/api/");
      if ((listed || (origin != null && anyOrigin)) && api && requested != null
          && CORS_METHODS.contains(requested)) {
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, " + ApiHandlers.CSRF_HEADER);
        ctx.header("Access-Control-Max-Age", "600");
        ctx.status(204);
        ctx.attribute(REPLIED, true);
      } else {
        notFound(ctx);
      }
      ctx.skipRemainingHandlers();
    }
  }

  private void async(Context ctx, Supplier<CompletableFuture<Reply>> work) {
    if (!inFlight.tryAcquire()) {
      reply(ctx, Reply.error(503, "busy"));
      return;
    }
    CompletableFuture<Reply> started;
    try {
      started = work.get();
    } catch (RuntimeException e) {
      started = CompletableFuture.failedFuture(e);
    }
    CompletableFuture<Reply> timed = started.orTimeout(config.timeoutMs(), TimeUnit.MILLISECONDS);
    timed.whenComplete((r, e) -> inFlight.release());
    CompletableFuture<Void> written = timed.handle((r, e) -> {
      reply(ctx, e == null ? r : failure(ctx, e));
      return null;
    });
    ctx.future(() -> written);
  }

  private static Reply failure(Context ctx, Throwable e) {
    Throwable t = e;
    while ((t instanceof CompletionException || t instanceof ExecutionException)
        && t.getCause() != null) {
      t = t.getCause();
    }
    if (t instanceof Reply.Exit exit) {
      return exit.reply();
    }
    if (t instanceof TimeoutException) {
      return Reply.error(503, "timeout");
    }
    if (t instanceof RejectedExecutionException || t instanceof CancellationException) {
      return Reply.error(503, "busy");
    }
    LOGGER.warn("HTTP request {} {} failed", ctx.method().name(), ctx.path(), t);
    return Reply.error(500, "internal error");
  }

  private static void reply(Context ctx, Reply reply) {
    ctx.attribute(REPLIED, true);
    ctx.status(reply.status());
    for (String[] h : reply.headers()) {
      if (h[0].equalsIgnoreCase("Set-Cookie")) {
        ctx.res().addHeader(h[0], h[1]);
      } else {
        ctx.header(h[0], h[1]);
      }
    }
    if (ctx.path().startsWith("/api/")) {
      ctx.header("Cache-Control", "no-store");
    }
    if (reply.json() != null) {
      ctx.contentType(CONTENT_TYPE);
      ctx.result(reply.json().toString().getBytes(StandardCharsets.UTF_8));
    } else {
      ctx.result(new byte[0]);
    }
  }

  private static void notFound(Context ctx) {
    if (ctx.path().startsWith("/api/") || ctx.path().equals("/api")
        || !HandlerType.GET.name().equals(ctx.method().name())) {
      reply(ctx, Reply.error(404, "not_found"));
    } else {
      text(ctx, 404, "not found");
    }
  }

  private static String body(Context ctx) {
    return new String(ctx.bodyAsBytes(), StandardCharsets.UTF_8);
  }

  private static String csrf(Context ctx) {
    return ctx.header(ApiHandlers.CSRF_HEADER);
  }

  private static String cookie(Context ctx) {
    return ctx.cookie(ApiHandlers.SESSION_COOKIE);
  }

  private static JsonObject okJson() {
    JsonObject o = new JsonObject();
    o.addProperty("ok", true);
    return o;
  }

  /** {@code GET /tiles/*} from squaremap's {@code <webDir>/tiles}. */
  private void tiles(Context ctx) {
    Path root = services.tilesDir().get();
    String raw = ctx.path().substring("/tiles/".length());
    Optional<StaticFiles.Opened> file = root == null
        ? Optional.empty()
        : StaticFiles.cleanRelativePath(raw).flatMap(rel -> StaticFiles.openFile(root, rel));
    if (file.isEmpty()) {
      text(ctx, 404, "not found");
      return;
    }
    // squaremap rewrites tiles and settings continuously: short cache for images, none for JSON.
    boolean json = file.get().contentType().startsWith("application/json");
    stream(ctx, file.get(), json ? "no-cache" : "public, max-age=10");
  }

  /** {@code GET /*}: bundled static web export. */
  private void site(Context ctx) {
    String path = ctx.path();
    if (path.startsWith("/api/") || path.equals("/api") || path.startsWith("/tiles/")) {
      notFound(ctx);
      return;
    }
    ClassLoader loader = services.webLoader();
    String prefix = services.webPrefix();
    boolean bundled = loader.getResource(prefix + "index.html") != null;
    if (!bundled) {
      text(ctx, path.equals("/") ? 200 : 404,
          "squaremap-pro web bundle missing: build web/ and rebuild the mod jar.");
      return;
    }
    Optional<String> rel = StaticFiles.cleanRelativePath(path.substring(1));
    Optional<StaticFiles.Opened> found = Optional.empty();
    if (rel.isPresent()) {
      String r = rel.get();
      if (r.isEmpty() || r.endsWith("/")) {
        found = StaticFiles.openResource(loader, prefix, r + "index.html");
      } else {
        found = StaticFiles.openResource(loader, prefix, r);
        if (found.isEmpty() && r.lastIndexOf('.') <= r.lastIndexOf('/')) {
          found = StaticFiles.openResource(loader, prefix, r + ".html")
              .or(() -> StaticFiles.openResource(loader, prefix, r + "/index.html"));
        }
      }
    }
    if (found.isPresent()) {
      String cache = rel.get().startsWith("_next/static/")
          ? "public, max-age=31536000, immutable"
          : "no-cache";
      stream(ctx, found.get(), cache);
      return;
    }
    Optional<StaticFiles.Opened> notFound = StaticFiles.openResource(loader, prefix, "404.html");
    ctx.attribute(REPLIED, true);
    if (notFound.isPresent()) {
      ctx.status(404);
      stream(ctx, notFound.get(), "no-cache");
      ctx.status(404);
    } else {
      text(ctx, 404, "not found");
    }
  }

  private static void stream(Context ctx, StaticFiles.Opened opened, String cacheControl) {
    ctx.attribute(REPLIED, true);
    ctx.header("Cache-Control", cacheControl);
    ctx.contentType(opened.contentType());
    InputStream in = opened.stream();
    ctx.result(in);
  }

  private static void text(Context ctx, int status, String message) {
    ctx.attribute(REPLIED, true);
    ctx.status(status);
    ctx.contentType("text/plain; charset=utf-8");
    ctx.header("Cache-Control", "no-store");
    ctx.result(message.getBytes(StandardCharsets.UTF_8));
  }

  /** @return {@code {"error": message}} JSON text. Any thread. */
  static String error(String message) {
    JsonObject o = new JsonObject();
    o.addProperty("error", message);
    return o.toString();
  }
}
