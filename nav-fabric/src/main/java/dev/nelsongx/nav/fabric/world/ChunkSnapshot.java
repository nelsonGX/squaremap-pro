package dev.nelsongx.nav.fabric.world;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable per-chunk copy of {@link BlockClass} values.
 *
 * <p>Storage is per 16-block vertical section, aligned to {@code minY}: section {@code s} covers
 * {@code [minY + 16s, minY + 16s + 15]}. A section is either <i>uniform</i> (one byte for all 4096
 * blocks, no array) or a {@code byte[4096]} indexed {@code ly * 256 + lz * 16 + lx} where
 * {@code ly = (y - minY) & 15}, {@code lz = z & 15}, {@code lx = x & 15}.
 *
 * <p>No Minecraft types appear in fields or API.
 */
// THREADING: immutable after build(); safe for concurrent reads from any thread. The Builder is
// single-threaded (confined to whichever thread creates it, normally the server thread).
public final class ChunkSnapshot {

  /** Blocks per section edge. */
  public static final int SECTION_SIZE = 16;
  /** Blocks per section. */
  public static final int SECTION_VOLUME = 4096;

  private final int chunkX;
  private final int chunkZ;
  private final int minY;
  private final int height;
  /** Per section: null if uniform, otherwise 4096 bytes. Never mutated after construction. */
  private final byte[][] sections;
  /** Per section uniform value; meaningful only where {@code sections[s] == null}. */
  private final byte[] uniform;

