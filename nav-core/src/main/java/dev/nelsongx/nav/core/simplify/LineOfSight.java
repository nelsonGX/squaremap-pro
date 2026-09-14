package dev.nelsongx.nav.core.simplify;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.path.MovementModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Walkable line-of-sight test between two standing positions, using the same move rules as A*
 * ({@link MovementModel}).
 *
 * <h2>Algorithm</h2>
 *
 * The x/z cells touched by the segment between the block centres of {@code a} and {@code b} are
 * walked as a 4-connected <em>supercover</em> line (integer arithmetic only, deterministic). A current
 * feet height starts at {@code a.y}. Every step to the next cell must be a legal cardinal
 * {@link MovementModel} move from {@code (cell, y)}; the move's target height becomes the new current
 * height. When the segment passes exactly through a cell corner, both side cells must be passable:
 * the diagonal cell is reached via the x-side cell and, independently, via the z-side cell, and both
 * routes must be legal and arrive at the same height.
 *
 * <p>The line is clear iff {@code a} is walkable, every step is legal, and the final height equals
 * {@code b.y}. A segment that is clear can therefore be followed by walking in a straight line using
 * only moves A* itself would accept.
 */
// THREADING: stateless pure functions; runs on worker threads against snapshot-backed WorldViews.
public final class LineOfSight {

  private LineOfSight() {
  }

  /**
   * Whether a player can walk the straight line from {@code a} to {@code b}.
   *
   * @param world world to query
   * @param a start feet position
   * @param b end feet position
   * @return {@code true} iff the line is walkable (see class Javadoc); for {@code a.equals(b)},
   *     {@code world.walkable(a)}
   * @throws NullPointerException if any argument is null
   */
  public static boolean clear(WorldView world, GridPos a, GridPos b) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(a, "a");
    Objects.requireNonNull(b, "b");
    if (!world.walkable(a.x(), a.y(), a.z())) {
      return false;
    }
    if (a.equals(b)) {
      return true;
    }
    Walker walker = new Walker(world, a.y());
    return walk(a.x(), a.z(), b.x(), b.z(), walker) && walker.y == b.y();
  }

  /**
   * The supercover cells of the segment from {@code (x0, z0)} to {@code (x1, z1)}, in walk order,
   * each as {@code {x, z}}. At an exact corner crossing both side cells are listed (x side first)
   * before the diagonal cell. Exposed for tests.
   */
  static List<int[]> supercover(int x0, int z0, int x1, int z1) {
    List<int[]> cells = new ArrayList<>();
    cells.add(new int[] {x0, z0});
    walk(x0, z0, x1, z1, new StepVisitor() {
      @Override
      public boolean cardinal(int fromX, int fromZ, int toX, int toZ) {
        cells.add(new int[] {toX, toZ});
        return true;
      }

      @Override
      public boolean corner(int fromX, int fromZ, int sx, int sz) {
        cells.add(new int[] {fromX + sx, fromZ});
        cells.add(new int[] {fromX, fromZ + sz});
        cells.add(new int[] {fromX + sx, fromZ + sz});
        return true;
      }
    });
    return cells;
  }

  /** Receives supercover steps; returning {@code false} aborts the walk. */
  private interface StepVisitor {
    boolean cardinal(int fromX, int fromZ, int toX, int toZ);

    /** Exact corner crossing from {@code (fromX, fromZ)} to {@code (fromX+sx, fromZ+sz)}. */
    boolean corner(int fromX, int fromZ, int sx, int sz);
  }

  /**
   * Walks the supercover line. The next boundary crossed is decided by comparing the parametric
   * positions {@code (2*ix+1)/(2*nx)} and {@code (2*iz+1)/(2*nz)}, cross-multiplied into longs.
   *
   * @return {@code false} iff the visitor aborted
   */
  private static boolean walk(int x0, int z0, int x1, int z1, StepVisitor visitor) {
    long nx = Math.abs((long) x1 - x0);
    long nz = Math.abs((long) z1 - z0);
    int sx = x1 > x0 ? 1 : -1;
    int sz = z1 > z0 ? 1 : -1;
    int x = x0;
    int z = z0;
    long ix = 0;
    long iz = 0;
    while (ix < nx || iz < nz) {
      long decision = (1 + 2 * ix) * nz - (1 + 2 * iz) * nx;
      if (decision == 0) {
        if (!visitor.corner(x, z, sx, sz)) {
          return false;
        }
        x += sx;
        z += sz;
        ix++;
        iz++;
      } else if (decision < 0) {
        if (!visitor.cardinal(x, z, x + sx, z)) {
          return false;
        }
        x += sx;
        ix++;
      } else {
        if (!visitor.cardinal(x, z, x, z + sz)) {
          return false;
        }
        z += sz;
        iz++;
      }
    }
    return true;
  }

  /** Per-call state: carries the current feet height along the walk. */
  private static final class Walker implements StepVisitor, MovementModel.NeighborSink {
    private final WorldView world;
    int y;
    private int targetX;
    private int targetZ;
    private int foundY;
    private boolean found;

    Walker(WorldView world, int y) {
      this.world = world;
      this.y = y;
    }

    @Override
    public boolean cardinal(int fromX, int fromZ, int toX, int toZ) {
      if (!move(fromX, y, fromZ, toX, toZ)) {
        return false;
      }
      y = foundY;
      return true;
    }

    @Override
    public boolean corner(int fromX, int fromZ, int sx, int sz) {
      int dx = fromX + sx;
      int dz = fromZ + sz;
      // Route via the x-side cell.
      if (!move(fromX, y, fromZ, dx, fromZ) || !move(dx, foundY, fromZ, dx, dz)) {
        return false;
      }
      int viaX = foundY;
      // Route via the z-side cell.
      if (!move(fromX, y, fromZ, fromX, dz) || !move(fromX, foundY, dz, dx, dz)) {
        return false;
      }
      if (foundY != viaX) {
        return false;
      }
      y = foundY;
      return true;
    }

    /** Finds the cardinal MovementModel neighbor of {@code (x, fy, z)} at {@code (tx, tz)}. */
    private boolean move(int x, int fy, int z, int tx, int tz) {
      targetX = tx;
      targetZ = tz;
      found = false;
      MovementModel.forEachNeighbor(world, x, fy, z, this);
      return found;
    }

    @Override
    public void accept(int x, int ny, int z, double cost) {
      if (x == targetX && z == targetZ) {
        foundY = ny;
        found = true;
      }
    }
  }
}
