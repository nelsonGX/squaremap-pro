package dev.nelsongx.map.fabric.http.api;

import dev.nelsongx.map.core.route.Network;
import dev.nelsongx.map.core.store.FeatureStore;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Per-world cache of immutable road/rail {@link Network}s, built lazily on the worker executor from
 * {@code FeatureStore.list(world)} and dropped after every successful write to that world.
 *
 * <p>A build that was already running when a write invalidated the entry still completes for the
 * requests that were waiting on it (they asked before the write), but later requests start a fresh
 * build. Failed builds are removed so the next request retries.
 */
// THREADING: get()/invalidate() — any thread, never block. Builds run on the injected executor
// (FeatureStore I/O + Network.build CPU work). Network is immutable and shared across threads.
public final class NetworkCache {

  private final Executor executor;
  private final ConcurrentHashMap<String, CompletableFuture<Network>> cache = new ConcurrentHashMap<>();

  /** @param executor worker executor */
  public NetworkCache(Executor executor) {
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /**
   * @param store feature store
   * @param worldId world
   * @return future network of the world
   */
  public CompletableFuture<Network> get(FeatureStore store, String worldId) {
    CompletableFuture<Network> f;
    try {
      f = cache.computeIfAbsent(worldId,
          w -> CompletableFuture.supplyAsync(() -> Network.build(store.list(w)), executor));
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(e);
    }
    f.whenComplete((n, e) -> {
      if (e != null) {
        cache.remove(worldId, f);
      }
    });
    return f;
  }

  /** Drops the cached network of a world. */
  public void invalidate(String worldId) {
    cache.remove(worldId);
  }
}
