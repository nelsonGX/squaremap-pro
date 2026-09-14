package dev.nelsongx.nav.fabric.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Per-server holder of the nav worker pool, block classifier and per-level {@link SnapshotCache}s.
 *
 * <p>Created on {@code SERVER_STARTED}, discarded on {@code SERVER_STOPPING}. Caches are created
 * lazily per {@code ResourceKey<Level>}; level height bounds are captured once on the server thread
 * at start so lazy creation needs no level access. Levels registered after server start are not
 * served ({@link #cache} returns null).
 */
// THREADING: start(), stop(), tick(), onDataPackReload() — SERVER THREAD ONLY (asserted).
// current(), cache(), executor() — ANY THREAD, non-blocking.
public final class NavServices {

  private record Bounds(int minY, int maxY) {
  }

  private static volatile NavServices current;

  private final NavExecutor executor;
  private final BlockClassifier classifier;
  private final ChunkSnapshotter snapshotter;
  private final SnapshotCache.Config config;
  /** Immutable after construction; captured on the server thread. */
  private final Map<ResourceKey<Level>, Bounds> bounds;
  private final ConcurrentHashMap<ResourceKey<Level>, SnapshotCache> caches =
      new ConcurrentHashMap<>();

  private NavServices(MinecraftServer server, SnapshotCache.Config config) {
    this.config = config;
    this.classifier = new BlockClassifier();
    this.classifier.rebuild();
    this.snapshotter = new ChunkSnapshotter(classifier);
    Map<ResourceKey<Level>, Bounds> b = new HashMap<>();
    for (ServerLevel level : server.getAllLevels()) {
      b.put(level.dimension(), new Bounds(level.getMinY(), level.getMaxY()));
    }
    this.bounds = Map.copyOf(b);
    this.executor = new NavExecutor();
  }

  /**
   * Creates services for a starting server. SERVER THREAD ONLY.
   *
   * @param server the server
   * @param config cache tunables
   * @return the new services
   */
  public static NavServices start(MinecraftServer server, SnapshotCache.Config config) {
    requireServerThread(server, "start");
    NavServices old = current;
    if (old != null) {
      old.shutdown(server);
    }
    NavServices s = new NavServices(server, Objects.requireNonNull(config, "config"));
    current = s;
    return s;
  }

  /**
   * Fails pending requests, clears caches and shuts down the worker pool. SERVER THREAD ONLY.
   *
   * @param server the stopping server
   */
  public static void stop(MinecraftServer server) {
    requireServerThread(server, "stop");
    NavServices s = current;
    current = null;
    if (s != null) {
      s.shutdown(server);
    }
  }

  /** @return services of the running server, or null. ANY THREAD. */
  public static NavServices current() {
    return current;
  }

  private void shutdown(MinecraftServer server) {
    for (SnapshotCache c : caches.values()) {
      c.close(server);
    }
    caches.clear();
    executor.shutdown();
  }

  /** @return the worker pool. ANY THREAD. */
  public NavExecutor executor() {
    return executor;
  }

  /**
   * The snapshot cache for a level, created on first use. ANY THREAD; non-blocking.
   *
   * @param dimension level key
   * @return the cache, or null if the level was not present at server start
   */
  public SnapshotCache cache(ResourceKey<Level> dimension) {
    Bounds b = bounds.get(dimension);
    if (b == null) {
      return null;
    }
    return caches.computeIfAbsent(dimension,
        k -> new SnapshotCache(k, b.minY(), b.maxY(), config, snapshotter, executor));
  }

  /**
   * Drains every cache's queue under one shared per-tick budget. SERVER THREAD ONLY.
   *
   * @param server the ticking server
   */
  public void tick(MinecraftServer server) {
    requireServerThread(server, "tick");
    if (caches.isEmpty()) {
      return;
    }
    SnapshotCache.TickBudget budget = new SnapshotCache.TickBudget(config);
    for (SnapshotCache c : caches.values()) {
      c.drain(server, budget);
    }
  }

  /**
   * Rebuilds the classification table (tags may have changed) and drops all snapshots.
   * SERVER THREAD ONLY.
   *
   * @param server the server
   */
  public void onDataPackReload(MinecraftServer server) {
    requireServerThread(server, "onDataPackReload");
    classifier.rebuild();
    for (SnapshotCache c : caches.values()) {
      c.invalidateAll();
    }
  }

  private static void requireServerThread(MinecraftServer server, String what) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("NavServices." + what + " must run on the server thread, not "
          + Thread.currentThread().getName());
    }
  }
}