  private ChunkSnapshot(int chunkX, int chunkZ, int minY, int height, byte[][] sections,
      byte[] uniform) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minY = minY;
    this.height = height;
    this.sections = sections;
    this.uniform = uniform;
  }

  /**
   * Creates a snapshot where every block has the same class.
   *
   * @param chunkX chunk x
   * @param chunkZ chunk z
   * @param minY lowest block y (inclusive)
   * @param height number of block layers (&gt; 0)
   * @param value class for every block
   * @return the snapshot
   */
  public static ChunkSnapshot uniform(int chunkX, int chunkZ, int minY, int height, byte value) {
    Builder b = builder(chunkX, chunkZ, minY, height);
    for (int s = 0; s < b.sectionCount(); s++) {
      b.setUniformSection(s, value);
    }
    return b.build();
  }

  /**
   * Starts a builder; all blocks default to {@link BlockClass#BLOCKED}.
   *
   * @param chunkX chunk x
   * @param chunkZ chunk z
   * @param minY lowest block y (inclusive)
   * @param height number of block layers (&gt; 0)
   * @return a new builder
   */
  public static Builder builder(int chunkX, int chunkZ, int minY, int height) {
    return new Builder(chunkX, chunkZ, minY, height);
  }

  /**
   * Packs chunk coordinates into a single key, as used by {@link SnapshotWorldView}.
   *
   * @param chunkX chunk x
   * @param chunkZ chunk z
   * @return packed key
   */
  public static long key(int chunkX, int chunkZ) {
    return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
  }

  /** @return chunk x */
  public int chunkX() {
    return chunkX;
  }

  /** @return chunk z */
  public int chunkZ() {
    return chunkZ;
  }

  /** @return lowest block y (inclusive) */
  public int minY() {
    return minY;
  }

  /** @return number of block layers */
  public int height() {
    return height;
  }

  /** @return highest block y (inclusive) */
  public int maxY() {
    return minY + height - 1;
  }

  /**
   * Number of sections that are stored uniformly (no per-block array).
   *
   * @return uniform section count
   */
  public int uniformSectionCount() {
    int n = 0;
    for (byte[] s : sections) {
      if (s == null) {
        n++;
      }
    }
    return n;
  }

  /**
   * Classification of a block. {@code x} and {@code z} are world block coordinates; only their low
   * 4 bits are used (the caller is responsible for picking the right chunk).
   *
   * @param x world block x
   * @param y world block y
   * @param z world block z
   * @return the {@link BlockClass} value, or {@link BlockClass#BLOCKED} if {@code y} is outside
   *     {@code [minY, maxY]}
   */
  public byte classify(int x, int y, int z) {
    int ry = y - minY;
    if (ry < 0 || ry >= height) {
      return BlockClass.BLOCKED;
    }
    int s = ry >>> 4;
    byte[] data = sections[s];
    if (data == null) {
      return uniform[s];
    }
    return data[((ry & 15) << 8) | ((z & 15) << 4) | (x & 15)];
  }

  @Override
  public String toString() {
    return "ChunkSnapshot[" + chunkX + "," + chunkZ + " y=" + minY + ".." + maxY() + "]";
  }

  /** Mutable builder; compacts uniform sections on {@link #build()}. */
  // THREADING: not thread-safe; confine to one thread.
  public static final class Builder {
    private final int chunkX;
    private final int chunkZ;
    private final int minY;
    private final int height;
    private final byte[][] sections;
    private final byte[] uniform;
    private boolean built;

    private Builder(int chunkX, int chunkZ, int minY, int height) {
      if (height <= 0) {
        throw new IllegalArgumentException("height must be > 0: " + height);
      }
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.minY = minY;
      this.height = height;
      int count = (height + SECTION_SIZE - 1) / SECTION_SIZE;
      this.sections = new byte[count][];
      this.uniform = new byte[count];
      Arrays.fill(uniform, BlockClass.BLOCKED);
    }

    /** @return number of sections */
    public int sectionCount() {
      return sections.length;
    }

    /**
     * Marks a whole section as a single class.
     *
     * @param section section index (0 = the section containing {@code minY})
     * @param value class
     * @return this
     */
    public Builder setUniformSection(int section, byte value) {
      checkOpen();
      checkValue(value);
      sections[section] = null;
      uniform[section] = value;
      return this;
    }

    /**
     * Sets a whole section from a 4096-byte array (copied), indexed {@code ly*256 + lz*16 + lx}.
     *
     * @param section section index
     * @param data 4096 classification bytes
     * @return this
     */
    public Builder setSection(int section, byte[] data) {
      checkOpen();
      Objects.requireNonNull(data, "data");
      if (data.length != SECTION_VOLUME) {
        throw new IllegalArgumentException("section data must have 4096 entries: " + data.length);
      }
      for (byte v : data) {
        checkValue(v);
      }
      sections[section] = data.clone();
      return this;
    }

    /**
     * Like {@link #setSection} but takes ownership of {@code data} without copying or validating.
     * The caller must not modify the array afterwards and must only store valid classes.
     */
    Builder adoptSection(int section, byte[] data) {
      checkOpen();
      if (data.length != SECTION_VOLUME) {
        throw new IllegalArgumentException("section data must have 4096 entries: " + data.length);
      }
      sections[section] = data;
      return this;
    }

    /**
     * Sets one block. {@code x}/{@code z} low 4 bits are used.
     *
     * @param x world or local block x
     * @param y world block y in {@code [minY, minY + height - 1]}
     * @param z world or local block z
     * @param value class
     * @return this
     */
    public Builder set(int x, int y, int z, byte value) {
      checkOpen();
      checkValue(value);
      int ry = y - minY;
      if (ry < 0 || ry >= height) {
        throw new IllegalArgumentException("y out of range: " + y);
      }
      int s = ry >>> 4;
      byte[] data = sections[s];
      if (data == null) {
        data = new byte[SECTION_VOLUME];
        Arrays.fill(data, uniform[s]);
        sections[s] = data;
      }
      data[((ry & 15) << 8) | ((z & 15) << 4) | (x & 15)] = value;
      return this;
    }

    /**
     * Builds the immutable snapshot, collapsing sections whose bytes are all equal.
     *
     * @return the snapshot
     */
    public ChunkSnapshot build() {
      checkOpen();
      built = true;
      for (int s = 0; s < sections.length; s++) {
        byte[] data = sections[s];
        if (data == null) {
          continue;
        }
        byte first = data[0];
        boolean same = true;
        for (int i = 1; i < data.length; i++) {
          if (data[i] != first) {
            same = false;
            break;
          }
        }
        if (same) {
          sections[s] = null;
          uniform[s] = first;
        }
      }
      return new ChunkSnapshot(chunkX, chunkZ, minY, height, sections, uniform);
    }

    private void checkOpen() {
      if (built) {
        throw new IllegalStateException("builder already built");
      }
    }

    private static void checkValue(byte value) {
      if (!BlockClass.isValid(value)) {
        throw new IllegalArgumentException("invalid block class: " + value);
      }
    }
  }
}
