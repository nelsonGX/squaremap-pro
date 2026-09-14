package dev.nelsongx.nav.fabric.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Per-level cache of {@link ChunkSnapshot}s plus a queue of snapshot requests drained on the server
 * thread under a per-tick budget.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * cache.prepare(minCx, minCz, maxCx, maxCz)
 *     .thenApplyAsync(view -> pathfind(view), navExecutor);   // always *Async with the executor
 * }</pre>
 *
 * <p>The internal future is completed on the server thread; the future returned by
 * {@link #prepare} is a {@code thenApplyAsync(identity, executor)} hop of it, so even non-async
 * dependents run on a {@link NavExecutor} worker, never on the server thread. If the executor
 * rejects the hop, the returned future fails with {@link java.util.concurrent.RejectedExecutionException}.
 *
 * <p>A view reflects each chunk as of the moment it was captured; chunks that are not loaded are
 * simply absent (read as unknown). Snapshots are never taken for chunks that are not loaded.
 */
// THREADING: prepare(), invalidate(), invalidateAll(), cachedChunkCount() — ANY THREAD, non-blocking
// (enqueue side). drain() and close() — SERVER THREAD ONLY (drain side; asserted). The ServerLevel is
// only ever looked up and read inside drain() on the server thread. Entries and views handed out are
// immutable and read by workers.
public final class SnapshotCache {

  /**
   * Tunables.
   *
   * @param tickBudgetNanos max wall time spent snapshotting per server tick (shared across levels)
   * @param maxChunksPerTick max chunks snapshotted per server tick (shared across levels)
   * @param maxRequestChunks max chunks in one {@link #prepare} rectangle
   * @param maxAgeNanos snapshots older than this are re-taken on the next prepare
   * @param maxCachedChunks hard cap on cached snapshots (oldest-accessed evicted first)
   */
  public record Config(long tickBudgetNanos, int maxChunksPerTick, int maxRequestChunks,
      long maxAgeNanos, int maxCachedChunks) {

    /** Validates values. */
    public Config {
      if (tickBudgetNanos <= 0 || maxChunksPerTick <= 0 || maxRequestChunks <= 0 || maxAgeNanos <= 0
          || maxCachedChunks <= 0) {
        throw new IllegalArgumentException("all SnapshotCache.Config values must be > 0");
      }
    }

    /**
     * Defaults: 2 ms / 64 chunks per tick, 4096 chunks per request, 30 s max age, 8192 cached.
     *
     * @return default config
     */
    public static Config defaults() {
      return new Config(TimeUnit.MILLISECONDS.toNanos(2), 64, 4096, TimeUnit.SECONDS.toNanos(30),
          8192);
    }
  }

  /** Per-tick work allowance shared by every cache drained in the same tick. */
  // THREADING: SERVER THREAD ONLY; created and consumed inside one END_SERVER_TICK callback.
  public static final class TickBudget {
    private final long deadline;
    private int chunksLeft;

    /**
     * Starts a budget now.
     *
     * @param config source of limits
     */
    public TickBudget(Config config) {
      this.deadline = System.nanoTime() + config.tickBudgetNanos();
      this.chunksLeft = config.maxChunksPerTick();
    }

    boolean exhausted() {
      return chunksLeft <= 0 || System.nanoTime() >= deadline;
    }

    void chargeChunk() {
      chunksLeft--;
    }
  }

  private static final class Entry {
    final ChunkSnapshot snapshot;
    final long capturedNanos;
    volatile long lastAccessNanos;

    Entry(ChunkSnapshot snapshot, long now) {
      this.snapshot = snapshot;
      this.capturedNanos = now;
      this.lastAccessNanos = now;
    }
  }

  private static final class Request {
    final int minX;
    final int minZ;
    final int width;
    final int total;
    final CompletableFuture<SnapshotWorldView> internal = new CompletableFuture<>();
    CompletableFuture<SnapshotWorldView> result;
    /** Server thread only. */
    int cursor;
    /** Server thread only. */
    final List<ChunkSnapshot> collected = new ArrayList<>();

    Request(int minX, int minZ, int width, int total) {
      this.minX = minX;
      this.minZ = minZ;
      this.width = width;
      this.total = total;
    }

    boolean abandoned() {
      return internal.isDone() || (result != null && result.isDone());
    }
  }

  private static final int PURGE_INTERVAL_TICKS = 200;

  private final ResourceKey<Level> dimension;
  private final int minY;
  private final int maxY;
  private final Config config;
  private final ChunkSnapshotter snapshotter;
  private final Executor completionExecutor;

  private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<Request> incoming = new ConcurrentLinkedQueue<>();
  /** Server thread only. */
  private final ArrayDeque<Request> active = new ArrayDeque<>();
  /** Server thread only. */
  private int ticksSincePurge;
  private volatile boolean closed;

  /**
   * Creates a cache. {@code minY}/{@code maxY} must have been read from the level on the server
   * thread by the caller.
   *
   * @param dimension level key, resolved via {@code MinecraftServer#getLevel} while draining
   * @param minY level min Y (inclusive)
   * @param maxY level max Y (inclusive)
   * @param config tunables
   * @param snapshotter chunk snapshotter
   * @param completionExecutor worker executor that dependents of {@link #prepare} run on
   */
  public SnapshotCache(ResourceKey<Level> dimension, int minY, int maxY, Config config,
      ChunkSnapshotter snapshotter, Executor completionExecutor) {
    this.dimension = Objects.requireNonNull(dimension, "dimension");
    this.minY = minY;
    this.maxY = maxY;
    this.config = Objects.requireNonNull(config, "config");
    this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    this.completionExecutor = Objects.requireNonNull(completionExecutor, "completionExecutor");
  }

  /** @return the level key this cache serves */
  public ResourceKey<Level> dimension() {
    return dimension;
  }

  /**
   * Requests a view covering an inclusive chunk rectangle.
   *
   * <p>ANY THREAD; never blocks. Enqueues the request; the server-thread tick hook fills missing or
   * stale chunks under the per-tick budget and then completes the future.
   *
   * @param minChunkX min chunk x (inclusive)
   * @param minChunkZ min chunk z (inclusive)
   * @param maxChunkX max chunk x (inclusive)
   * @param maxChunkZ max chunk z (inclusive)
   * @return future view; failed with {@link IllegalArgumentException} if the rectangle is empty or
   *     larger than {@link Config#maxRequestChunks()}, or {@link IllegalStateException} if closed
   */
  public CompletableFuture<SnapshotWorldView> prepare(int minChunkX, int minChunkZ, int maxChunkX,
      int maxChunkZ) {
    if (closed) {
      return CompletableFuture.failedFuture(new IllegalStateException("SnapshotCache closed"));
    }
    long w = (long) maxChunkX - minChunkX + 1;
    long d = (long) maxChunkZ - minChunkZ + 1;
    if (w <= 0 || d <= 0) {
      return CompletableFuture.failedFuture(new IllegalArgumentException(
          "empty chunk rectangle: [" + minChunkX + "," + minChunkZ + "]..[" + maxChunkX + ","
              + maxChunkZ + "]"));
    }
    long count = w * d;
    if (count > config.maxRequestChunks()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException(
          "requested " + count + " chunks, max is " + config.maxRequestChunks()));
    }
    Request req = new Request(minChunkX, minChunkZ, (int) w, (int) count);
    req.result = req.internal.thenApplyAsync(v -> v, completionExecutor);
    incoming.add(req);
    if (closed && incoming.remove(req)) {
      req.internal.completeExceptionally(new IllegalStateException("SnapshotCache closed"));
    }
    return req.result;
  }

  /**
   * Drops the cached snapshot of one chunk; the next {@link #prepare} re-snapshots it.
   *
   * <p>ANY THREAD; non-blocking. Views already handed out are unaffected.
   *
   * @param chunkX chunk x
   * @param chunkZ chunk z
   */
  public void invalidate(int chunkX, int chunkZ) {
    entries.remove(ChunkSnapshot.key(chunkX, chunkZ));
  }

  /** Drops every cached snapshot. ANY THREAD; non-blocking. */
  public void invalidateAll() {
    entries.clear();
  }

  /** @return number of cached snapshots (approximate under concurrency). ANY THREAD. */
  public int cachedChunkCount() {
    return entries.size();
  }

  /**
   * Processes queued requests within {@code budget}.
   *
   * <p>SERVER THREAD ONLY (asserted). Called from the {@code END_SERVER_TICK} hook.
   *
   * @param server the server
   * @param budget shared per-tick budget
   * @throws IllegalStateException if not on the server thread
   */
  public void drain(MinecraftServer server, TickBudget budget) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("SnapshotCache.drain must run on the server thread, not "
          + Thread.currentThread().getName());
    }
    Request polled;
    while ((polled = incoming.poll()) != null) {
      active.add(polled);
    }
    if (!active.isEmpty()) {
      ServerLevel level = server.getLevel(dimension);
      if (level == null) {
        failActive(new IllegalStateException("level not loaded: " + dimension));
      } else {
        process(level, budget);
      }
    }
    if (++ticksSincePurge >= PURGE_INTERVAL_TICKS || entries.size() > config.maxCachedChunks()) {
      ticksSincePurge = 0;
      evict();
    }
  }

  /** SERVER THREAD ONLY (called from drain). */
  private void process(ServerLevel level, TickBudget budget) {
    int sinceTimeCheck = 0;
    while (!active.isEmpty()) {
      Request req = active.peek();
      if (req.abandoned()) {
        active.poll();
        continue;
      }
      while (req.cursor < req.total) {
        int cx = req.minX + req.cursor % req.width;
        int cz = req.minZ + req.cursor / req.width;
        long key = ChunkSnapshot.key(cx, cz);
        long now = System.nanoTime();
        Entry e = entries.get(key);
        if (e != null && now - e.capturedNanos <= config.maxAgeNanos()) {
          e.lastAccessNanos = now;
          req.collected.add(e.snapshot);
          req.cursor++;
          if (++sinceTimeCheck >= 256) {
            sinceTimeCheck = 0;
            if (budget.exhausted()) {
              return;
            }
          }
          continue;
        }
        if (budget.exhausted()) {
          return;
        }
        ChunkSnapshot snap = snapshotter.snapshot(level, cx, cz);
        if (snap != null) {
          budget.chargeChunk();
          entries.put(key, new Entry(snap, System.nanoTime()));
          req.collected.add(snap);
        } else if (e != null) {
          entries.remove(key, e); // chunk unloaded since; stale data is not served
        }
        req.cursor++;
      }
      active.poll();
      req.internal.complete(new SnapshotWorldView(minY, maxY, req.collected));
    }
  }

  /** SERVER THREAD ONLY. Removes expired entries, then oldest-accessed entries above the cap. */
  private void evict() {
    long now = System.nanoTime();
    entries.values().removeIf(e -> now - e.capturedNanos > config.maxAgeNanos());
    int cap = config.maxCachedChunks();
    if (entries.size() <= cap) {
      return;
    }
    int target = Math.max(0, cap - cap / 10);
    List<Map.Entry<Long, Entry>> all = new ArrayList<>(entries.entrySet());
    all.sort(Comparator.comparingLong(me -> me.getValue().lastAccessNanos));
    int toRemove = all.size() - target;
    for (int i = 0; i < toRemove && i < all.size(); i++) {
      Map.Entry<Long, Entry> me = all.get(i);
      entries.remove(me.getKey(), me.getValue());
    }
  }

  /**
   * Fails pending requests and clears the cache. Further {@link #prepare} calls fail.
   *
   * <p>SERVER THREAD ONLY (called on SERVER_STOPPING).
   *
   * @param server the server
   */
  public void close(MinecraftServer server) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("SnapshotCache.close must run on the server thread");
    }
    closed = true;
    Request polled;
    while ((polled = incoming.poll()) != null) {
      active.add(polled);
    }
    failActive(new IllegalStateException("server stopping"));
    entries.clear();
  }

  private void failActive(Throwable t) {
    Request r;
    while ((r = active.poll()) != null) {
      r.internal.completeExceptionally(t);
    }
  }
}
