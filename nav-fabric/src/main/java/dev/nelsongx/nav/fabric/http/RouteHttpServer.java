package dev.nelsongx.nav.fabric.http;

import dev.nelsongx.nav.fabric.route.RouteOutcome;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpResponseException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javalin server for {@code GET /route} and {@code GET /health}. Minecraft-free: the route computation
 * is injected as a {@link RouteFunction}.
 *
 * <p>Everything else (unknown path, any method but GET, OPTIONS preflight not allowed by the CORS
 * config) is a 404 JSON {@code {"error": ...}}. CORS: {@code Access-Control-Allow-Origin} is set only for
 * configured origins ({@code *} = any); preflight allows GET only.
 */
// THREADING: start()/stop() bind or shut down Jetty and BLOCK — call them only on the dedicated
// lifecycle thread (RouteHttpLifecycle) or a test thread, never on the Minecraft server thread.
// Request handlers run on Jetty's daemon pool; they parse, call RouteFunction (non-blocking), and
// finish asynchronously via Context.future. The response is written on a Jetty pool thread (hop via
// thenAcceptAsync), never on a nav worker. Handlers never touch ServerLevel/MinecraftServer/world state.
public final class RouteHttpServer {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final HttpConfig config;
  private final RouteFunction routes;
  private final Semaphore permits;
  private final boolean anyOrigin;
  private final List<String> origins;
  private Javalin app;
  private QueuedThreadPool pool;

  /**
   * Creates a stopped server.
   *
   * @param config settings (bind, port, CORS, timeout, concurrency)
   * @param routes route computation
   */
  public RouteHttpServer(HttpConfig config, RouteFunction routes) {
    this.config = Objects.requireNonNull(config, "config");
    this.routes = Objects.requireNonNull(routes, "routes");
    this.permits = new Semaphore(config.maxConcurrent());
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
      cfg.routes.get("/route", this::handleRoute);
      cfg.routes.get("/health", ctx -> json(ctx, 200, RouteJson.health()));
      cfg.routes.exception(HttpResponseException.class,
          (e, ctx) -> json(ctx, e.getStatus(), RouteJson.error(e.getMessage())));
      cfg.routes.exception(Exception.class, (e, ctx) -> {
        LOGGER.warn("HTTP request {} {} failed", ctx.method().name(), ctx.path(), e);
        json(ctx, 500, RouteJson.error("internal error"));
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
      boolean knownPath = path.equals("/route") || path.equals("/health");
      if (allowedOrigin && knownPath && "GET".equals(ctx.header("Access-Control-Request-Method"))) {
        ctx.header("Access-Control-Allow-Methods", "GET");
        ctx.header("Access-Control-Max-Age", "600");
        ctx.status(204);
      } else {
        json(ctx, 404, RouteJson.error("not found: OPTIONS " + path));
      }
      ctx.skipRemainingHandlers();
      return;
    }
    if (!HandlerType.GET.name().equals(method)) {
      json(ctx, 404, RouteJson.error("not found: " + method + " " + ctx.path()));
      ctx.skipRemainingHandlers();
    }
  }

  /** GET /route. Jetty request thread; never blocks. */
  private void handleRoute(Context ctx) {
    RouteRequestParser.Result parsed = RouteRequestParser.parse(ctx.queryParam("from"),
        ctx.queryParam("to"), ctx.queryParam("world"));
    if (parsed instanceof RouteRequestParser.Invalid invalid) {
      respond(ctx, RouteOutcome.invalidRequest(invalid.world(), invalid.error()));
      return;
    }
    RouteQuery query = ((RouteRequestParser.Ok) parsed).query();
    if (!permits.tryAcquire()) {
      respond(ctx, RouteOutcome.notReady(query.world(), "server busy"));
      return;
    }
    CompletableFuture<Void> response;
    try {
      CompletableFuture<RouteOutcome> routed = Objects.requireNonNull(routes.route(query),
          "route future");
      long timeoutMs = config.timeoutMs();
      QueuedThreadPool writer = pool;
      // copy(): orTimeout completes the future it is called on; don't fail the producer's future.
      response = routed.copy()
          .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
          .handle((outcome, err) -> toOutcome(query.world(), outcome, err, timeoutMs))
          .thenAcceptAsync(outcome -> respond(ctx, outcome), writer)
          .whenComplete((v, err) -> permits.release());
    } catch (RuntimeException e) {
      permits.release();
      LOGGER.error("route request {} failed to start", query, e);
      respond(ctx, RouteOutcome.notReady(query.world(), "internal error: " + e));
      return;
    }
    ctx.future(() -> response);
  }

  /** Maps the timed future's result to an outcome. Any thread. */
  static RouteOutcome toOutcome(String world, RouteOutcome outcome, Throwable err, long timeoutMs) {
    if (err == null) {
      return outcome != null ? outcome : RouteOutcome.notReady(world, "internal error: no outcome");
    }
    Throwable cause = err;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof TimeoutException) {
      return RouteOutcome.notReady(world, "timed out after " + timeoutMs + " ms");
    }
    LOGGER.error("route request in {} failed", world, cause);
    return RouteOutcome.notReady(world, "internal error: " + cause);
  }

  private static void respond(Context ctx, RouteOutcome outcome) {
    json(ctx, RouteJson.httpStatus(outcome.status()), RouteJson.write(outcome));
  }

  private static void json(Context ctx, int status, String body) {
    ctx.status(status);
    ctx.contentType(RouteJson.CONTENT_TYPE);
    ctx.header("Cache-Control", "no-store");
    ctx.result(body.getBytes(StandardCharsets.UTF_8));
  }
}
