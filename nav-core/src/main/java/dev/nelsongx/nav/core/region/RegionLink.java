package dev.nelsongx.nav.core.region;

import dev.nelsongx.nav.core.GridPos;
import java.util.Comparator;
import java.util.Objects;

/**
 * A directed edge of the region graph: exactly one legal
 * {@link dev.nelsongx.nav.core.path.MovementModel} move from node {@code fromPos} (member of region
 * {@code from}) to node {@code toPos} (member of a different region {@code to}). Covers cross-sector
 * moves and intra-sector one-way moves (e.g. drops). There is at most one link per
 * {@code (fromPos, toPos)} pair.
 *
 * @param from source region
 * @param to target region, different from {@code from}
 * @param fromPos source feet position
 * @param toPos target feet position
 * @param cost MovementModel move cost, &gt; 0
 */
public record RegionLink(RegionId from, RegionId to, GridPos fromPos, GridPos toPos, double cost) {

  /**
   * Canonical order of links in {@link RegionGraph#linksFrom} lists: {@code fromPos} then
   * {@code toPos}, each by {@link Region#NODE_ORDER}.
   */
  public static final Comparator<RegionLink> POS_ORDER = Comparator
      .comparing(RegionLink::fromPos, Region.NODE_ORDER)
      .thenComparing(RegionLink::toPos, Region.NODE_ORDER);

  /**
   * Validates.
   *
   * @throws NullPointerException if any reference is null
   * @throws IllegalArgumentException if {@code from.equals(to)} or cost is not a positive finite number
   */
  public RegionLink {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(fromPos, "fromPos");
    Objects.requireNonNull(toPos, "toPos");
    if (from.equals(to)) {
      throw new IllegalArgumentException("self link on " + from);
    }
    if (!(cost > 0) || Double.isInfinite(cost)) {
      throw new IllegalArgumentException("cost must be positive and finite: " + cost);
    }
  }
}
