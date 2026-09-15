package dev.nelsongx.map.fabric.http;

import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin server for the web map. Currently serves only {@code GET /api/health} returning
 * {@code {"ok":true}}; the API and static site are added by later PLAN tasks. Minecraft-free.
 *
 * <p>Everything else (unknown path, any method but GET, OPTIONS preflight not allowed by the CORS
 * config) is a 404 JSON {@code {"error": ...}}. CORS: {@code Access-Control-Allow-Origin} is set only for
 * configured origins ({@code *} = any); preflight allows GET only.
 */
// THREADING: start()/stop() bind or shut down Jetty and BLOCK — call them only on the dedicated
// lifecycle thread (HttpLifecycle) or a test thread, never on the Minecraft server thread.
// Request handlers run on Jetty's daemon pool and never touch ServerLevel/MinecraftServer/world state.
public final class MapHttpServer {

  /** Content-Type of every JSON response. */
  public static final String CONTENT_TYPE = "application/json; charset=utf-8";
  /** Health check path. */
  public static final String HEALTH_PATH = "/api/health";

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final HttpConfig config;
  private final boolean anyOrigin;
  private final List<String> origins;
  private Javalin app;
  private QueuedThreadPool pool;

  /**
   * Creates a stopped server.
   *
   * @param config settings (bind, port, CORS)
   */
  public MapHttpServer(HttpConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.origins = config.corsOrigins();
    this.anyOrigin = origins.contains("*");
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
      cfg.routes.before(this::filter);
      cfg.routes.get(HEALTH_PATH, ctx -> json(ctx, 200, "{\"ok\":true}"));
      cfg.routes.exception(HttpResponseException.class,
          (e, ctx) -> json(ctx, e.getStatus(), error(e.getMessage())));
      cfg.routes.exception(Exception.class, (e, ctx) -> {
        LOGGER.warn("HTTP request {} {} failed", ctx.method().name(), ctx.path(), e);
        json(ctx, 500, error("internal error"));
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

  /** Before-filter for every request: CORS headers, preflight, GET-only. Jetty request thread. */
  private void filter(Context ctx) {
    String origin = ctx.header("Origin");
    boolean allowedOrigin = origin != null && (anyOrigin || origins.contains(origin));
    if (allowedOrigin) {
      ctx.header("Access-Control-Allow-Origin", anyOrigin ? "*" : origin);
    }
    if (!anyOrigin && !origins.isEmpty()) {
      ctx.header("Vary", "Origin");
    }
    String method = ctx.method().name();
    if (HandlerType.OPTIONS.name().equals(method)) {
      String path = ctx.path();
      boolean knownPath = path.equals(HEALTH_PATH);
      if (allowedOrigin && knownPath && "GET".equals(ctx.header("Access-Control-Request-Method"))) {
        ctx.header("Access-Control-Allow-Methods", "GET");
        ctx.header("Access-Control-Max-Age", "600");
        ctx.status(204);
      } else {
        json(ctx, 404, error("not found: OPTIONS " + path));
      }
      ctx.skipRemainingHandlers();
      return;
    }
    if (!HandlerType.GET.name().equals(method)) {
      json(ctx, 404, error("not found: " + method + " " + ctx.path()));
      ctx.skipRemainingHandlers();
    }
  }

  /** @return {@code {"error": message}} JSON text. Any thread. */
  static String error(String message) {
    JsonObject o = new JsonObject();
    o.addProperty("error", message);
    return o.toString();
  }

  private static void json(Context ctx, int status, String body) {
    ctx.status(status);
    ctx.contentType(CONTENT_TYPE);
    ctx.header("Cache-Control", "no-store");
    ctx.result(body.getBytes(StandardCharsets.UTF_8));
  }
}
