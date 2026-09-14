package dev.nelsongx.nav.fabric.route;

import dev.nelsongx.nav.core.WorldView;
import java.util.OptionalInt;

/** Pure endpoint Y resolution over a {@link WorldView}. Minecraft-free. */
// THREADING: pure static functions; run on NavExecutor workers against snapshot views (any thread).
public final class Endpoints {

  /** Maximum vertical offset searched around a given start Y. */
  public static final int START_Y_TOLERANCE = 3;

  private Endpoints() {
  }

  /**
   * Nearest walkable feet Y to {@code y} in column {@code (x, z)}, searching {@code y, y-1, y+1, y-2,
   * y+2, y-3, y+3}. Never jumps to the surface (a player in a cave stays in the cave); tolerates slabs,
   * partial blocks and mid-jump positions.
   *
   * @param world world view
   * @param x block x
   * @param y given feet y
   * @param z block z
   * @return the nearest walkable y within ±{@link #START_Y_TOLERANCE}, or empty if none
   */
  public static OptionalInt nearestWalkableY(WorldView world, int x, int y, int z) {
    if (world.walkable(x, y, z)) {
      return OptionalInt.of(y);
    }
    for (int d = 1; d <= START_Y_TOLERANCE; d++) {
      if (world.walkable(x, y - d, z)) {
        return OptionalInt.of(y - d);
      }
      if (world.walkable(x, y + d, z)) {
        return OptionalInt.of(y + d);
      }
    }
    return OptionalInt.empty();
  }
}
