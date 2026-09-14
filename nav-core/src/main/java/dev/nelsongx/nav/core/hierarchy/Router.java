package dev.nelsongx.nav.core.hierarchy;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.path.AStar;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.simplify.PathSimplifier;
import java.util.Objects;

/**
 * Facade that picks hierarchical or flat search and simplifies the result.
 *
 * <p><b>Pure:</b> no static mutable state, no threads, no I/O.
 */
// THREADING: pure function; runs on worker threads against snapshot-backed WorldViews. Never call on
// the server thread.
public final class Router {

  private Router() {
  }

  /**
   * Routes from {@code start} to {@code goal}.
   *
   * <p>Uses {@link HierarchicalPathfinder#findPath} when {@code graph} is non-null, otherwise
   * {@code AStar.findPath(world, start, goal, opts.maxFlatNodes())}. On success the points are replaced
   * by {@link PathSimplifier#simplify(WorldView, java.util.List)} (first and last points preserved);
   * {@code nodesExpanded} is unchanged. Failures are returned as-is.
   *
   * @param world world to search
   * @param graph region graph, or {@code null} (nullable) to search flat
   * @param start start feet position
   * @param goal goal feet position
   * @param opts effort limits
   * @return the simplified result
   * @throws NullPointerException if {@code world}, {@code start}, {@code goal} or {@code opts} is null
   */
  public static PathResult route(WorldView world, RegionGraph graph, GridPos start, GridPos goal,
      HierarchicalOptions opts) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(opts, "opts");
    PathResult r = graph != null
        ? HierarchicalPathfinder.findPath(world, graph, start, goal, opts)
        : AStar.findPath(world, start, goal, opts.maxFlatNodes());
    if (r instanceof PathResult.Success s) {
      return new PathResult.Success(PathSimplifier.simplify(world, s.points()), s.nodesExpanded());
    }
    return r;
  }
}
