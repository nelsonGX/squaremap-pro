package dev.nelsongx.map.core.route;

import dev.nelsongx.map.core.feature.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Time-optimal routing over a {@link Network}: A* on travel time with the admissible, consistent
 * heuristic {@code euclidean distance / fastest allowed speed}.
 *
 * <p>Graph used for one query:
 *
 * <ul>
 *   <li><b>Direct walk</b> start → goal, if no longer than {@code maxDirectWalk}.
 *   <li><b>Access</b> (if ROAD allowed): walk from the start to its projection onto each of the
 *       {@code accessCandidates} nearest road segments within {@code maxAccessWalk}; a projection
 *       strictly inside a segment is a virtual node splitting it. <b>Egress</b> is symmetric at the
 *       goal. Start and goal projections on the same segment are joined along it.
 *   <li><b>Stations</b> (if RAIL allowed): walk from the start to any station within {@code
 *       maxAccessWalk} and from any such station to the goal; walk transfers between stations
 *       within {@code transferWalk}; a walk link between a station and its single nearest road vertex
 *       within {@code stationRoadLink} (if ROAD allowed; vertices only, not projections).
 *   <li>Boarding/alighting at a station is free.
 * </ul>
 *
 * <p>WALK is always allowed. Ties are broken by node id, and node ids follow feature-id order, so
 * results are deterministic.
 *
 * <p>Legs: consecutive walk edges merge; road edges merge while the road name stays the same
 * (across different road features); rail edges merge while on the same railway, with boarding and
 * alighting station names. Zero-length edges never start a leg. Points are node positions rounded
 * to the nearest integer block, with consecutive duplicates removed (a leg shorter than a block
 * keeps two identical points). If {@code from.equals(to)} the result is a single walk leg {@code
 * [from, from]} with distance and duration 0.
 *
 * <p>THREADING: pure function; the network is immutable and each call uses its own state, so any
 * number of threads may route concurrently.
 */
public final class Router {
  private static final byte WALK = 0;
  private static final byte ROAD = 1;
  private static final byte RAIL = 2;
  private static final byte BOARD = 3;
  private static final byte ALIGHT = 4;

  private Router() {}

  public static RouteResult route(Network network, Vertex from, Vertex to, RouterOptions options) {
    return route(network, from, to, options, true);
  }

  /** {@code useHeuristic = false} runs plain Dijkstra (used to test heuristic admissibility). */
  static RouteResult route(
      Network network, Vertex from, Vertex to, RouterOptions options, boolean useHeuristic) {
    Objects.requireNonNull(network, "network");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(options, "options");
    if (from.equals(to)) {
      return new RouteResult.Found(
          List.of(new Leg(Mode.WALK, null, List.of(from, to), 0, 0, null, null)), 0, 0);
    }
    return new Search(network, from, to, options, useHeuristic).run();
  }

  /** Per-query state. Node id layout: road | rail | station | START | GOAL | virtual. */
  private static final class Search {
    private final Network net;
    private final RouterOptions opt;
    private final Speeds speeds;
    private final boolean roadOn;
    private final boolean railOn;
    private final double fromX;
    private final double fromZ;
    private final double toX;
    private final double toZ;
    private final Vertex from;
    private final Vertex to;

    private final int railBase;
    private final int stationBase;
    private final int start;
    private final int goal;
    private final int virtualBase;

    // Access / egress (insertion-ordered for determinism).
    private final Map<Integer, Double> accessRoad = new LinkedHashMap<>();
    private final Map<Integer, Double> egressRoad = new HashMap<>();
    private final Map<Integer, Double> accessStation = new LinkedHashMap<>();
    private final Map<Integer, Double> egressStation = new HashMap<>();
    private final List<double[]> virtualPos = new ArrayList<>();
    private final List<Integer> virtualSeg = new ArrayList<>();
    private final List<Boolean> virtualEgress = new ArrayList<>();
    private final Map<Integer, List<Integer>> egressVirtualsAtRoadNode = new HashMap<>();
    private final Map<Integer, List<Integer>> egressVirtualsOnSeg = new HashMap<>();
    private final Map<Integer, List<Integer>> accessVirtualIds = new HashMap<>();

