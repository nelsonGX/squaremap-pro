package dev.nelsongx.map.fabric.http;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Starts/stops the {@link MapHttpServer} with the Minecraft server. */
// THREADING: onServerStarted()/onServerStopping() are called on the server thread and only enqueue
// work; they never block. All config file I/O and Javalin start()/stop() (socket bind, Jetty shutdown)
// run serially on one daemon thread "squaremap-pro-http-lifecycle" (core 0, max 1, so tasks keep
// submission order and the thread exits when idle). A start task always stops the previous instance
// first (integrated servers can start several times per JVM). `server` is only touched on that thread.
public final class HttpLifecycle {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final Supplier<Path> configDir;
  private final ThreadPoolExecutor lifecycle;
  private MapHttpServer server;

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

  /** SERVER_STARTED hook. Server thread; enqueues only. */
  public void onServerStarted() {
    submit(() -> {
      stopCurrent();
      HttpConfig config = HttpConfig.load(configDir.get());
      if (!config.enabled()) {
        LOGGER.info("squaremap-pro HTTP server disabled (http.enabled=false)");
        return;
      }
      MapHttpServer s = new MapHttpServer(config);
      try {
        s.start();
        server = s;
        LOGGER.info("squaremap-pro HTTP server listening on http://{}:{}/", config.bind(), s.port());
      } catch (RuntimeException | LinkageError e) {
        LOGGER.error("squaremap-pro HTTP server failed to start on {}:{}", config.bind(),
            config.port(), e);
      }
    });
  }

  /** SERVER_STOPPING hook. Server thread; enqueues only. */
  public void onServerStopping() {
    submit(this::stopCurrent);
  }

  /** Lifecycle thread only. */
  private void stopCurrent() {
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
