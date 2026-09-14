package dev.nelsongx.nav.fabric.world;

/**
 * Navigation classification of a single block, stored as a byte in {@link ChunkSnapshot}.
 *
 * <p>Deliberately free of Minecraft types so snapshot code can be unit-tested without a game.
 */
// THREADING: constants only; usable from any thread.
public final class BlockClass {

  /** An entity can occupy the block: no full collision and no fluid, not a hazard. */
  public static final byte PASSABLE = 0;

  /** A solid block whose top face an entity can stand on; not occupiable. */
  public static final byte STANDABLE = 1;

  /** Neither passable nor standable: fluids, hazards, partial non-supporting shapes. */
  public static final byte BLOCKED = 2;

  private BlockClass() {
  }

  /**
   * Whether {@code value} is one of the defined classes.
   *
   * @param value candidate byte
   * @return whether valid
   */
  public static boolean isValid(byte value) {
    return value == PASSABLE || value == STANDABLE || value == BLOCKED;
  }
}
