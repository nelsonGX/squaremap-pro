package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import dev.nelsongx.nav.fabric.world.NavServices;
import dev.nelsongx.nav.fabric.world.SnapshotCache;
import dev.nelsongx.nav.fabric.world.SnapshotWorldView;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-server region-graph services: one {@link RegionGraphManager} per level, the running
 * {@code /navbuild} job per level, and the periodic dirty-sector rebuild.
 *
 * <p>Created on {@code SERVER_STARTED} right after {@link NavServices#start}; stopped on
 * {@code SERVER_STOPPING} right before {@link NavServices#stop} so the final saves are queued on the
 * executor before it stops accepting work (queued tasks still run after {@code shutdown()}).
 */
// THREADING: start(), stop(), tick(), startJob(), cancelJob() — SERVER THREAD ONLY (start/stop/tick
// asserted): they read the MinecraftServer/ServerLevel (world path, level list, Y bounds) and only
// enqueue work. current(), manager(), graph(), job() — ANY THREAD, non-blocking (graph() is called by
// RouteService on NavExecutor workers). All graph mutation and SQLite I/O happens inside
// RegionGraphManager on NavExecutor workers; worker code receives only Paths, ints and snapshot views.
public final class NavGraphServices {

  /** Ticks between dirty-sector rebuild rounds. */
  public static final int DIRTY_INTERVAL_TICKS = 100;

  /** A sector counts as loaded if its chunk snapshot is present in the prepared view. */
  public static final BiPredicate<SnapshotWorldView, SectorPos> LOADED =
      (view, s) -> view.hasChunk(s.sx(), s.sz());

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private static volatile NavGraphServices current;

  private final NavServices nav;
  /** Immutable after construction. */
  private final Map<ResourceKey<Level>, RegionGraphManager> managers;
  private final ConcurrentHashMap<ResourceKey<Level>, BuildJob<SnapshotWorldView>> jobs =
      new ConcurrentHashMap<>();
  /** Server thread only. */
  private int ticks;

  private NavGraphServices(NavServices nav, Map<ResourceKey<Level>, RegionGraphManager> managers) {
    this.nav = nav;
    this.managers = managers;
  }

  /**
   * Creates managers for every level and starts their asynchronous loads. SERVER THREAD ONLY.
   *
   * @param server the started server
   * @param nav the nav services just started for it
   * @return the services
   */
  public static NavGraphServices start(MinecraftServer server, NavServices nav) {
    requireServerThread(server, "start");
    Objects.requireNonNull(nav, "nav");
    NavGraphServices old = current;
    if (old != null) {
      old.shutdown();
    }
    // Resolved on the server thread; workers only receive the resulting Path.
    Path root = server.getWorldPath(LevelResource.ROOT);
    Map<ResourceKey<Level>, RegionGraphManager> m = new HashMap<>();
    for (ServerLevel level : server.getAllLevels()) {
      ResourceKey<Level> key = level.dimension();
      Identifier id = key.identifier();
      Path file = null;
      try {
        file = root.resolve(RegionStorePaths.relativePath(id.getNamespace(), id.getPath()))
            .normalize();
      } catch (IllegalArgumentException e) {
        LOGGER.warn("region graph for {} will not be persisted: {}", id, e.getMessage());
      }
      m.put(key, new RegionGraphManager(id.toString(), level.getMinY(), level.getMaxY(), file,
          nav.executor(), RegionGraphManager.DEFAULT_SAVE_DEBOUNCE_MILLIS));
    }
    NavGraphServices s = new NavGraphServices(nav, Map.copyOf(m));
    current = s;
    for (RegionGraphManager manager : s.managers.values()) {
      manager.load();
    }
    return s;
  }

  /**
   * Cancels jobs and queues a final save per level; never waits. SERVER THREAD ONLY. Call before
   * {@link NavServices#stop}.
   *
   * @param server the stopping server
   */
  public static void stop(MinecraftServer server) {
    requireServerThread(server, "stop");
    NavGraphServices s = current;
    current = null;
    if (s != null) {
      s.shutdown();
    }
  }

  /** @return services of the running server, or null. ANY THREAD. */
  public static NavGraphServices current() {
    return current;
  }

  private void shutdown() {
    for (BuildJob<SnapshotWorldView> job : jobs.values()) {
      job.cancel();
    }
    jobs.clear();
    for (RegionGraphManager manager : managers.values()) {
      manager.saveOnStop();
    }
  }

  /** @return the nav services these graph services were started with. ANY THREAD. */
  public NavServices nav() {
    return nav;
  }

  /**
   * @param dimension level key
   * @return its manager or null. ANY THREAD.
   */
  public RegionGraphManager manager(ResourceKey<Level> dimension) {
    return managers.get(dimension);
  }

  /**
   * @param dimension level key
   * @return the current graph or null if the level is unknown. ANY THREAD.
   */
  public RegionGraph graph(ResourceKey<Level> dimension) {
    RegionGraphManager m = managers.get(dimension);
    return m == null ? null : m.current();
  }

  /**
   * @param dimension level key
   * @return the running job or null. ANY THREAD.
   */
  public BuildJob<SnapshotWorldView> job(ResourceKey<Level> dimension) {
    BuildJob<SnapshotWorldView> job = jobs.get(dimension);
    return job == null || job.completion().isDone() ? null : job;
  }

  /**
   * Starts a build job unless one is already running for the level. SERVER THREAD (commands).
   *
   * @param dimension level key
   * @param area chunk area
   * @param listener progress listener (worker thread)
   * @return the started job, or null if the level is unknown or a job is already running
   */
  public BuildJob<SnapshotWorldView> startJob(ResourceKey<Level> dimension, ChunkBox area,
      Consumer<BuildJob.Progress> listener) {
    RegionGraphManager manager = managers.get(dimension);
    SnapshotCache cache = nav.cache(dimension);
    if (manager == null || cache == null || job(dimension) != null) {
      return null;
    }
    Function<ChunkBox, CompletableFuture<SnapshotWorldView>> prepare =
        box -> cache.prepare(box.minChunkX(), box.minChunkZ(), box.maxChunkX(), box.maxChunkZ());
    BuildJob<SnapshotWorldView> job = new BuildJob<>(manager, area, BuildPlanner.plan(area), prepare,
        LOADED, nav.executor(), BuildJob.DEFAULT_DELAY_MILLIS, listener);
    jobs.put(dimension, job);
    job.completion().whenComplete((p, e) -> jobs.remove(dimension, job));
    job.start();
    return job;
  }

  /**
   * Every {@link #DIRTY_INTERVAL_TICKS} ticks starts a dirty rebuild round per level (non-blocking).
   * SERVER THREAD ONLY.
   *
   * @param server the ticking server
   */
  public void tick(MinecraftServer server) {
    requireServerThread(server, "tick");
    if (++ticks < DIRTY_INTERVAL_TICKS) {
      return;
    }
    ticks = 0;
    for (Map.Entry<ResourceKey<Level>, RegionGraphManager> e : managers.entrySet()) {
      RegionGraphManager manager = e.getValue();
      if (manager.dirtyCount() == 0) {
        continue;
      }
      SnapshotCache cache = nav.cache(e.getKey());
      if (cache == null) {
        continue;
      }
      manager.rebuildDirty(
          box -> cache.prepare(box.minChunkX(), box.minChunkZ(), box.maxChunkX(), box.maxChunkZ()),
          LOADED);
    }
  }

  private static void requireServerThread(MinecraftServer server, String what) {
    if (!server.isSameThread()) {
      throw new IllegalStateException("NavGraphServices." + what
          + " must run on the server thread, not " + Thread.currentThread().getName());
    }
  }
}
