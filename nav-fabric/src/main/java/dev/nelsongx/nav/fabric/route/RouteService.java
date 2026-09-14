package dev.nelsongx.nav.fabric.route;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.hierarchy.HierarchicalOptions;
import dev.nelsongx.nav.core.hierarchy.Router;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.fabric.world.NavExecutor;
import dev.nelsongx.nav.fabric.world.NavServices;
import dev.nelsongx.nav.fabric.world.SnapshotCache;
import dev.nelsongx.nav.fabric.world.SnapshotWorldView;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route queries against snapshot-backed world views. Shared by the {@code /nav} command and the HTTP
 * endpoint.
 *
 * <p>Expected failures (bad endpoints, unknown world, server not running, no path, cap) never fail the
 * returned future; they complete it with a non-OK {@link RouteOutcome}.
 */
// THREADING: route() — ANY THREAD, non-blocking; never touches a level or the server. Chunk data is
// captured by SnapshotCache on the server thread; Y resolution and Router.route run on NavExecutor
// workers against the immutable SnapshotWorldView. ResourceKey is an immutable interned value.
public final class RouteService {

  /** Default chunk margin around the endpoints' bounding box. */
  public static final int DEFAULT_MARGIN_CHUNKS = 6;

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private final Supplier<NavServices> services;
  private final Function<ResourceKey<Level>, RegionGraph> graphSupplier;
  private final HierarchicalOptions options;
  private final int marginChunks;

  /**
   * Creates a service with default options and margin.
   *
   * @param services supplier of the running server's services (null when not running); any thread
   * @param graphSupplier region graph per dimension, may return null (flat search); called on workers
   */
  public RouteService(Supplier<NavServices> services,
      Function<ResourceKey<Level>, RegionGraph> graphSupplier) {
    this(services, graphSupplier, HierarchicalOptions.defaults(), DEFAULT_MARGIN_CHUNKS);
  }

  /**
   * Creates a service.
   *
   * @param services supplier of the running server's services (null when not running)
   * @param graphSupplier region graph per dimension, may return null
   * @param options search limits
   * @param marginChunks chunk margin around the endpoints, &gt;= 0
   */
  public RouteService(Supplier<NavServices> services,
      Function<ResourceKey<Level>, RegionGraph> graphSupplier, HierarchicalOptions options,
      int marginChunks) {
    this.services = Objects.requireNonNull(services, "services");
    this.graphSupplier = Objects.requireNonNull(graphSupplier, "graphSupplier");
    this.options = Objects.requireNonNull(options, "options");
    if (marginChunks < 0) {
      throw new IllegalArgumentException("marginChunks must be >= 0");
    }
    this.marginChunks = marginChunks;
  }

  /**
   * Routes between two block columns.
   *
   * <p>ANY THREAD; never blocks. The returned future completes on a nav worker (or immediately on the
   * calling thread for fast failures); it is never completed exceptionally.
   *
   * @param dimension level key
   * @param fromX start block x
   * @param fromY start feet y if known (command): the nearest walkable y within ±3 of it is used
   *     (see {@link Endpoints#nearestWalkableY}), else {@code INVALID_REQUEST} "you are not standing on
   *     walkable ground". If absent (HTTP), the column's ground Y is used.
   * @param fromZ start block z
   * @param toX goal block x
   * @param toZ goal block z
   * @return future outcome
   */
  public CompletableFuture<RouteOutcome> route(ResourceKey<Level> dimension, int fromX,
      OptionalInt fromY, int fromZ, int toX, int toZ) {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(fromY, "fromY");
    String world = worldId(dimension);
    try {
      NavServices s = services.get();
      if (s == null) {
        return CompletableFuture.completedFuture(
            RouteOutcome.notReady(world, "server not running"));
      }
      SnapshotCache cache = s.cache(dimension);
      if (cache == null) {
        return CompletableFuture.completedFuture(
            RouteOutcome.worldNotFound(world, "world not found: " + world));
      }
      ChunkBox box = ChunkBox.around(fromX, fromZ, toX, toZ, marginChunks);
      if (box.chunkCount() > s.config().maxRequestChunks()) {
        return CompletableFuture.completedFuture(
            RouteOutcome.invalidRequest(world, "route too long for one query"));
      }
      NavExecutor executor = s.executor();
      return cache.prepare(box.minChunkX(), box.minChunkZ(), box.maxChunkX(), box.maxChunkZ())
          .handleAsync((view, err) -> err != null
              ? fromThrowable(world, err)
              : search(dimension, world, view, fromX, fromY, fromZ, toX, toZ), executor)
          .exceptionally(t -> fromThrowable(world, t));
    } catch (RuntimeException e) {
      return CompletableFuture.completedFuture(fromThrowable(world, e));
    }
  }

  /** THREADING: NavExecutor worker only (reads the immutable snapshot view). */
  private RouteOutcome search(ResourceKey<Level> dimension, String world, SnapshotWorldView view,
      int fromX, OptionalInt fromY, int fromZ, int toX, int toZ) {
    OptionalInt startY = fromY.isPresent()
        ? Endpoints.nearestWalkableY(view, fromX, fromY.getAsInt(), fromZ)
        : view.groundY(fromX, fromZ);
    OptionalInt goalY = view.groundY(toX, toZ);
    GridPos start = startY.isPresent() ? new GridPos(fromX, startY.getAsInt(), fromZ) : null;
    GridPos goal = goalY.isPresent() ? new GridPos(toX, goalY.getAsInt(), toZ) : null;
    if (start == null) {
      String error = fromY.isPresent()
          ? "you are not standing on walkable ground"
          : noGround(fromX, fromZ);
      return RouteOutcome.failure(RouteOutcome.Status.INVALID_REQUEST, world, null, goal, 0, error);
    }
    if (goal == null) {
      return RouteOutcome.failure(RouteOutcome.Status.INVALID_REQUEST, world, start, null, 0,
          noGround(toX, toZ));
    }
    RegionGraph graph = graphSupplier.apply(dimension);
    PathResult result = Router.route(view, graph, start, goal, options);
    return RouteOutcome.fromPathResult(world, start, goal, result);
  }

  private static String noGround(int x, int z) {
    return "no standable ground at " + x + "," + z + " (or chunk not loaded)";
  }

  /** Maps an unexpected or infrastructure failure to an outcome. ANY THREAD. */
  static RouteOutcome fromThrowable(String world, Throwable t) {
    Throwable cause = t;
    while ((cause instanceof CompletionException || cause instanceof ExecutionException)
        && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof RejectedExecutionException) {
      return RouteOutcome.notReady(world, "navigation workers busy or stopped");
    }
    if (cause instanceof IllegalStateException) {
      // SnapshotCache: "SnapshotCache closed", "server stopping", "level not loaded: ..."
      return RouteOutcome.notReady(world, "world not ready: " + cause.getMessage());
    }
    if (cause instanceof IllegalArgumentException) {
      return RouteOutcome.invalidRequest(world, "invalid request: " + cause.getMessage());
    }
    LOGGER.error("route query in {} failed unexpectedly", world, cause);
    return RouteOutcome.notReady(world, "internal error: " + cause);
  }

  /**
   * @param dimension level key
   * @return {@code namespace:path}, matching squaremap's {@code WorldIdentifier.asString()} form
   */
  public static String worldId(ResourceKey<Level> dimension) {
    return dimension.identifier().toString();
  }
}
