package dev.nelsongx.nav.core.region;

import java.util.Comparator;

/**
 * Identifies a region: its sector and its index within that sector.
 *
 * <p>Indices are {@code 0..n-1} for a sector with {@code n} regions, assigned in ascending order of
 * the regions' seeds under {@link Region#NODE_ORDER}. An id is only meaningful for the graph (and world
 * state) it was built from; rebuilding a sector may renumber its regions.
 *
 * <p>Natural order: {@code sx}, {@code sz}, {@code index}.
 *
 * @param sx sector x index
 * @param sz sector z index
 * @param index region index within the sector, &gt;= 0
 */
public record RegionId(int sx, int sz, int index) implements Comparable<RegionId> {

  private static final Comparator<RegionId> ORDER = Comparator.comparingInt(RegionId::sx)
      .thenComparingInt(RegionId::sz).thenComparingInt(RegionId::index);

  /**
   * Validates.
   *
   * @throws IllegalArgumentException if {@code index < 0}
   */
  public RegionId {
    if (index < 0) {
      throw new IllegalArgumentException("index must be >= 0: " + index);
    }
  }

  /**
   * The sector this region belongs to.
   *
   * @return sector position
   */
  public SectorPos sector() {
    return new SectorPos(sx, sz);
  }

  @Override
  public int compareTo(RegionId o) {
    return ORDER.compare(this, o);
  }
}
