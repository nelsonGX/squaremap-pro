package dev.nelsongx.nav.fabric.http;

import dev.nelsongx.nav.fabric.route.RouteOutcome;
import dev.nelsongx.nav.fabric.route.RouteService;
import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts/stops the {@link RouteHttpServer} with the Minecraft server and adapts HTTP queries to
 * {@link RouteService}.
 */
// THREADING: onServerStarted()/onServerStopping() are called on the server thread and only enqueue
// work; they never block. All config file I/O and Javalin start()/stop() (socket bind, Jetty shutdown)
// run serially on one daemon thread "squaremap-pro-http-lifecycle" (core 0, max 1, so tasks keep
// submission order and the thread exits when idle). A start task always stops the previous instance
// first (integrated servers can start several times per JVM). `server` is only touched on that thread.
// The route adapter runs on Jetty request threads: it builds an interned ResourceKey (thread-safe
// ConcurrentMap) and calls RouteService.route (any thread, non-blocking) — no level/server access.
public final class RouteHttpLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final Supplier<Path> configDir;
  private final RouteFunction routes;
  private final ThreadPoolExecutor lifecycle;
  private RouteHttpServer server;

  /**
   * @param configDir supplies the Fabric config directory
   * @param routeService shared route service
   */
  public RouteHttpLifecycle(Supplier<Path> configDir, RouteService routeService) {
    this.configDir = Objects.requireNonNull(configDir, "configDir");
    this.routes = adapter(Objects.requireNonNull(routeService, "routeService"));
    this.lifecycle = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
        r -> {
          Thread t = new Thread(r, "squaremap-pro-http-lifecycle");
          t.setDaemon(true);
          return t;
        });
  }

  /** Adapts a {@link RouteQuery} to {@link RouteService#route}. Any thread; non-blocking. */
  static RouteFunction adapter(RouteService routeService) {
    return query -> {
      int sep = query.world().indexOf(':');
      Identifier id;
      try {
        id = Identifier.fromNamespaceAndPath(query.world().substring(0, sep),
            query.world().substring(sep + 1));
      } catch (RuntimeException e) {
        return CompletableFuture.completedFuture(
            RouteOutcome.invalidRequest(RouteRequestParser.DEFAULT_WORLD,
                "invalid 'world' value \"" + query.world() + "\""));
      }
      ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
      return routeService.route(key, query.fromX(), OptionalInt.empty(), query.fromZ(),
          query.toX(), query.toZ());
    };
  }

  /** SERVER_STARTED hook. Server thread; enqueues only. */
  public void onServerStarted() {
    submit(() -> {
      stopCurrent();
      HttpConfig config = HttpConfig.load(configDir.get());
      if (!config.enabled()) {
        LOGGER.info("squaremap-pro HTTP endpoint disabled (http.enabled=false)");
        return;
      }
      RouteHttpServer s = new RouteHttpServer(config, routes);
      try {
        s.start();
        server = s;
        LOGGER.info("squaremap-pro HTTP endpoint listening on http://{}:{}/route", config.bind(),
            s.port());
      } catch (RuntimeException | LinkageError e) {
        LOGGER.error("squaremap-pro HTTP endpoint failed to start on {}:{}; navigation keeps working"
            + " without it", config.bind(), config.port(), e);
      }
    });
  }

  /** SERVER_STOPPING hook. Server thread; enqueues only. */
  public void onServerStopping() {
    submit(this::stopCurrent);
  }

  /** Lifecycle thread only. */
  private void stopCurrent() {
    RouteHttpServer s = server;
    server = null;
    if (s != null) {
      s.stop();
      LOGGER.info("squaremap-pro HTTP endpoint stopped");
    }
  }

  private void submit(Runnable task) {
    try {
      lifecycle.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException | LinkageError e) {
          LOGGER.error("squaremap-pro HTTP lifecycle task failed", e);
        }
      });
    } catch (RejectedExecutionException e) {
      LOGGER.error("squaremap-pro HTTP lifecycle task rejected", e);
    }
  }
}
