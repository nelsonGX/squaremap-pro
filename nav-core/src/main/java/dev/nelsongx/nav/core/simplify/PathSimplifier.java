package dev.nelsongx.nav.core.simplify;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reduces a legal block path (as produced by {@link dev.nelsongx.nav.core.path.AStar}) to a short
 * polyline whose every segment is {@link LineOfSight#clear walkable in a straight line}.
 *
 * <h2>Stages</h2>
 *
 * <ol>
 *   <li><b>String pull:</b> from anchor {@code i}, the farthest {@code j} in
 *       {@code (i, i + maxLookahead]} with {@code LineOfSight.clear(path[i], path[j])} is chosen (if
 *       none is clear, {@code i + 1}); {@code j} is emitted and becomes the next anchor.
 *   <li><b>Constrained Douglas-Peucker</b> on the stage-1 points, in 3D block coordinates: the
 *       interior point farthest from segment {@code [s, e]} is kept if its distance exceeds
 *       {@code epsilon}. Otherwise all interior points are dropped only if
 *       {@code LineOfSight.clear(s, e)}; if not, the farthest point is kept anyway and both halves are
 *       processed recursively.
 * </ol>
 *
 * <p>The output is an immutable subsequence of the input with first and last points preserved.
 *
 * <p><b>Pure:</b> no static mutable state, no threads, no I/O.
 */
// THREADING: pure function; runs on worker threads against snapshot-backed WorldViews.
public final class PathSimplifier {

  /** Default Douglas-Peucker tolerance, in blocks. */
  public static final double DEFAULT_EPSILON = 1.0;
  /** Default number of path points scanned ahead of each string-pull anchor. */
  public static final int DEFAULT_MAX_LOOKAHEAD = 256;

  private PathSimplifier() {
  }

  /**
   * Simplifies with {@link #DEFAULT_EPSILON} and {@link #DEFAULT_MAX_LOOKAHEAD}.
   *
   * @param world world the path lies in
   * @param path legal path
   * @return immutable simplified subsequence
   * @throws NullPointerException if an argument or element is null
   */
  public static List<GridPos> simplify(WorldView world, List<GridPos> path) {
    return simplify(world, path, DEFAULT_EPSILON, DEFAULT_MAX_LOOKAHEAD);
  }

  /**
   * Simplifies with {@link #DEFAULT_MAX_LOOKAHEAD}.
   *
   * @param world world the path lies in
   * @param path legal path
   * @param epsilon Douglas-Peucker tolerance in blocks; must be &gt;= 0
   * @return immutable simplified subsequence
   * @throws IllegalArgumentException if {@code epsilon} is negative or NaN
   * @throws NullPointerException if an argument or element is null
   */
  public static List<GridPos> simplify(WorldView world, List<GridPos> path, double epsilon) {
    return simplify(world, path, epsilon, DEFAULT_MAX_LOOKAHEAD);
  }

  /**
   * Simplifies a path.
   *
   * @param world world the path lies in
   * @param path legal path
   * @param epsilon Douglas-Peucker tolerance in blocks; must be &gt;= 0
   * @param maxLookahead how many points past each anchor the string pull scans; must be &gt;= 1
   * @return immutable subsequence of {@code path}, first and last points preserved; paths of size
   *     0, 1 or 2 are returned as copies
   * @throws IllegalArgumentException if {@code epsilon} is negative or NaN, or {@code maxLookahead < 1}
   * @throws NullPointerException if an argument or element is null
   */
  public static List<GridPos> simplify(WorldView world, List<GridPos> path, double epsilon,
      int maxLookahead) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(path, "path");
    if (!(epsilon >= 0)) {
      throw new IllegalArgumentException("epsilon must be >= 0: " + epsilon);
    }
    if (maxLookahead < 1) {
      throw new IllegalArgumentException("maxLookahead must be >= 1: " + maxLookahead);
    }
    List<GridPos> input = List.copyOf(path); // also rejects null elements
    if (input.size() <= 2) {
      return input;
    }
    return douglasPeucker(world, stringPull(world, input, maxLookahead), epsilon);
  }

  private static List<GridPos> stringPull(WorldView world, List<GridPos> path, int maxLookahead) {
    int last = path.size() - 1;
    List<GridPos> out = new ArrayList<>();
    out.add(path.get(0));
    int i = 0;
    while (i < last) {
      GridPos anchor = path.get(i);
      int next = i + 1;
      for (int j = (int) Math.min(last, (long) i + maxLookahead); j > i + 1; j--) {
        if (LineOfSight.clear(world, anchor, path.get(j))) {
          next = j;
          break;
        }
      }
      out.add(path.get(next));
      i = next;
    }
    return out;
  }

  private static List<GridPos> douglasPeucker(WorldView world, List<GridPos> pts, double epsilon) {
    int n = pts.size();
    if (n <= 2) {
      return List.copyOf(pts);
    }
    boolean[] keep = new boolean[n];
    keep[0] = true;
    keep[n - 1] = true;
    ArrayDeque<int[]> stack = new ArrayDeque<>();
    stack.push(new int[] {0, n - 1});
    while (!stack.isEmpty()) {
      int[] range = stack.pop();
      int s = range[0];
      int e = range[1];
      if (e - s < 2) {
        continue;
      }
      int farthest = s + 1;
      double maxDist = -1;
      for (int k = s + 1; k < e; k++) {
        double d = distanceToSegment(pts.get(k), pts.get(s), pts.get(e));
        if (d > maxDist) {
          maxDist = d;
          farthest = k;
        }
      }
      if (maxDist <= epsilon && LineOfSight.clear(world, pts.get(s), pts.get(e))) {
        continue; // drop all interior points
      }
      keep[farthest] = true;
      stack.push(new int[] {farthest, e});
      stack.push(new int[] {s, farthest});
    }
    List<GridPos> out = new ArrayList<>();
    for (int k = 0; k < n; k++) {
      if (keep[k]) {
        out.add(pts.get(k));
      }
    }
    return List.copyOf(out);
  }

  /**
   * Euclidean 3D distance from {@code p} to the closed segment {@code [a, b]}. The perpendicular case
   * uses an exact integer cross product, so collinear points yield exactly {@code 0.0}.
   */
  static double distanceToSegment(GridPos p, GridPos a, GridPos b) {
    double abx = (double) b.x() - a.x();
    double aby = (double) b.y() - a.y();
    double abz = (double) b.z() - a.z();
    double apx = (double) p.x() - a.x();
    double apy = (double) p.y() - a.y();
    double apz = (double) p.z() - a.z();
    double len2 = abx * abx + aby * aby + abz * abz;
    double dot = apx * abx + apy * aby + apz * abz;
    if (len2 == 0 || dot <= 0) {
      return Math.sqrt(apx * apx + apy * apy + apz * apz);
    }
    if (dot >= len2) {
      double bpx = (double) p.x() - b.x();
      double bpy = (double) p.y() - b.y();
      double bpz = (double) p.z() - b.z();
      return Math.sqrt(bpx * bpx + bpy * bpy + bpz * bpz);
    }
    double cx = apy * abz - apz * aby;
    double cy = apz * abx - apx * abz;
    double cz = apx * aby - apy * abx;
    return Math.sqrt((cx * cx + cy * cy + cz * cz) / len2);
  }
}
