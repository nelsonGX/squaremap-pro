package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Drives a planned region-graph build batch by batch. Minecraft-free.
 *
 * <p>For each batch: prepare its snapshot box, then {@link RegionGraphManager#rebuild} its sectors on the
 * manager lane. Chunks not loaded in the prepared view are skipped (not built) and counted; so are loaded
 * chunks deferred because a built neighbour is unloaded. At most one batch is in flight; the next batch
 * starts {@code delayMillis} after the previous one finished, scheduled with
 * {@link CompletableFuture#delayedExecutor} onto the worker executor (no sleeping anywhere).
 *
 * @param <V> prepared view type
 */
// THREADING: start(), cancel(), progress() — ANY THREAD, non-blocking (start/cancel are called from
// commands on the server thread). Batch steps run on the executor (NavExecutor workers) or on the thread
// that completes the prepare future; prepare must be non-blocking and callable from any thread
// (SnapshotCache.prepare is). The progress listener is invoked on a worker thread and must hop to the
// server thread itself before touching game state.
public final class BuildJob<V extends WorldView> {

  /** Default delay between batches. */
  public static final long DEFAULT_DELAY_MILLIS = 250;

  /**
   * Progress snapshot.
   *
   * @param done chunks rebuilt
   * @param skipped chunks skipped (unloaded or deferred)
   * @param total chunks in the job
   * @param batchesDone finished batches
   * @param batchCount planned batches
   */
  public record Progress(int done, int skipped, int total, int batchesDone, int batchCount) {
    /** @return whether every batch was processed */
    public boolean complete() {
      return batchesDone == batchCount;
    }
  }

  private final RegionGraphManager manager;
  private final ChunkBox area;
  private final List<BuildPlanner.Batch> batches;
  private final Function<ChunkBox, CompletableFuture<V>> prepare;
  private final BiPredicate<V, SectorPos> loaded;
  private final Executor executor;
  private final long delayMillis;
  private final Consumer<Progress> listener;
  private final int total;

  private final AtomicInteger done = new AtomicInteger();
  private final AtomicInteger skipped = new AtomicInteger();
  private final AtomicInteger batchesDone = new AtomicInteger();
  private final CompletableFuture<Progress> completion = new CompletableFuture<>();
  private volatile boolean cancelled;
  private volatile boolean started;

  /**
   * @param manager target graph manager
   * @param area whole job area (tracked for block changes while running)
   * @param batches planned batches (see {@link BuildPlanner})
   * @param prepare snapshot preparation; any thread, non-blocking
   * @param loaded whether a sector's chunk is present in a prepared view
   * @param executor worker executor used to schedule the next batch
   * @param delayMillis delay between batches, &gt;= 0
   * @param listener called after every batch (worker thread); may be null
   */
  public BuildJob(RegionGraphManager manager, ChunkBox area, List<BuildPlanner.Batch> batches,
      Function<ChunkBox, CompletableFuture<V>> prepare, BiPredicate<V, SectorPos> loaded,
      Executor executor, long delayMillis, Consumer<Progress> listener) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.area = Objects.requireNonNull(area, "area");
    this.batches = List.copyOf(batches);
    this.prepare = Objects.requireNonNull(prepare, "prepare");
    this.loaded = Objects.requireNonNull(loaded, "loaded");
    this.executor = Objects.requireNonNull(executor, "executor");
    if (delayMillis < 0) {
      throw new IllegalArgumentException("delayMillis must be >= 0");
    }
    this.delayMillis = delayMillis;
    this.listener = listener;
    long t = 0;
    for (BuildPlanner.Batch b : this.batches) {
      t += b.chunks().chunkCount();
    }
    this.total = (int) Math.min(Integer.MAX_VALUE, t);
  }

  /**
   * Starts the first batch. ANY THREAD; non-blocking. Idempotent.
   *
   * @return the completion future (see {@link #completion()})
   */
  public CompletableFuture<Progress> start() {
    synchronized (this) {
      if (started) {
        return completion;
      }
      started = true;
    }
    manager.setActiveJobArea(area);
    completion.whenComplete((p, e) -> manager.clearActiveJobArea(area));
    runBatch(0);
    return completion;
  }

  /** Stops after the batch in flight. ANY THREAD. */
  public void cancel() {
    cancelled = true;
    if (!started) {
      finish();
    }
  }

  /** @return whether cancel was requested */
  public boolean cancelled() {
    return cancelled;
  }

  /** @return the job area */
  public ChunkBox area() {
    return area;
  }

  /** @return current progress. ANY THREAD. */
  public Progress progress() {
    return new Progress(done.get(), skipped.get(), total, batchesDone.get(), batches.size());
  }

  /**
   * Completes with the final progress when all batches ran or after cancellation (check
   * {@link Progress#complete()} / {@link #cancelled()}); exceptionally if a batch failed.
   *
   * @return completion future
   */
  public CompletableFuture<Progress> completion() {
    return completion;
  }

  private void finish() {
    completion.complete(progress());
  }

  private void runBatch(int i) {
    if (cancelled || i >= batches.size()) {
      finish();
      return;
    }
    BuildPlanner.Batch batch = batches.get(i);
    long preparedAt = System.nanoTime();
    CompletableFuture<V> view;
    try {
      view = prepare.apply(batch.prepareBox());
    } catch (RuntimeException e) {
      completion.completeExceptionally(e);
      return;
    }
    view.thenCompose(v -> manager.rebuild(v, batch.sectors(), loaded, preparedAt, false))
        .whenComplete((res, err) -> {
          if (err != null) {
            completion.completeExceptionally(err);
            return;
          }
          done.addAndGet(res.rebuilt().size());
          skipped.addAndGet(res.unloaded().size() + res.deferred().size());
          batchesDone.incrementAndGet();
          if (listener != null) {
            try {
              listener.accept(progress());
            } catch (RuntimeException ignored) {
              // listener failures must not stop the job
            }
          }
          scheduleNext(i + 1);
        });
  }

  private void scheduleNext(int next) {
    if (cancelled || next >= batches.size()) {
      finish();
      return;
    }
    Runnable step = () -> {
      try {
        runBatch(next);
      } catch (RuntimeException e) {
        completion.completeExceptionally(e);
      }
    };
    Executor target = delayMillis == 0 ? executor
        : CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS, r -> {
          try {
            executor.execute(r);
          } catch (RejectedExecutionException e) {
            completion.completeExceptionally(e);
          }
        });
    try {
      target.execute(step);
    } catch (RejectedExecutionException e) {
      completion.completeExceptionally(e);
    }
  }
}
