package dev.nelsongx.nav.core.hierarchy;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.PathResult.FailureReason;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.path.AStar;
import dev.nelsongx.nav.core.path.Heuristics;
import dev.nelsongx.nav.core.path.MovementModel;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionId;
import dev.nelsongx.nav.core.region.RegionLink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * Two-level path search: a coarse A* over {@link RegionGraph} portals picks a region route, then
 * block-level {@link AStar} searches connect consecutive portals.
 *
 * <h2>Flat fallbacks</h2>
 *
 * Plain {@code AStar.findPath(world, start, goal, maxFlatNodes)} is used instead of the coarse layer
 * when:
 *
 * <ul>
 *   <li>the octile x/z distance between start and goal is at most {@code nearDistance} (short queries are
 *       cheap flat, and the coarse route's lower-bound costs are least accurate over short distances);
 *   <li>start or goal cannot be {@linkplain RegionGraph#locate located} in the graph (its sector is not
 *       built, it is outside the graph's Y range, or the graph is stale for that sector);
 *   <li>start and goal lie in the same region (regions are strongly connected, so the coarse layer adds
 *       nothing);
 *   <li>the coarse route turns out to be stale while being refined: a segment search returns
 *       {@code NO_PATH} or {@code INVALID_ENDPOINT}, or a portal move is no longer legal. This fallback
 *       runs at most once and its node count is added to the total.
 * </ul>
 *
 * <h2>Coarse search</h2>
 *
 * States are pairs (region, position): the start state is (start region, start), and taking link
 * {@code L} from a state at {@code p} in {@code L.from} costs {@code octile(p, L.fromPos) + L.cost} and
 * lands in state ({@code L.to}, {@code L.toPos}). States with equal (region, position) are merged. A
 * state in the goal region may move to the terminal goal state for {@code octile(p, goal)}. Edge costs
 * are lower bounds of the real block cost (octile is admissible inside a region), and the heuristic
 * {@code octile(p, goal)} is consistent with them, so the first time the terminal is closed its route is
 * optimal with respect to these bounds. Open-set ties break on lower {@code f}, then lower {@code h},
 * then insertion order; links are enumerated in {@link RegionLink#POS_ORDER}, so results are
 * deterministic.
 *
 * <p>Because every legal block move is either inside one region or a link, a goal reachable through
 * built sectors is always reachable in the coarse graph. Exhausting the coarse open set therefore proves
 * the goal unreachable (within built sectors) and yields {@code NO_PATH} after visiting only portal
 * states, instead of flooding every reachable block as flat A* would. Closing more than
 * {@code maxCoarseNodes} states yields {@code CAP_EXCEEDED}.
 *
 * <h2>Fine search</h2>
 *
 * The coarse route start → (fromPos₁ → toPos₁) → … → goal is refined by {@code AStar.findPath} from each
 * segment's entry (start or previous toPos) to its exit (next fromPos or goal) with
 * {@code maxSegmentNodes}; each portal move fromPos → toPos is appended as a single legal move. A segment
 * {@code CAP_EXCEEDED} yields {@code CAP_EXCEEDED}. The result points are unsimplified and form a legal
 * {@link MovementModel} path from start to goal; {@code nodesExpanded} sums the coarse search, every
 * segment search and the fallback, if any.
 *
 * <p><b>Pure:</b> no static mutable state, no threads, no I/O; concurrent calls are safe as long as the
 * {@code WorldView} honours its concurrent-read contract.
 */
// THREADING: pure function; runs on worker threads against snapshot-backed WorldViews and an immutable
// RegionGraph. Never call on the server thread.
public final class HierarchicalPathfinder {

  private HierarchicalPathfinder() {
  }

  /**
   * Finds a path using the region graph (see class Javadoc).
   *
   * @param world world to search
   * @param graph region graph, ideally built from the same world state
   * @param start start feet position
   * @param goal goal feet position
   * @param opts effort limits
   * @return {@code Success} with unsimplified points from start to goal inclusive, or a {@code Failure}
   *     ({@code INVALID_ENDPOINT} with 0 nodes if an endpoint is not walkable, {@code NO_PATH},
   *     {@code CAP_EXCEEDED})
   * @throws NullPointerException if any argument is null
   */
  public static PathResult findPath(WorldView world, RegionGraph graph, GridPos start, GridPos goal,
      HierarchicalOptions opts) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(opts, "opts");
    if (!world.walkable(start.x(), start.y(), start.z())) {
      return new PathResult.Failure(FailureReason.INVALID_ENDPOINT, 0,
          "start " + start + " is not walkable");
    }
    if (!world.walkable(goal.x(), goal.y(), goal.z())) {
      return new PathResult.Failure(FailureReason.INVALID_ENDPOINT, 0,
          "goal " + goal + " is not walkable");
    }
    if (octile(start, goal) <= opts.nearDistance()) {
      return flat(world, start, goal, opts);
    }
    Optional<RegionId> startRegion = graph.locate(world, start);
    if (startRegion.isEmpty()) {
      return flat(world, start, goal, opts);
    }
    Optional<RegionId> goalRegion = graph.locate(world, goal);
    if (goalRegion.isEmpty() || goalRegion.get().equals(startRegion.get())) {
      return flat(world, start, goal, opts);
    }

    CoarseSearch coarse = new CoarseSearch(graph, startRegion.get(), start, goalRegion.get(), goal,
        opts.maxCoarseNodes());
    List<RegionLink> route = coarse.run();
    int total = coarse.expanded;
    if (route == null) {
      return new PathResult.Failure(coarse.failure, total, coarse.failureDetail);
    }

    List<GridPos> points = new ArrayList<>();
    GridPos cur = start;
    for (int i = 0; i <= route.size(); i++) {
      RegionLink link = i < route.size() ? route.get(i) : null;
      GridPos exit = link == null ? goal : link.fromPos();
      PathResult seg = AStar.findPath(world, cur, exit, opts.maxSegmentNodes());
      total = saturatedAdd(total, seg.nodesExpanded());
      if (seg instanceof PathResult.Failure f) {
        if (f.reason() == FailureReason.CAP_EXCEEDED) {
          return new PathResult.Failure(FailureReason.CAP_EXCEEDED, total,
              "segment " + cur + " -> " + exit + ": " + f.detail());
        }
        return staleFallback(world, start, goal, opts, total);
      }
      List<GridPos> segPoints = ((PathResult.Success) seg).points();
      appendJoined(points, segPoints);
      if (link != null) {
        if (!isLegalMove(world, link.fromPos(), link.toPos())) {
          return staleFallback(world, start, goal, opts, total);
        }
        points.add(link.toPos());
        cur = link.toPos();
      }
    }
    return new PathResult.Success(points, total);
  }

  private static PathResult flat(WorldView world, GridPos start, GridPos goal,
      HierarchicalOptions opts) {
    return AStar.findPath(world, start, goal, opts.maxFlatNodes());
  }

  private static PathResult staleFallback(WorldView world, GridPos start, GridPos goal,
      HierarchicalOptions opts, int spent) {
    PathResult r = flat(world, start, goal, opts);
    int total = saturatedAdd(spent, r.nodesExpanded());
    if (r instanceof PathResult.Success s) {
      return new PathResult.Success(s.points(), total);
    }
    PathResult.Failure f = (PathResult.Failure) r;
    return new PathResult.Failure(f.reason(), total,
        f.detail() + " (flat fallback after stale region route)");
  }

  /** Appends {@code seg}, dropping its first point when it repeats the last appended point. */
  private static void appendJoined(List<GridPos> out, List<GridPos> seg) {
    int from = !out.isEmpty() && out.get(out.size() - 1).equals(seg.get(0)) ? 1 : 0;
    out.addAll(seg.subList(from, seg.size()));
  }

  private static boolean isLegalMove(WorldView world, GridPos a, GridPos b) {
    boolean[] found = new boolean[1];
    MovementModel.forEachNeighbor(world, a.x(), a.y(), a.z(), (x, y, z, cost) -> {
      if (x == b.x() && y == b.y() && z == b.z()) {
        found[0] = true;
      }
    });
    return found[0];
  }

  static double octile(GridPos a, GridPos b) {
    return Heuristics.octile(a.x() - b.x(), a.z() - b.z());
  }

  private static int saturatedAdd(int a, int b) {
    long s = (long) a + b;
    return s > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
  }

  /** Per-call coarse A* state. */
  private static final class CoarseSearch {
    /** Coarse state key; {@code region == null} marks the terminal goal state. */
    private record State(RegionId region, GridPos pos) {
    }

    private record Entry(double f, double h, long seq, int node) {
    }

    private static final Comparator<Entry> ORDER = Comparator
        .comparingDouble(Entry::f)
        .thenComparingDouble(Entry::h)
        .thenComparingLong(Entry::seq);

    private final RegionGraph graph;
    private final RegionId startRegion;
    private final GridPos start;
    private final RegionId goalRegion;
    private final GridPos goal;
    private final int maxNodes;

    private final List<State> states = new ArrayList<>();
    private final List<RegionLink> via = new ArrayList<>();
    private double[] g = new double[64];
    private int[] parent = new int[64];
    private boolean[] closed = new boolean[64];
    private final Map<State, Integer> index = new HashMap<>();
    private final PriorityQueue<Entry> open = new PriorityQueue<>(ORDER);
    private long nextSeq;

    int expanded;
    FailureReason failure;
    String failureDetail;

    CoarseSearch(RegionGraph graph, RegionId startRegion, GridPos start, RegionId goalRegion,
        GridPos goal, int maxNodes) {
      this.graph = graph;
      this.startRegion = startRegion;
      this.start = start;
      this.goalRegion = goalRegion;
      this.goal = goal;
      this.maxNodes = maxNodes;
    }

    /** Returns the links of the route in order, or null on failure (sets {@link #failure}). */
    List<RegionLink> run() {
      relax(new State(startRegion, start), 0.0, -1, null);
      while (!open.isEmpty()) {
        int n = open.poll().node();
        if (closed[n]) {
          continue;
        }
        if (expanded == maxNodes) {
          failure = FailureReason.CAP_EXCEEDED;
          failureDetail = "coarse node cap of " + maxNodes + " reached";
          return null;
        }
        closed[n] = true;
        expanded++;
        State s = states.get(n);
        if (s.region() == null) {
          return reconstruct(n);
        }
        double gn = g[n];
        for (RegionLink link : graph.linksFrom(s.region())) {
          relax(new State(link.to(), link.toPos()),
              gn + octile(s.pos(), link.fromPos()) + link.cost(), n, link);
        }
        if (s.region().equals(goalRegion)) {
          relax(new State(null, goal), gn + octile(s.pos(), goal), n, null);
        }
      }
      failure = FailureReason.NO_PATH;
      failureDetail = "goal region unreachable after expanding " + expanded + " coarse states";
      return null;
    }

    private void relax(State s, double gs, int from, RegionLink link) {
      Integer existing = index.get(s);
      int n;
      if (existing == null) {
        n = states.size();
        if (n == g.length) {
          g = Arrays.copyOf(g, n * 2);
          parent = Arrays.copyOf(parent, n * 2);
          closed = Arrays.copyOf(closed, n * 2);
        }
        states.add(s);
        via.add(link);
        index.put(s, n);
      } else {
        n = existing;
        if (closed[n] || gs >= g[n]) {
          return;
        }
        via.set(n, link);
      }
      g[n] = gs;
      parent[n] = from;
      double h = octile(s.pos(), goal);
      open.add(new Entry(gs + h, h, nextSeq++, n));
    }

    private List<RegionLink> reconstruct(int n) {
      List<RegionLink> links = new ArrayList<>();
      for (int i = n; i >= 0; i = parent[i]) {
        RegionLink l = via.get(i);
        if (l != null) {
          links.add(l);
        }
      }
      Collections.reverse(links);
      return links;
    }
  }
}
