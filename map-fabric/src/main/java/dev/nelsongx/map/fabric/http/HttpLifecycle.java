package dev.nelsongx.map.fabric.http;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts/stops the {@link MapHttpServer} with the Minecraft server. */
// THREADING: onServerStarted()/onServerStopping() are called on the server thread and only enqueue
// work; they never block. All config file I/O and Javalin start()/stop() (socket bind, Jetty shutdown)
// run serially on one daemon thread "squaremap-pro-http-lifecycle" (core 0, max 1, so tasks keep
// submission order and the thread exits when idle). A start task always stops the previous instance
// first (integrated servers can start several times per JVM). `server` is only touched on that thread;
// `running` is volatile, written on that thread and read from any thread (e.g. the /mapedit command on
// the server thread).
public final class HttpLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  /**
   * A started HTTP server.
   *
   * @param config the config it was started with
   * @param port the bound port
   */
  public record Running(HttpConfig config, int port) {
  }

  private final Supplier<Path> configDir;
  private final ThreadPoolExecutor lifecycle;
  private MapHttpServer server;
  private volatile Running running;

  /**
   * @param configDir supplies the Fabric config directory
   */
  public HttpLifecycle(Supplier<Path> configDir) {
    this.configDir = Objects.requireNonNull(configDir, "configDir");
    this.lifecycle = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
        r -> {
          Thread t = new Thread(r, "squaremap-pro-http-lifecycle");
          t.setDaemon(true);
          return t;
        });
  }

  /** @return the running server's config and port, or null if not running. ANY THREAD. */
  public Running running() {
    return running;
  }

  /** SERVER_STARTED hook. Server thread; enqueues only. */
  public void onServerStarted() {
    onServerStarted(config -> { });
  }

  /**
   * SERVER_STARTED hook. Server thread; enqueues only.
   *
   * @param configLoaded called on the lifecycle thread with the freshly loaded config (also when the
   *     HTTP server is disabled), before the server is started; must not block for long
   */
  public void onServerStarted(Consumer<HttpConfig> configLoaded) {
    Objects.requireNonNull(configLoaded, "configLoaded");
    submit(() -> {
      stopCurrent();
      HttpConfig config = HttpConfig.load(configDir.get());
      try {
        configLoaded.accept(config);
      } catch (RuntimeException | LinkageError e) {
        LOGGER.error("squaremap-pro config listener failed", e);
      }
      if (!config.enabled()) {
        LOGGER.info("squaremap-pro HTTP server disabled (http.enabled=false)");
        return;
      }
      MapHttpServer s = new MapHttpServer(config);
      try {
        s.start();
        server = s;
        running = new Running(config, s.port());
        LOGGER.info("squaremap-pro HTTP server listening on http://{}:{}/", config.bind(), s.port());
      } catch (RuntimeException | LinkageError e) {
        LOGGER.error("squaremap-pro HTTP server failed to start on {}:{}", config.bind(),
            config.port(), e);
      }
    });
  }

  /** SERVER_STOPPING hook. Server thread; clears {@link #running()} at once, stops asynchronously. */
  public void onServerStopping() {
    running = null;
    submit(this::stopCurrent);
  }

  /** Lifecycle thread only. */
  private void stopCurrent() {
    running = null;
    MapHttpServer s = server;
    server = null;
    if (s != null) {
      s.stop();
      LOGGER.info("squaremap-pro HTTP server stopped");
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
