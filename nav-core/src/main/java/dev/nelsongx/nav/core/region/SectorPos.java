package dev.nelsongx.nav.core.region;

import java.util.Comparator;

/**
 * A sector: the square column of blocks {@code [sx*size, (sx+1)*size-1] x [sz*size, (sz+1)*size-1]}
 * spanning all Y, for some sector size (default {@link #DEFAULT_SIZE}).
 *
 * <p>Natural order: {@code sx}, then {@code sz}.
 *
 * @param sx sector x index
 * @param sz sector z index
 */
public record SectorPos(int sx, int sz) implements Comparable<SectorPos> {

  /** Default sector edge length in blocks. */
  public static final int DEFAULT_SIZE = 16;

  private static final Comparator<SectorPos> ORDER =
      Comparator.comparingInt(SectorPos::sx).thenComparingInt(SectorPos::sz);

  /**
   * The sector containing block column {@code (x, z)}.
   *
   * @param x block x
   * @param z block z
   * @param sectorSize sector edge length, &gt;= 2
   * @return the containing sector ({@code Math.floorDiv} of each coordinate)
   * @throws IllegalArgumentException if {@code sectorSize < 2}
   */
  public static SectorPos of(int x, int z, int sectorSize) {
    requireValidSize(sectorSize);
    return new SectorPos(Math.floorDiv(x, sectorSize), Math.floorDiv(z, sectorSize));
  }

  /**
   * Lowest block x in this sector.
   *
   * @param sectorSize sector edge length
   * @return {@code sx * sectorSize}
   */
  public int minBlockX(int sectorSize) {
    return sx * sectorSize;
  }

  /**
   * Lowest block z in this sector.
   *
   * @param sectorSize sector edge length
   * @return {@code sz * sectorSize}
   */
  public int minBlockZ(int sectorSize) {
    return sz * sectorSize;
  }

  /**
   * Whether block column {@code (x, z)} lies in this sector.
   *
   * @param x block x
   * @param z block z
   * @param sectorSize sector edge length
   * @return whether contained
   */
  public boolean contains(int x, int z, int sectorSize) {
    return Math.floorDiv(x, sectorSize) == sx && Math.floorDiv(z, sectorSize) == sz;
  }

  @Override
  public int compareTo(SectorPos o) {
    return ORDER.compare(this, o);
  }

  static void requireValidSize(int sectorSize) {
    if (sectorSize < 2) {
      throw new IllegalArgumentException("sectorSize must be >= 2: " + sectorSize);
    }
  }
}
