package dev.nelsongx.nav.fabric.route;

/**
 * Inclusive chunk rectangle covering two block endpoints plus a margin. Minecraft-free.
 *
 * @param minChunkX min chunk x (inclusive)
 * @param minChunkZ min chunk z (inclusive)
 * @param maxChunkX max chunk x (inclusive)
 * @param maxChunkZ max chunk z (inclusive)
 */
// THREADING: immutable value object; any thread.
public record ChunkBox(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

  /**
   * Chunks of both endpoints expanded by {@code margin} chunks on every side.
   *
   * @param fromX start block x
   * @param fromZ start block z
   * @param toX goal block x
   * @param toZ goal block z
   * @param margin extra chunks on each side, &gt;= 0
   * @return the box
   */
  public static ChunkBox around(int fromX, int fromZ, int toX, int toZ, int margin) {
    if (margin < 0) {
      throw new IllegalArgumentException("margin must be >= 0: " + margin);
    }
    int ax = fromX >> 4;
    int az = fromZ >> 4;
    int bx = toX >> 4;
    int bz = toZ >> 4;
    return new ChunkBox(Math.min(ax, bx) - margin, Math.min(az, bz) - margin,
        Math.max(ax, bx) + margin, Math.max(az, bz) + margin);
  }

  /** @return number of chunks in the box (long; cannot overflow for int chunk coordinates) */
  public long chunkCount() {
    return ((long) maxChunkX - minChunkX + 1) * ((long) maxChunkZ - minChunkZ + 1);
  }
}