    private int[] stationNearestRoad; // lazily computed: -2 unknown, -1 none

    private double[] g;
    private int[] prev;
    private byte[] prevKind;
    private int[] prevRef;
    private boolean[] closed;
    private final boolean useHeuristic;
    private final double maxSpeed;
    private final Heap heap = new Heap();

    Search(Network net, Vertex from, Vertex to, RouterOptions opt, boolean useHeuristic) {
      this.net = net;
      this.opt = opt;
      this.speeds = opt.speeds();
      this.roadOn = opt.roadAllowed() && net.roadSegmentCount() > 0;
      this.railOn = opt.railAllowed() && net.stationCount() > 0;
      this.from = from;
      this.to = to;
      this.fromX = from.x();
      this.fromZ = from.z();
      this.toX = to.x();
      this.toZ = to.z();
      this.useHeuristic = useHeuristic;
      double max = speeds.walk();
      if (roadOn) {
        max = Math.max(max, speeds.maxRoad());
      }
      if (railOn) {
        max = Math.max(max, speeds.rail());
      }
      this.maxSpeed = max;
      railBase = net.roadNodeCount();
      stationBase = railBase + net.railX.length;
      start = stationBase + net.stationCount();
      goal = start + 1;
      virtualBase = goal + 1;
    }

    RouteResult run() {
      if (roadOn) {
        addRoadAccess(fromX, fromZ, false);
        addRoadAccess(toX, toZ, true);
      }
      if (railOn) {
        stationNearestRoad = new int[net.stationCount()];
        Arrays.fill(stationNearestRoad, -2);
        addStationAccess(fromX, fromZ, accessStation);
        addStationAccess(toX, toZ, egressStation);
      }
      int total = virtualBase + virtualPos.size();
      g = new double[total];
      Arrays.fill(g, Double.POSITIVE_INFINITY);
      prev = new int[total];
      prevKind = new byte[total];
      prevRef = new int[total];
      closed = new boolean[total];

      g[start] = 0;
      prev[start] = -1;
      heap.push(h(start), start);
      while (!heap.isEmpty()) {
        int u = heap.pop();
        if (closed[u]) {
          continue;
        }
        closed[u] = true;
        if (u == goal) {
          break;
        }
        expand(u);
      }
      if (g[goal] == Double.POSITIVE_INFINITY) {
        return new RouteResult.NoPath(
            "no route within the walking limits using the allowed modes");
      }
      return assemble();
    }

    // ---- access / egress -------------------------------------------------------------------

