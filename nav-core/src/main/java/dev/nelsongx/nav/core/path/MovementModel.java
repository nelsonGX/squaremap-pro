package dev.nelsongx.nav.core.path;

import dev.nelsongx.nav.core.WorldView;

/**
 * Single source of truth for which moves between standing positions are legal and what they cost.
 *
 * <p>All searches (fine A*, region graph, simplification checks) must go through this class so that
 * they agree on reachability and cost.
 *
 * <h2>Rules</h2>
 *
 * From feet position {@code (x, y, z)}, for each of the 8 horizontal directions {@code (dx, dz)}:
 *
 * <ul>
 *   <li><b>Cardinal</b> ({@code dx == 0 || dz == 0}): the candidate target heights are tried in the
 *       order {@code y, y+1, y-1, y-2, y-3} (i.e. within {@code [y - MAX_DROP, y + MAX_STEP_UP]},
 *       closest first, preferring up over down at equal distance). The first legal {@code ny} is the
 *       single neighbor in that direction; a candidate failing any check is skipped. A candidate is
 *       legal iff {@code world.walkable(x+dx, ny, z+dz)} and:
 *       <ul>
 *         <li>same level ({@code ny == y}): nothing more;
 *         <li>step up ({@code ny == y+1}): {@code world.passable(x, y+2, z)} (headroom above the
 *             mover for the jump);
 *         <li>drop ({@code ny < y}): {@code world.passable(x+dx, k, z+dz)} for every {@code k} in
 *             {@code [ny+2, y+1]}, i.e. the target column is clear from the mover's head height down
 *             to the landing ({@code walkable} already covers {@code ny} and {@code ny+1}).
 *       </ul>
 *   <li><b>Diagonal</b>: legal only on the same level ({@code ny == y}) and only if both adjacent
 *       cardinal cells {@code walkable(x+dx, y, z)} and {@code walkable(x, y, z+dz)} are walkable
 *       (no corner cutting, no diagonal climbs or drops).
 * </ul>
 *
 * <h2>Cost</h2>
 *
 * Horizontal base {@code 1.0} (cardinal) or {@code √2} (diagonal); plus {@link #STEP_UP_COST} for a
 * step up; plus {@link #DROP_COST_PER_BLOCK}{@code ·|dy|} for a drop. Every move therefore costs at
 * least its horizontal base, which is what makes {@link Heuristics#octile(int, int)} admissible.
 *
 * <p>Stateless; safe for concurrent use.
 */
// THREADING: stateless pure functions; runs on worker threads against snapshot-backed WorldViews.
public final class MovementModel {

  /** Maximum number of blocks a move may climb. */
  public static final int MAX_STEP_UP = 1;
  /** Maximum number of blocks a move may drop. */
  public static final int MAX_DROP = 3;

  /** Horizontal base cost of a cardinal move. */
  public static final double CARDINAL_COST = 1.0;
  /** Horizontal base cost of a diagonal move. */
  public static final double DIAGONAL_COST = Math.sqrt(2.0);
  /** Extra cost of a one-block step up. */
  public static final double STEP_UP_COST = 0.5;
  /** Extra cost per block dropped. */
  public static final double DROP_COST_PER_BLOCK = 0.25;

  /** Direction x offsets: 4 cardinals (E, W, S, N) then 4 diagonals. Order is fixed for determinism. */
  private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
  /** Direction z offsets matching {@link #DX}. */
  private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
  /** Candidate dy values for cardinal moves, in preference order. */
  private static final int[] DY_ORDER = {0, 1, -1, -2, -3};

  private MovementModel() {
  }

  /** Receives neighbors from {@link #forEachNeighbor}. */
  @FunctionalInterface
  public interface NeighborSink {
    /**
     * Called once per legal neighbor.
     *
     * @param x neighbor block x
     * @param y neighbor feet y
     * @param z neighbor block z
     * @param cost cost of the move to this neighbor (&gt; 0)
     */
    void accept(int x, int y, int z, double cost);
  }

  /**
   * Enumerates every legal move from feet position {@code (x, y, z)}: at most one neighbor per
   * horizontal direction, in a fixed deterministic order. Does not allocate.
   *
   * <p>The origin itself is not checked for walkability.
   *
   * @param world the world to query
   * @param x origin block x
   * @param y origin feet y
   * @param z origin block z
   * @param sink receives each neighbor and its move cost
   */
  public static void forEachNeighbor(WorldView world, int x, int y, int z, NeighborSink sink) {
    for (int d = 0; d < 8; d++) {
      int dx = DX[d];
      int dz = DZ[d];
      int nx = x + dx;
      int nz = z + dz;
      if (dx != 0 && dz != 0) {
        if (world.walkable(nx, y, nz) && world.walkable(nx, y, z) && world.walkable(x, y, nz)) {
          sink.accept(nx, y, nz, DIAGONAL_COST);
        }
        continue;
      }
      for (int dy : DY_ORDER) {
        int ny = y + dy;
        if (world.walkable(nx, ny, nz) && clearance(world, x, y, z, nx, ny, nz)) {
          sink.accept(nx, ny, nz, moveCost(true, dy));
          break;
        }
      }
    }
  }

  /**
   * Extra clearance for a cardinal move to a walkable target: headroom above the mover for a step up,
   * or a clear target column from the mover's head down to the landing for a drop.
   */
  private static boolean clearance(WorldView world, int x, int y, int z, int nx, int ny, int nz) {
    if (ny > y) {
      return world.passable(x, y + 2, z);
    }
    for (int k = ny + 2; k <= y + 1; k++) {
      if (!world.passable(nx, k, nz)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Cost of a move with the given shape. Does not check legality.
   *
   * @param cardinal whether the move is cardinal (else diagonal)
   * @param dy vertical change (positive = up)
   * @return move cost
   */
  public static double moveCost(boolean cardinal, int dy) {
    double base = cardinal ? CARDINAL_COST : DIAGONAL_COST;
    if (dy > 0) {
      return base + STEP_UP_COST * dy;
    }
    return base + DROP_COST_PER_BLOCK * -dy;
  }
}
