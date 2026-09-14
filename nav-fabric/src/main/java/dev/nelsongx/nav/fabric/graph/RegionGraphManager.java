package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphStore;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the region graph of one dimension: current value, serialized mutations, persistence and
 * change tracking. Minecraft-free (the dimension is identified by a display name only).
 *
 * <h2>Lane</h2>
 *
 * Every mutation (load, build batch, dirty rebuild) runs through {@link #onLane}: a chain of futures in
 * which each task is submitted to the executor only after the previous one finished, so
 * {@code withSectorsRebuilt} + {@code set} never race.
 *
 * <h2>Rebuild safety</h2>
 *
 * {@link #rebuild} only rebuilds a candidate sector when its chunk is loaded in the view <em>and</em>
 * every built sector within {@link #VIEW_MARGIN} sectors of it is loaded too; otherwise the rebuild would
 * read those built neighbours as empty and drop their links. Skipped sectors are reported as
 * {@code unloaded} / {@code deferred}.
 *
 * <h2>Change tracking</h2>
 *
 * {@link #markChanged} records the time of the last block change per sector and adds it to the dirty
 * set. After a rebuild applied from a view whose preparation started at {@code preparedAtNanos}, every
 * sector within {@link #VIEW_MARGIN} of a rebuilt sector that changed at or after that instant is marked
 * dirty again, because the view may predate the change.
 *
 * <h2>Persistence</h2>
 *
 * Saves are debounced ({@link #DEFAULT_SAVE_DEBOUNCE_MILLIS} after the last change) and serialized with a
 * lock held only by worker threads. {@link #saveOnStop} schedules a final save without waiting.
 */
// THREADING: current(), acceptsChange(), markChanged(), onChunkLoaded(), setActiveJobArea(), counters —
// ANY THREAD, non-blocking (called from the server thread by the block-change hook, tick and commands).
// load(), rebuild(), rebuildDirty(), saveOnStop() — ANY THREAD, non-blocking: they only enqueue work.
// The queued work (RegionGraph.withSectorsRebuilt, RegionGraphStore.load/save, planning of dirty groups'
// results) runs on the injected executor (NavExecutor workers), never on the caller's thread unless the
// executor is a direct executor (tests). The graph reference is an AtomicReference read from any thread.
public final class RegionGraphManager {

  /** Sector edge length: one sector == one chunk. */
  public static final int SECTOR_SIZE = 16;
  /** Sectors around a rebuilt sector whose data withSectorsRebuilt may read. */
  public static final int VIEW_MARGIN = 2;
  /** Default save debounce. */
  public static final long DEFAULT_SAVE_DEBOUNCE_MILLIS = 10_000;
  /** Dirty sectors are grouped into cells of this many sectors per axis for snapshotting. */
  public static final int DIRTY_CELL = 8;
  /** Change timestamps older than this are forgotten. */
  private static final long CHANGE_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(10);

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  /**
   * Result of one rebuild.
   *
   * @param rebuilt sectors rebuilt
   * @param unloaded candidates whose chunk was not loaded in the view (not built)
   * @param deferred loaded candidates with an unloaded built sector nearby (not built)
   * @param dropped dirty candidates no longer part of the graph (dirty rebuilds only)
   */
  public record RebuildResult(List<SectorPos> rebuilt, List<SectorPos> unloaded,
      List<SectorPos> deferred, List<SectorPos> dropped) {
  }

  private final String name;
  private final int minY;
  private final int maxY;
  private final Path file;
  private final Executor executor;
  private final long saveDebounceMillis;

  private final AtomicReference<RegionGraph> graph;
  private final Object laneLock = new Object();
  /** Guarded by laneLock. */
  private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

  private final Object saveLock = new Object();
  private final AtomicLong changeGen = new AtomicLong();
  /** Guarded by saveLock. */
  private long savedGen;
  private final AtomicLong saveRequest = new AtomicLong();
  private volatile boolean loading;
  private volatile boolean closed;

  private final Set<SectorPos> dirty = ConcurrentHashMap.newKeySet();
  private final Set<SectorPos> waiting = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<SectorPos, Long> lastChangeNanos = new ConcurrentHashMap<>();
  private final AtomicBoolean dirtyRoundInFlight = new AtomicBoolean();
  private final AtomicReference<ChunkBox> activeJobArea = new AtomicReference<>();

  /**
   * @param name display name for logs (e.g. {@code minecraft:overworld})
   * @param minY level min Y (inclusive)
   * @param maxY level max Y (inclusive)
   * @param file database file, or null for no persistence
   * @param executor worker executor for lane tasks and I/O
   * @param saveDebounceMillis save debounce, &gt;= 0
   */
  public RegionGraphManager(String name, int minY, int maxY, Path file, Executor executor,
      long saveDebounceMillis) {
    this.name = Objects.requireNonNull(name, "name");
    this.minY = minY;
    this.maxY = maxY;
    this.file = file;
    this.executor = Objects.requireNonNull(executor, "executor");
    this.saveDebounceMillis = saveDebounceMillis;
    this.graph = new AtomicReference<>(RegionGraph.empty(SECTOR_SIZE, minY, maxY));
  }

  /** @return display name */
  public String name() {
    return name;
  }

  /** @return the current graph (never null). ANY THREAD. */
  public RegionGraph current() {
    return graph.get();
  }

  /** @return number of dirty sectors (approximate). ANY THREAD. */
  public int dirtyCount() {
    return dirty.size();
  }

  /** @return number of sectors waiting for a chunk load (approximate). ANY THREAD. */
  public int waitingCount() {
    return waiting.size();
  }

  /** @return whether a load is pending or running. ANY THREAD. */
  public boolean loading() {
    return loading;
  }

  // ---------------------------------------------------------------------------------------------
  // Lane
  // ---------------------------------------------------------------------------------------------

  /**
   * Runs {@code task} on the executor after every previously enqueued lane task has finished.
   * ANY THREAD; never blocks.
   *
   * @param task task
   * @param <T> result type
   * @return future of the result; failed if the task throws or the executor rejects it
   */
  public <T> CompletableFuture<T> onLane(Supplier<T> task) {
    Objects.requireNonNull(task, "task");
    CompletableFuture<T> result = new CompletableFuture<>();
    CompletableFuture<Void> prev;
    synchronized (laneLock) {
      prev = tail;
      tail = result.handle((v, e) -> null);
    }
    prev.whenComplete((v, e) -> {
      try {
        executor.execute(() -> {
          try {
            result.complete(task.get());
          } catch (Throwable t) {
            result.completeExceptionally(t);
          }
        });
      } catch (RejectedExecutionException ex) {
        result.completeExceptionally(ex);
      }
    });
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Persistence
  // ---------------------------------------------------------------------------------------------

  /**
   * Loads the graph from {@link #file} on the lane. A missing file keeps the empty graph; a failing or
   * incompatible file (sector size != 16 or different Y range) is logged and discarded. ANY THREAD.
   *
   * @return completion (never exceptional)
   */
  public CompletableFuture<Void> load() {
    if (file == null) {
      return CompletableFuture.completedFuture(null);
    }
    loading = true;
    return onLane(() -> {
      try {
        if (!Files.isRegularFile(file)) {
          LOGGER.info("no region graph for {} at {}", name, file);
          return null;
        }
        RegionGraph loaded = RegionGraphStore.load(file);
        if (loaded.sectorSize() != SECTOR_SIZE || loaded.minY() != minY || loaded.maxY() != maxY) {
          LOGGER.warn("discarding region graph for {}: {} does not match sectorSize={} y={}..{}",
              name, loaded, SECTOR_SIZE, minY, maxY);
          return null;
        }
        if (!graph.get().sectors().isEmpty()) {
          // load() is meant to be the first lane task; never overwrite sectors built meanwhile.
          LOGGER.warn("region graph for {} was built before load finished; ignoring {}", name, file);
          return null;
        }
        graph.set(loaded);
        synchronized (saveLock) {
          savedGen = changeGen.get();
        }
        LOGGER.info("loaded region graph for {}: {}", name, loaded);
      } catch (RuntimeException e) {
        LOGGER.error("failed to load region graph for {} from {}; starting empty", name, file, e);
      }
      return null;
    }).handle((v, e) -> {
      loading = false;
      if (e != null) {
        LOGGER.error("region graph load for {} did not run", name, e);
      }
      return null;
    });
  }

  private void graphChanged() {
    changeGen.incrementAndGet();
    requestSave();
  }

  /** Schedules a save {@link #saveDebounceMillis} from now, superseding earlier requests. ANY THREAD. */
  private void requestSave() {
    if (file == null || closed) {
      return;
    }
    long req = saveRequest.incrementAndGet();
    Executor delayed = CompletableFuture.delayedExecutor(saveDebounceMillis, TimeUnit.MILLISECONDS,
        safe(executor));
    delayed.execute(() -> {
      if (saveRequest.get() == req) {
        saveNow();
      }
    });
  }

  /**
   * Final best-effort save: cancels pending debounced saves and enqueues a save directly on the
   * executor (not the lane, so it is not held up by a running batch). Does not wait. ANY THREAD
   * (called on SERVER_STOPPING before the executor is shut down).
   */
  public void saveOnStop() {
    closed = true;
    saveRequest.incrementAndGet();
    activeJobArea.set(null);
    if (file == null) {
      return;
    }
    try {
      executor.execute(this::saveNow);
    } catch (RejectedExecutionException e) {
      LOGGER.warn("could not schedule final region graph save for {}", name, e);
    }
  }

  /** WORKER THREAD: blocking SQLite I/O. Saves if the graph changed since the last save/load. */
  void saveNow() {
    if (file == null) {
      return;
    }
    synchronized (saveLock) {
      long gen = changeGen.get();
      if (gen == savedGen) {
        return;
      }
      RegionGraph g = graph.get();
      try {
        Files.createDirectories(file.toAbsolutePath().getParent());
        RegionGraphStore.save(g, file);
        savedGen = gen;
        LOGGER.debug("saved region graph for {}: {}", name, g);
      } catch (Exception e) {
        LOGGER.error("failed to save region graph for {} to {}", name, file, e);
      }
    }
  }

  private static Executor safe(Executor e) {
    return r -> {
      try {
        e.execute(r);
      } catch (RejectedExecutionException ignored) {
        // executor shut down (server stopping); saveOnStop handles the final save
      }
    };
  }

  // ---------------------------------------------------------------------------------------------
  // Change tracking
  // ---------------------------------------------------------------------------------------------

  /**
   * Sets (or clears with null) the chunk area of the running build job; changes inside it are tracked
   * even before those sectors are built. ANY THREAD.
   *
   * @param area job area or null
   */
  public void setActiveJobArea(ChunkBox area) {
    activeJobArea.set(area);
  }

  /**
   * Clears the job area if it is still {@code area} (identity). ANY THREAD.
   *
   * @param area the area set by the finishing job
   */
  public void clearActiveJobArea(ChunkBox area) {
    activeJobArea.compareAndSet(area, null);
  }

  /**
   * Whether a block change in chunk {@code (cx, cz)} matters: the sector is built, a load is pending
   * (the loaded graph may contain it), or a build job covers it. ANY THREAD; allocation-light.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @return whether to call {@link #markChanged}
   */
  public boolean acceptsChange(int cx, int cz) {
    if (closed) {
      return false;
    }
    if (loading) {
      return true;
    }
    ChunkBox job = activeJobArea.get();
    if (job != null && cx >= job.minChunkX() && cx <= job.maxChunkX() && cz >= job.minChunkZ()
        && cz <= job.maxChunkZ()) {
      return true;
    }
    return graph.get().sectors().contains(new SectorPos(cx, cz));
  }

  /**
   * Records a block change. ANY THREAD.
   *
   * @param sector changed sector
   * @param nanos {@code System.nanoTime()} of the change
   */
  public void markChanged(SectorPos sector, long nanos) {
    lastChangeNanos.put(sector, nanos);
    waiting.remove(sector);
    dirty.add(sector);
  }

  /**
   * A chunk was loaded: sectors waiting for it (or for a neighbour within {@link #VIEW_MARGIN}) become
   * dirty again. ANY THREAD.
   *
   * @param cx chunk x
   * @param cz chunk z
   */
  public void onChunkLoaded(int cx, int cz) {
    if (waiting.isEmpty()) {
      return;
    }
    for (int dz = -VIEW_MARGIN; dz <= VIEW_MARGIN; dz++) {
      for (int dx = -VIEW_MARGIN; dx <= VIEW_MARGIN; dx++) {
        SectorPos s = new SectorPos(cx + dx, cz + dz);
        if (waiting.remove(s)) {
          dirty.add(s);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Rebuild
  // ---------------------------------------------------------------------------------------------

  /**
   * Rebuilds candidates from a prepared view on the lane. ANY THREAD; never blocks.
   *
   * @param view prepared view (must cover candidates expanded by {@link #VIEW_MARGIN})
   * @param candidates sectors to rebuild
   * @param loaded whether a sector's chunk is present in the view
   * @param preparedAtNanos {@code System.nanoTime()} taken before the view was requested
   * @param onlyBuilt dirty mode: drop candidates that are not built (unless inside the job area)
   * @param <V> view type
   * @return future result
   */
  public <V extends WorldView> CompletableFuture<RebuildResult> rebuild(V view,
      Collection<SectorPos> candidates, BiPredicate<V, SectorPos> loaded, long preparedAtNanos,
      boolean onlyBuilt) {
    Objects.requireNonNull(view, "view");
    Objects.requireNonNull(loaded, "loaded");
    List<SectorPos> copy = List.copyOf(candidates);
    return onLane(() -> applyRebuild(view, copy, loaded, preparedAtNanos, onlyBuilt));
  }

  /** LANE (worker thread). */
  private <V extends WorldView> RebuildResult applyRebuild(V view, List<SectorPos> candidates,
      BiPredicate<V, SectorPos> loaded, long preparedAtNanos, boolean onlyBuilt) {
    RegionGraph g = graph.get();
    Set<SectorPos> built = g.sectors();
    List<SectorPos> rebuilt = new ArrayList<>();
    List<SectorPos> unloaded = new ArrayList<>();
    List<SectorPos> deferred = new ArrayList<>();
    List<SectorPos> dropped = new ArrayList<>();
    ChunkBox job = activeJobArea.get();
    for (SectorPos s : new TreeSet<>(candidates)) {
      if (onlyBuilt && !built.contains(s)) {
        if (job != null && s.sx() >= job.minChunkX() && s.sx() <= job.maxChunkX()
            && s.sz() >= job.minChunkZ() && s.sz() <= job.maxChunkZ()) {
          dirty.add(s); // a running job may still build it; retry later
        } else {
          dropped.add(s);
        }
        continue;
      }
      if (!loaded.test(view, s)) {
        unloaded.add(s);
        continue;
      }
      if (hasUnloadedBuiltNeighbour(view, loaded, built, s)) {
        deferred.add(s);
        continue;
      }
      rebuilt.add(s);
    }
    if (!rebuilt.isEmpty()) {
      RegionGraph next = g.withSectorsRebuilt(view, rebuilt);
      graph.set(next);
      graphChanged();
      if (!lastChangeNanos.isEmpty()) {
        Set<SectorPos> nextSectors = next.sectors();
        for (SectorPos s : rebuilt) {
          for (int dz = -VIEW_MARGIN; dz <= VIEW_MARGIN; dz++) {
            for (int dx = -VIEW_MARGIN; dx <= VIEW_MARGIN; dx++) {
              SectorPos n = new SectorPos(s.sx() + dx, s.sz() + dz);
              Long t = lastChangeNanos.get(n);
              if (t != null && t - preparedAtNanos >= 0 && nextSectors.contains(n)) {
                dirty.add(n);
              }
            }
          }
        }
      }
    }
    return new RebuildResult(List.copyOf(rebuilt), List.copyOf(unloaded), List.copyOf(deferred),
        List.copyOf(dropped));
  }

  private static <V extends WorldView> boolean hasUnloadedBuiltNeighbour(V view,
      BiPredicate<V, SectorPos> loaded, Set<SectorPos> built, SectorPos s) {
    for (int dz = -VIEW_MARGIN; dz <= VIEW_MARGIN; dz++) {
      for (int dx = -VIEW_MARGIN; dx <= VIEW_MARGIN; dx++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        SectorPos n = new SectorPos(s.sx() + dx, s.sz() + dz);
        if (built.contains(n) && !loaded.test(view, n)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Drains the dirty set and rebuilds it: dirty sectors are grouped into {@link #DIRTY_CELL}-sized cells,
   * each cell's bounds + {@link #VIEW_MARGIN} is prepared and rebuilt on the lane. Sectors that could not
   * be rebuilt (unloaded / deferred) wait for a nearby chunk load ({@link #onChunkLoaded}); a failed
   * preparation puts its sectors back into the dirty set. At most one round runs at a time.
   *
   * <p>ANY THREAD; never blocks (called every N ticks on the server thread).
   *
   * @param prepare snapshot preparation (any thread, non-blocking)
   * @param loaded whether a sector's chunk is present in a prepared view
   * @param <V> view type
   * @return completion of the round (never exceptional); an already-completed future if nothing to do
   */
  public <V extends WorldView> CompletableFuture<Void> rebuildDirty(
      Function<ChunkBox, CompletableFuture<V>> prepare, BiPredicate<V, SectorPos> loaded) {
    long now = System.nanoTime();
    lastChangeNanos.values().removeIf(t -> now - t > CHANGE_RETENTION_NANOS);
    if (closed || dirty.isEmpty() || !dirtyRoundInFlight.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }
    Map<SectorPos, List<SectorPos>> cells = new TreeMap<>();
    for (SectorPos s : List.copyOf(dirty)) {
      if (dirty.remove(s)) {
        SectorPos cell = new SectorPos(Math.floorDiv(s.sx(), DIRTY_CELL),
            Math.floorDiv(s.sz(), DIRTY_CELL));
        cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(s);
      }
    }
    List<CompletableFuture<Void>> rounds = new ArrayList<>();
    for (List<SectorPos> group : cells.values()) {
      int x0 = Integer.MAX_VALUE;
      int z0 = Integer.MAX_VALUE;
      int x1 = Integer.MIN_VALUE;
      int z1 = Integer.MIN_VALUE;
      for (SectorPos s : group) {
        x0 = Math.min(x0, s.sx());
        z0 = Math.min(z0, s.sz());
        x1 = Math.max(x1, s.sx());
        z1 = Math.max(z1, s.sz());
      }
      ChunkBox box = new ChunkBox(x0 - VIEW_MARGIN, z0 - VIEW_MARGIN, x1 + VIEW_MARGIN,
          z1 + VIEW_MARGIN);
      long preparedAt = System.nanoTime();
      CompletableFuture<Void> round;
      try {
        round = prepare.apply(box)
            .thenCompose(view -> rebuild(view, group, loaded, preparedAt, true))
            .handle((res, err) -> {
              if (err != null) {
                if (!closed) {
                  dirty.addAll(group);
                  LOGGER.debug("dirty region rebuild for {} failed; will retry", name, err);
                }
              } else {
                waiting.addAll(res.unloaded());
                waiting.addAll(res.deferred());
              }
              return null;
            });
      } catch (RuntimeException e) {
        dirty.addAll(group);
        round = CompletableFuture.completedFuture(null);
      }
      rounds.add(round);
    }
    return CompletableFuture.allOf(rounds.toArray(new CompletableFuture<?>[0]))
        .handle((v, e) -> {
          dirtyRoundInFlight.set(false);
          return null;
        });
  }
}