    private void addRoadAccess(double px, double pz, boolean egress) {
      if (opt.accessCandidates() == 0) {
        return;
      }
      double r = opt.maxAccessWalk();
      BitSet seen = new BitSet(net.roadSegmentCount());
      List<double[]> cands = new ArrayList<>(); // {dist, seg, t, qx, qz}
      net.segmentGrid.query(
          px - r,
          pz - r,
          px + r,
          pz + r,
          s -> {
            if (seen.get(s)) {
              return;
            }
            seen.set(s);
            double ax = net.nodeX[net.segA[s]];
            double az = net.nodeZ[net.segA[s]];
            double dx = net.nodeX[net.segB[s]] - ax;
            double dz = net.nodeZ[net.segB[s]] - az;
            double t = ((px - ax) * dx + (pz - az) * dz) / (dx * dx + dz * dz);
            t = Math.max(0, Math.min(1, t));
            double qx = t == 1 ? ax + dx : ax + dx * t;
            double qz = t == 1 ? az + dz : az + dz * t;
            double d = Math.hypot(px - qx, pz - qz);
            if (d <= r) {
              cands.add(new double[] {d, s, t, qx, qz});
            }
          });
      cands.sort(
          (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
      Map<Integer, Double> roadMap = egress ? egressRoad : accessRoad;
      for (int i = 0; i < Math.min(opt.accessCandidates(), cands.size()); i++) {
        double[] c = cands.get(i);
        int s = (int) c[1];
        double t = c[2];
        if (t == 0 || t == 1) {
          roadMap.putIfAbsent(t == 0 ? net.segA[s] : net.segB[s], c[0]);
          continue;
        }
        int v = virtualBase + virtualPos.size();
        virtualPos.add(new double[] {c[3], c[4]});
        virtualSeg.add(s);
        virtualEgress.add(egress);
        if (egress) {
          egressVirtualsAtRoadNode.computeIfAbsent(net.segA[s], k -> new ArrayList<>()).add(v);
          egressVirtualsAtRoadNode.computeIfAbsent(net.segB[s], k -> new ArrayList<>()).add(v);
          egressVirtualsOnSeg.computeIfAbsent(s, k -> new ArrayList<>()).add(v);
        } else {
          accessVirtualIds.computeIfAbsent(s, k -> new ArrayList<>()).add(v);
        }
      }
    }

    private void addStationAccess(double px, double pz, Map<Integer, Double> into) {
      double r = opt.maxAccessWalk();
      List<double[]> found = new ArrayList<>();
      net.stationGrid.query(
          px - r,
          pz - r,
          px + r,
          pz + r,
          s -> {
            double d = Math.hypot(px - net.stationX[s], pz - net.stationZ[s]);
            if (d <= r) {
              found.add(new double[] {s, d});
            }
          });
      found.sort((a, b) -> Double.compare(a[0], b[0]));
      for (double[] f : found) {
        into.put((int) f[0], f[1]);
      }
    }

    private int nearestRoadNode(int station) {
      if (stationNearestRoad[station] != -2) {
        return stationNearestRoad[station];
      }
      double sx = net.stationX[station];
      double sz = net.stationZ[station];
      double r = opt.stationRoadLink();
      int[] best = {-1};
      double[] bestD = {Double.POSITIVE_INFINITY};
      net.roadNodeGrid.query(
          sx - r,
          sz - r,
          sx + r,
          sz + r,
          n -> {
            double d = Math.hypot(sx - net.nodeX[n], sz - net.nodeZ[n]);
            if (d <= r && (d < bestD[0] || (d == bestD[0] && n < best[0]))) {
              bestD[0] = d;
              best[0] = n;
            }
          });
      stationNearestRoad[station] = best[0];
      return best[0];
    }

    // ---- search ----------------------------------------------------------------------------

    private double x(int n) {
      if (n < railBase) {
        return net.nodeX[n];
      } else if (n < stationBase) {
        return net.railX[n - railBase];
      } else if (n < start) {
        return net.stationX[n - stationBase];
      } else if (n == start) {
        return fromX;
      } else if (n == goal) {
        return toX;
      }
      return virtualPos.get(n - virtualBase)[0];
    }

    private double z(int n) {
      if (n < railBase) {
        return net.nodeZ[n];
      } else if (n < stationBase) {
        return net.railZ[n - railBase];
      } else if (n < start) {
        return net.stationZ[n - stationBase];
      } else if (n == start) {
        return fromZ;
      } else if (n == goal) {
        return toZ;
      }
      return virtualPos.get(n - virtualBase)[1];
    }

    private double dist(int a, int b) {
      return Math.hypot(x(a) - x(b), z(a) - z(b));
    }

    private double h(int n) {
      return useHeuristic ? Math.hypot(x(n) - toX, z(n) - toZ) / maxSpeed : 0;
    }

    private void relax(int u, int v, double cost, byte kind, int ref) {
      if (closed[v]) {
        return;
      }
      double ng = g[u] + cost;
      if (ng < g[v]) {
        g[v] = ng;
        prev[v] = u;
        prevKind[v] = kind;
        prevRef[v] = ref;
        heap.push(ng + h(v), v);
      }
    }

    private void walk(int u, int v, double distance) {
      relax(u, v, distance / speeds.walk(), WALK, -1);
    }

    private void road(int u, int v, int seg) {
      relax(u, v, dist(u, v) / speeds.road(net.segClass[seg]), ROAD, seg);
    }

    private void expand(int u) {
      if (u == start) {
        double direct = Math.hypot(toX - fromX, toZ - fromZ);
        if (direct <= opt.maxDirectWalk()) {
          walk(u, goal, direct);
        }
        accessRoad.forEach((n, d) -> walk(u, n, d));
        for (int i = 0; i < virtualPos.size(); i++) {
          if (!virtualEgress.get(i)) {
            int v = virtualBase + i;
            walk(u, v, dist(u, v));
          }
        }
        accessStation.forEach((s, d) -> walk(u, stationBase + s, d));
      } else if (u < railBase) {
        for (int seg : net.nodeSegments[u]) {
          road(u, net.segA[seg] == u ? net.segB[seg] : net.segA[seg], seg);
        }
        List<Integer> egressVirtuals = egressVirtualsAtRoadNode.get(u);
        if (egressVirtuals != null) {
          for (int v : egressVirtuals) {
            road(u, v, virtualSeg.get(v - virtualBase));
          }
        }
        Double egress = egressRoad.get(u);
        if (egress != null) {
          walk(u, goal, egress);
        }
        if (railOn) {
          double r = opt.stationRoadLink();
          double ux = net.nodeX[u];
          double uz = net.nodeZ[u];
          net.stationGrid.query(
              ux - r,
              uz - r,
              ux + r,
              uz + r,
              s -> {
                if (nearestRoadNode(s) == u) {
                  walk(u, stationBase + s, dist(u, stationBase + s));
                }
              });
        }
      } else if (u < stationBase) {
        int k = u - railBase;
        int railway = net.railOf[k];
        if (k > net.railStart[railway]) {
          relax(u, u - 1, dist(u, u - 1) / speeds.rail(), RAIL, railway);
        }
        if (k + 1 < net.railStart[railway + 1]) {
          relax(u, u + 1, dist(u, u + 1) / speeds.rail(), RAIL, railway);
        }
        for (int s : net.railNodeStations[k]) {
          relax(u, stationBase + s, 0, ALIGHT, s);
        }
      } else if (u < start) {
        int s = u - stationBase;
        for (int rn : net.stationRailNodes[s]) {
          relax(u, railBase + rn, 0, BOARD, s);
        }
        double r = opt.transferWalk();
        double sx = net.stationX[s];
        double sz = net.stationZ[s];
        net.stationGrid.query(
            sx - r,
            sz - r,
            sx + r,
            sz + r,
            other -> {
              if (other != s) {
                double d = dist(u, stationBase + other);
                if (d <= r) {
                  walk(u, stationBase + other, d);
                }
              }
            });
        if (roadOn) {
          int n = nearestRoadNode(s);
          if (n >= 0) {
            walk(u, n, dist(u, n));
          }
        }
        Double egress = egressStation.get(s);
        if (egress != null) {
          walk(u, goal, egress);
        }
      } else if (u >= virtualBase) {
        int i = u - virtualBase;
        if (virtualEgress.get(i)) {
          walk(u, goal, dist(u, goal));
        } else {
          int seg = virtualSeg.get(i);
          road(u, net.segA[seg], seg);
          road(u, net.segB[seg], seg);
          List<Integer> sameSeg = egressVirtualsOnSeg.get(seg);
          if (sameSeg != null) {
            for (int v : sameSeg) {
              road(u, v, seg);
            }
          }
        }
      }
    }

    // ---- legs ------------------------------------------------------------------------------

    private static final class LegBuilder {
      final Mode mode;
      final String name;
      final int railway;
      final List<Vertex> points = new ArrayList<>();
      double distance;
      double duration;
      final String fromStation;
      String toStation;
      boolean open = true;

      LegBuilder(Mode mode, String name, int railway, String fromStation) {
        this.mode = mode;
        this.name = name;
        this.railway = railway;
        this.fromStation = fromStation;
      }

      void add(Vertex p) {
        if (points.isEmpty() || !points.get(points.size() - 1).equals(p)) {
          points.add(p);
        }
      }

      Leg build() {
        List<Vertex> pts = new ArrayList<>(points);
        if (pts.size() == 1) {
          pts.add(pts.get(0));
        }
        return new Leg(mode, name, pts, distance, duration, fromStation, toStation);
      }
    }

    private Vertex rounded(int n) {
      if (n == start) {
        return from;
      } else if (n == goal) {
        return to;
      }
      return new Vertex((int) Math.round(x(n)), (int) Math.round(z(n)));
    }

    private RouteResult assemble() {
      List<Integer> path = new ArrayList<>();
      for (int n = goal; n != -1; n = prev[n]) {
        path.add(n);
      }
      java.util.Collections.reverse(path);

      List<LegBuilder> legs = new ArrayList<>();
      LegBuilder cur = null;
      String boardedAt = null;
      for (int i = 1; i < path.size(); i++) {
        int u = path.get(i - 1);
        int v = path.get(i);
        byte kind = prevKind[v];
        int ref = prevRef[v];
        if (kind == BOARD) {
          boardedAt = net.stationName[ref];
          continue;
        }
        if (kind == ALIGHT) {
          if (cur != null && cur.mode == Mode.RAIL && cur.open) {
            cur.toStation = net.stationName[ref];
            cur.open = false;
          }
          continue;
        }
        double d = dist(u, v);
        if (d == 0) {
          continue;
        }
        Mode mode;
        String name;
        double speed;
        switch (kind) {
          case ROAD -> {
            mode = Mode.ROAD;
            name = net.segName[ref];
            speed = speeds.road(net.segClass[ref]);
          }
          case RAIL -> {
            mode = Mode.RAIL;
            name = net.railwayName[ref];
            speed = speeds.rail();
          }
          default -> {
            mode = Mode.WALK;
            name = null;
            speed = speeds.walk();
          }
        }
        boolean extend =
            cur != null
                && cur.mode == mode
                && switch (mode) {
                  case WALK -> true;
                  case ROAD -> Objects.equals(cur.name, name);
                  // Re-boarding the same railway with nothing in between continues the ride.
                  case RAIL -> cur.railway == ref;
                };
        if (extend) {
          if (mode == Mode.RAIL && !cur.open) {
            cur.open = true;
            cur.toStation = null;
          }
        } else {
          cur =
              new LegBuilder(
                  mode, name, mode == Mode.RAIL ? ref : -1, mode == Mode.RAIL ? boardedAt : null);
          cur.add(rounded(u));
          legs.add(cur);
        }
        cur.add(rounded(v));
        cur.distance += d;
        cur.duration += d / speed;
      }

      List<Leg> out = new ArrayList<>(legs.size());
      double distance = 0;
      double duration = 0;
      for (LegBuilder b : legs) {
        Leg leg = b.build();
        out.add(leg);
        distance += leg.distance();
        duration += leg.duration();
      }
      return new RouteResult.Found(out, distance, duration);
    }
  }

  /** Binary min-heap of (key, node); ties broken by smaller node id. */
  private static final class Heap {
    private double[] keys = new double[64];
    private int[] nodes = new int[64];
    private int size;

    boolean isEmpty() {
      return size == 0;
    }

    void push(double key, int node) {
      if (size == keys.length) {
        keys = Arrays.copyOf(keys, size * 2);
        nodes = Arrays.copyOf(nodes, size * 2);
      }
      int i = size++;
      while (i > 0) {
        int parent = (i - 1) >>> 1;
        if (!less(key, node, keys[parent], nodes[parent])) {
          break;
        }
        keys[i] = keys[parent];
        nodes[i] = nodes[parent];
        i = parent;
      }
      keys[i] = key;
      nodes[i] = node;
    }

    int pop() {
      int top = nodes[0];
      size--;
      if (size > 0) {
        double key = keys[size];
        int node = nodes[size];
        int i = 0;
        while (true) {
          int child = 2 * i + 1;
          if (child >= size) {
            break;
          }
          if (child + 1 < size
              && less(keys[child + 1], nodes[child + 1], keys[child], nodes[child])) {
            child++;
          }
          if (!less(keys[child], nodes[child], key, node)) {
            break;
          }
          keys[i] = keys[child];
          nodes[i] = nodes[child];
          i = child;
        }
        keys[i] = key;
        nodes[i] = node;
      }
      return top;
    }

    private static boolean less(double k1, int n1, double k2, int n2) {
      return k1 < k2 || (k1 == k2 && n1 < n2);
    }
  }
}
