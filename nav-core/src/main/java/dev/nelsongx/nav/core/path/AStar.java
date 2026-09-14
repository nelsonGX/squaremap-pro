package dev.nelsongx.nav.core.path;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.PathResult.FailureReason;
import dev.nelsongx.nav.core.WorldView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Block-level A* search over a {@link WorldView} using {@link MovementModel} moves and the
 * {@link Heuristics#octile(int, int)} heuristic.
 *
 * <p><b>Pure:</b> no static mutable state, no threads, no I/O. All search state lives in a per-call
 * object, so concurrent calls are safe as long as the {@code WorldView} honours its concurrent-read
 * contract.
 *
 * <h2>Position packing</h2>
 *
 * Positions are packed into a {@code long} key (two's complement fields, sign-extended on unpack):
 *
 * <pre>
 *   bit 63 ........ 38 | 37 ........ 12 | 11 ..... 0
 *        x (26 bits)   |   z (26 bits)  | y (12 bits)
 * </pre>
 *
 * Supported ranges: x, z in {@code [-33_554_432, 33_554_431]} (covers ±33,554,431) and y in
 * {@code [-2048, 2047]} (covers ±2047). Endpoints outside this range are rejected as
 * {@code INVALID_ENDPOINT}; neighbors outside it are ignored.
 *
 * <h2>Determinism</h2>
 *
 * The open set is a binary heap ordered by lower {@code f}, then lower {@code h}, then insertion
 * order; neighbor enumeration order is fixed by {@link MovementModel}. Identical inputs yield identical
 * results.
 */
// THREADING: pure function; runs on worker threads against snapshot-backed WorldViews. Never call on
// the server thread for large searches.
public final class AStar {

  static final int XZ_BITS = 26;
  static final int Y_BITS = 12;
  static final int XZ_MIN = -(1 << (XZ_BITS - 1));
  static final int XZ_MAX = (1 << (XZ_BITS - 1)) - 1;
  static final int Y_MIN = -(1 << (Y_BITS - 1));
  static final int Y_MAX = (1 << (Y_BITS - 1)) - 1;
  private static final long XZ_MASK = (1L << XZ_BITS) - 1;
  private static final long Y_MASK = (1L << Y_BITS) - 1;

  private AStar() {
  }

  /**
   * Finds a cheapest path from {@code start} to {@code goal}.
   *
   * <p>A node counts as expanded when it is popped from the open set and closed (the goal included).
   * The search fails with {@code CAP_EXCEEDED} when closing another node would exceed
   * {@code maxNodes}.
   *
   * @param world world to search
   * @param start start feet position
   * @param goal goal feet position
   * @param maxNodes maximum number of nodes to expand; must be &gt;= 1
   * @return {@code Success} with every node from start to goal inclusive (unsimplified), or a
   *     {@code Failure} ({@code INVALID_ENDPOINT} with 0 nodes, {@code CAP_EXCEEDED}, {@code NO_PATH})
   * @throws IllegalArgumentException if {@code maxNodes < 1}
   * @throws NullPointerException if any argument is null
   */
  public static PathResult findPath(WorldView world, GridPos start, GridPos goal, int maxNodes) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(goal, "goal");
    if (maxNodes < 1) {
      throw new IllegalArgumentException("maxNodes must be >= 1: " + maxNodes);
    }
    if (!inRange(start.x(), start.y(), start.z())) {
      return invalid("start " + start + " is outside the supported coordinate range");
    }
    if (!inRange(goal.x(), goal.y(), goal.z())) {
      return invalid("goal " + goal + " is outside the supported coordinate range");
    }
    if (!world.walkable(start.x(), start.y(), start.z())) {
      return invalid("start " + start + " is not walkable");
    }
    if (!world.walkable(goal.x(), goal.y(), goal.z())) {
      return invalid("goal " + goal + " is not walkable");
    }
    if (start.equals(goal)) {
      return new PathResult.Success(List.of(start), 0);
    }
    return new Search(world, start, goal, maxNodes).run();
  }

  /**
   * Total {@link MovementModel} cost along {@code points}.
   *
   * @param world world used to check move legality
   * @param points consecutive path points; fewer than two points cost {@code 0.0}
   * @return summed move cost
   * @throws IllegalArgumentException if any consecutive pair is not a legal move
   * @throws NullPointerException if an argument or element is null
   */
  public static double pathCost(WorldView world, List<GridPos> points) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(points, "points");
    MoveFinder finder = new MoveFinder();
    double total = 0.0;
    for (int i = 1; i < points.size(); i++) {
      GridPos a = Objects.requireNonNull(points.get(i - 1), "point");
      GridPos b = Objects.requireNonNull(points.get(i), "point");
      finder.target = b;
      finder.cost = Double.NaN;
      MovementModel.forEachNeighbor(world, a.x(), a.y(), a.z(), finder);
      if (Double.isNaN(finder.cost)) {
        throw new IllegalArgumentException(
            "illegal move at index " + (i - 1) + ": " + a + " -> " + b);
      }
      total += finder.cost;
    }
    return total;
  }

  private static final class MoveFinder implements MovementModel.NeighborSink {
    GridPos target;
    double cost;

    @Override
    public void accept(int x, int y, int z, double moveCost) {
      if (x == target.x() && y == target.y() && z == target.z()) {
        cost = moveCost;
      }
    }
  }

  private static PathResult invalid(String detail) {
    return new PathResult.Failure(FailureReason.INVALID_ENDPOINT, 0, detail);
  }

  static boolean inRange(int x, int y, int z) {
    return x >= XZ_MIN && x <= XZ_MAX && z >= XZ_MIN && z <= XZ_MAX && y >= Y_MIN && y <= Y_MAX;
  }

  static long pack(int x, int y, int z) {
    return ((x & XZ_MASK) << (Y_BITS + XZ_BITS)) | ((z & XZ_MASK) << Y_BITS) | (y & Y_MASK);
  }

  static int unpackX(long key) {
    return (int) (key >> (Y_BITS + XZ_BITS));
  }

  static int unpackZ(long key) {
    return (int) ((key << XZ_BITS) >> (XZ_BITS + Y_BITS));
  }

  static int unpackY(long key) {
    return (int) ((key << (64 - Y_BITS)) >> (64 - Y_BITS));
  }

  /** Per-call search state. Nodes are stored in parallel arrays indexed by node id. */
  private static final class Search implements MovementModel.NeighborSink {
    private final WorldView world;
    private final GridPos start;
    private final int goalX;
    private final int goalZ;
    private final long goalKey;
    private final int maxNodes;

    // Node storage.
    private long[] nodeKey = new long[256];
    private double[] nodeG = new double[256];
    private int[] nodeParent = new int[256];
    private boolean[] nodeClosed = new boolean[256];
    private int nodeCount;
    private final LongIntMap index = new LongIntMap(512);

    // Open set: binary heap of (f, h, seq, node) in parallel arrays.
    private double[] heapF = new double[256];
    private double[] heapH = new double[256];
    private long[] heapSeq = new long[256];
    private int[] heapNode = new int[256];
    private int heapSize;
    private long nextSeq;

    private int current;

    Search(WorldView world, GridPos start, GridPos goal, int maxNodes) {
      this.world = world;
      this.start = start;
      this.goalX = goal.x();
      this.goalZ = goal.z();
      this.goalKey = pack(goal.x(), goal.y(), goal.z());
      this.maxNodes = maxNodes;
    }

    PathResult run() {
      int s = addNode(pack(start.x(), start.y(), start.z()), 0.0, -1);
      double h0 = Heuristics.octile(goalX - start.x(), goalZ - start.z());
      push(h0, h0, s);
      int expanded = 0;
      while (heapSize > 0) {
        int n = pop();
        if (nodeClosed[n]) {
          continue; // stale duplicate; consistent heuristic => first pop was optimal
        }
        if (expanded == maxNodes) {
          return new PathResult.Failure(FailureReason.CAP_EXCEEDED, expanded,
              "node cap of " + maxNodes + " reached");
        }
        nodeClosed[n] = true;
        expanded++;
        long key = nodeKey[n];
        if (key == goalKey) {
          return new PathResult.Success(reconstruct(n), expanded);
        }
        current = n;
        MovementModel.forEachNeighbor(world, unpackX(key), unpackY(key), unpackZ(key), this);
      }
      return new PathResult.Failure(FailureReason.NO_PATH, expanded,
          "goal unreachable after expanding " + expanded + " nodes");
    }

    @Override
    public void accept(int x, int y, int z, double cost) {
      if (!inRange(x, y, z)) {
        return;
      }
      long key = pack(x, y, z);
      double g = nodeG[current] + cost;
      int n = index.get(key);
      if (n < 0) {
        n = addNode(key, g, current);
      } else if (nodeClosed[n] || g >= nodeG[n]) {
        return;
      } else {
        nodeG[n] = g;
        nodeParent[n] = current;
      }
      double h = Heuristics.octile(goalX - x, goalZ - z);
      push(g + h, h, n);
    }

    private int addNode(long key, double g, int parent) {
      if (nodeCount == nodeKey.length) {
        int cap = nodeCount * 2;
        nodeKey = Arrays.copyOf(nodeKey, cap);
        nodeG = Arrays.copyOf(nodeG, cap);
        nodeParent = Arrays.copyOf(nodeParent, cap);
        nodeClosed = Arrays.copyOf(nodeClosed, cap);
      }
      int n = nodeCount++;
      nodeKey[n] = key;
      nodeG[n] = g;
      nodeParent[n] = parent;
      index.put(key, n);
      return n;
    }

    private List<GridPos> reconstruct(int n) {
      ArrayList<GridPos> points = new ArrayList<>();
      for (int i = n; i >= 0; i = nodeParent[i]) {
        long k = nodeKey[i];
        points.add(new GridPos(unpackX(k), unpackY(k), unpackZ(k)));
      }
      Collections.reverse(points);
      return points;
    }

    // ---- heap ----

    private boolean less(int i, int j) {
      if (heapF[i] != heapF[j]) {
        return heapF[i] < heapF[j];
      }
      if (heapH[i] != heapH[j]) {
        return heapH[i] < heapH[j];
      }
      return heapSeq[i] < heapSeq[j];
    }

    private void swap(int i, int j) {
      double f = heapF[i];
      heapF[i] = heapF[j];
      heapF[j] = f;
      double h = heapH[i];
      heapH[i] = heapH[j];
      heapH[j] = h;
      long s = heapSeq[i];
      heapSeq[i] = heapSeq[j];
      heapSeq[j] = s;
      int n = heapNode[i];
      heapNode[i] = heapNode[j];
      heapNode[j] = n;
    }

    private void push(double f, double h, int node) {
      if (heapSize == heapF.length) {
        int cap = heapSize * 2;
        heapF = Arrays.copyOf(heapF, cap);
        heapH = Arrays.copyOf(heapH, cap);
        heapSeq = Arrays.copyOf(heapSeq, cap);
        heapNode = Arrays.copyOf(heapNode, cap);
      }
      int i = heapSize++;
      heapF[i] = f;
      heapH[i] = h;
      heapSeq[i] = nextSeq++;
      heapNode[i] = node;
      while (i > 0) {
        int p = (i - 1) >>> 1;
        if (!less(i, p)) {
          break;
        }
        swap(i, p);
        i = p;
      }
    }

    private int pop() {
      int top = heapNode[0];
      int last = --heapSize;
      if (last > 0) {
        swap(0, last);
        int i = 0;
        while (true) {
          int l = 2 * i + 1;
          if (l >= last) {
            break;
          }
          int r = l + 1;
          int m = (r < last && less(r, l)) ? r : l;
          if (!less(m, i)) {
            break;
          }
          swap(i, m);
          i = m;
        }
      }
      return top;
    }
  }

  /** Minimal open-addressing {@code long -> int} map (non-negative values only). */
  private static final class LongIntMap {
    private long[] keys;
    private int[] values; // stored as value + 1; 0 = empty slot
    private int size;

    LongIntMap(int capacity) {
      int cap = Integer.highestOneBit(Math.max(16, capacity) - 1) << 1;
      keys = new long[cap];
      values = new int[cap];
    }

    private static int hash(long k) {
      k ^= k >>> 33;
      k *= 0xff51afd7ed558ccdL;
      k ^= k >>> 33;
      k *= 0xc4ceb9fe1a85ec53L;
      k ^= k >>> 33;
      return (int) k;
    }

    int get(long key) {
      int mask = keys.length - 1;
      for (int i = hash(key) & mask; values[i] != 0; i = (i + 1) & mask) {
        if (keys[i] == key) {
          return values[i] - 1;
        }
      }
      return -1;
    }

    void put(long key, int value) {
      if ((size + 1) * 2 > keys.length) {
        rehash();
      }
      int mask = keys.length - 1;
      int i = hash(key) & mask;
      while (values[i] != 0) {
        if (keys[i] == key) {
          values[i] = value + 1;
          return;
        }
        i = (i + 1) & mask;
      }
      keys[i] = key;
      values[i] = value + 1;
      size++;
    }

    private void rehash() {
      long[] oldKeys = keys;
      int[] oldValues = values;
      keys = new long[oldKeys.length * 2];
      values = new int[oldValues.length * 2];
      size = 0;
      for (int i = 0; i < oldKeys.length; i++) {
        if (oldValues[i] != 0) {
          put(oldKeys[i], oldValues[i] - 1);
        }
      }
    }
  }
}
