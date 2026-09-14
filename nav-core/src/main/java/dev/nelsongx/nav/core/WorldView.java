package dev.nelsongx.nav.core;

import java.util.OptionalInt;

/**
 * Read-only view of block collision data that pathfinding runs against.
 *
 * <p><b>Threading contract:</b> implementations must be safe for concurrent reads from multiple
 * threads and must never block (no locks held across I/O, no waiting on other threads, no loading of
 * unavailable data). Data that is not available immediately is treated as unknown.
 */
public interface WorldView {

  /**
   * Lowest Y coordinate (inclusive) this view has data for.
   *
   * @return inclusive lower vertical bound
   */
  int minY();

  /**
   * Highest Y coordinate (inclusive) this view has data for.
   *
   * @return inclusive upper vertical bound
   */
  int maxY();

  /**
   * Whether an entity can occupy block {@code (x, y, z)}: the block is neither solid nor fluid.
   *
   * <p>Blocks that are unknown, unloaded, or out of bounds (including {@code y} outside
   * {@code [minY, maxY]}) yield {@code false}.
   *
   * @param x block x
   * @param y block y
   * @param z block z
   * @return whether the block can be occupied
   */
  boolean passable(int x, int y, int z);

  /**
   * Whether an entity two blocks tall can stand with its feet in block {@code (x, y, z)}.
   *
   * <p>True iff the block at {@code y - 1} is solid and standable, and
   * {@link #passable(int, int, int)} holds for both the feet block {@code y} and the head block
   * {@code y + 1}. Positions that are unknown, unloaded, or out of bounds (including a feet or head
   * block outside {@code [minY, maxY]}) yield {@code false}.
   *
   * @param x block x
   * @param y feet block y
   * @param z block z
   * @return whether the position is a valid standing position
   */
  boolean walkable(int x, int y, int z);

  /**
   * The highest standing position in the column {@code (x, z)}.
   *
   * @param x block x
   * @param z block z
   * @return the highest {@code y} in {@code [minY(), maxY()]} for which {@link #walkable(int, int, int)}
   *     is true, or empty if there is none
   */
  OptionalInt groundY(int x, int z);
}
