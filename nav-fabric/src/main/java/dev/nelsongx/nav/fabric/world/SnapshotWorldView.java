package dev.nelsongx.nav.fabric.world;

import dev.nelsongx.nav.core.WorldView;
import java.util.Collection;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * {@link WorldView} over an immutable set of {@link ChunkSnapshot}s.
 *
 * <p>Missing chunks, and y outside {@code [minY, maxY]}, read as neither passable nor standable.
 * {@code minY}/{@code maxY} are plain ints captured from the level on the server thread when the
 * owning {@link SnapshotCache} was created. No Minecraft types are referenced here.
 */
// THREADING: immutable after construction; safe for concurrent reads from any thread, never blocks.
// Intended for worker threads (NavExecutor). Never touches a live level.
public final class SnapshotWorldView implements WorldView {

  private final int minY;
  private final int maxY;
  /** Open-addressed table keyed by ChunkSnapshot.key; capacity is a power of two. */
  private final long[] keys;
  private final ChunkSnapshot[] values;
  private final int mask;
  private final int size;

  /**
   * Creates a view.
   *
   * @param minY lowest block y (inclusive)
   * @param maxY highest block y (inclusive)
   * @param snapshots chunk snapshots; later duplicates of the same chunk replace earlier ones
   */
  public SnapshotWorldView(int minY, int maxY, Collection<ChunkSnapshot> snapshots) {
    Objects.requireNonNull(snapshots, "snapshots");
    if (maxY < minY) {
      throw new IllegalArgumentException("maxY < minY: " + maxY + " < " + minY);
    }
    this.minY = minY;
    this.maxY = maxY;
    int cap = Integer.highestOneBit(Math.max(4, snapshots.size() * 2 - 1)) << 1;
    this.keys = new long[cap];
    this.values = new ChunkSnapshot[cap];
    this.mask = cap - 1;
    int n = 0;
    for (ChunkSnapshot s : snapshots) {
      Objects.requireNonNull(s, "snapshot");
      long k = ChunkSnapshot.key(s.chunkX(), s.chunkZ());
      int i = slot(k);
      while (values[i] != null && keys[i] != k) {
        i = (i + 1) & mask;
      }
      if (values[i] == null) {
        n++;
      }
      keys[i] = k;
      values[i] = s;
    }
    this.size = n;
  }

  private int slot(long key) {
    long h = key * 0x9E3779B97F4A7C15L;
    return (int) (h ^ (h >>> 32)) & mask;
  }

  /**
   * Snapshot for a chunk, or null if absent.
   *
   * @param chunkX chunk x
   * @param chunkZ chunk z
   * @return snapshot or null
   */
  public ChunkSnapshot chunk(int chunkX, int chunkZ) {
    long k = ChunkSnapshot.key(chunkX, chunkZ);
    int i = slot(k);
    ChunkSnapshot v;
    while ((v = values[i]) != null) {
      if (keys[i] == k) {
        return v;
      }
      i = (i + 1) & mask;
    }
    return null;
  }

  /** @return number of chunks available in this view */
  public int chunkCount() {
    return size;
  }

  /**
   * Raw classification, {@link BlockClass#BLOCKED} for missing chunks or out-of-range y.
   *
   * @param x block x
   * @param y block y
   * @param z block z
   * @return block class
   */
  public byte classify(int x, int y, int z) {
    if (y < minY || y > maxY) {
      return BlockClass.BLOCKED;
    }
    ChunkSnapshot c = chunk(x >> 4, z >> 4);
    return c == null ? BlockClass.BLOCKED : c.classify(x, y, z);
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
  }

  @Override
  public boolean passable(int x, int y, int z) {
    return classify(x, y, z) == BlockClass.PASSABLE;
  }

  @Override
  public boolean walkable(int x, int y, int z) {
    if (y - 1 < minY || y + 1 > maxY) {
      return false;
    }
    ChunkSnapshot c = chunk(x >> 4, z >> 4);
    return c != null && walkableIn(c, x, y, z);
  }

  private static boolean walkableIn(ChunkSnapshot c, int x, int y, int z) {
    return c.classify(x, y - 1, z) == BlockClass.STANDABLE
        && c.classify(x, y, z) == BlockClass.PASSABLE
        && c.classify(x, y + 1, z) == BlockClass.PASSABLE;
  }

  @Override
  public OptionalInt groundY(int x, int z) {
    ChunkSnapshot c = chunk(x >> 4, z >> 4);
    if (c == null) {
      return OptionalInt.empty();
    }
    for (int y = maxY - 1; y >= minY + 1; y--) {
      if (walkableIn(c, x, y, z)) {
        return OptionalInt.of(y);
      }
    }
    return OptionalInt.empty();
  }
}
