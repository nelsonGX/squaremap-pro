package dev.nelsongx.nav.fabric.world;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Mod-owned bounded worker pool for pathfinding, region-graph building and SQLite I/O.
 *
 * <p>Daemon threads named {@code squaremap-pro-nav-N}; {@code max(1, cores / 2)} threads capped at 4;
 * bounded queue. When the queue is full or the pool is shut down, {@link #execute} throws
 * {@link RejectedExecutionException} and {@link #submit} returns a failed future.
 */
// THREADING: execute()/submit() may be called from any thread and never block. Construction happens
// on SERVER_STARTED and shutdown() on SERVER_STOPPING (server thread); shutdown() does not wait.
public final class NavExecutor implements Executor {

  /** Default bounded queue capacity. */
  public static final int DEFAULT_QUEUE_CAPACITY = 1024;

  private final ThreadPoolExecutor pool;

  /** Creates and starts a pool with the default size and queue capacity. */
  public NavExecutor() {
    this(defaultThreads(), DEFAULT_QUEUE_CAPACITY);
  }

  /**
   * Creates and starts a pool.
   *
   * @param threads worker thread count (&gt; 0)
   * @param queueCapacity bounded queue capacity (&gt; 0)
   */
  public NavExecutor(int threads, int queueCapacity) {
    AtomicInteger counter = new AtomicInteger(1);
    ThreadFactory factory = r -> {
      Thread t = new Thread(r, "squaremap-pro-nav-" + counter.getAndIncrement());
      t.setDaemon(true);
      return t;
    };
    this.pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
  }

  /**
   * Default thread count: {@code max(1, availableProcessors / 2)}, capped at 4.
   *
   * @return thread count
   */
  public static int defaultThreads() {
    return Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
  }

  /**
   * Runs a task on a worker. Any thread; never blocks.
   *
   * @throws RejectedExecutionException if the queue is full or the pool is shut down
   */
  @Override
  public void execute(Runnable command) {
    pool.execute(command);
  }

  /**
   * Computes a value on a worker. Any thread; never blocks.
   *
   * @param task the computation
   * @param <T> result type
   * @return future of the result; failed with {@link RejectedExecutionException} on rejection
   */
  public <T> CompletableFuture<T> submit(Supplier<T> task) {
    try {
      return CompletableFuture.supplyAsync(task, pool);
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  /** @return whether shutdown has been requested */
  public boolean isShutdown() {
    return pool.isShutdown();
  }

  /**
   * Stops accepting new tasks. Already-queued tasks still run (so their futures complete) on the
   * daemon workers. Does not wait (safe on the server thread).
   */
  public void shutdown() {
    pool.shutdown();
  }
}
