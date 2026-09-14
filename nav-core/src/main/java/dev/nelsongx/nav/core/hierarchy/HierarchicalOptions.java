package dev.nelsongx.nav.core.hierarchy;

/**
 * Effort limits and thresholds for {@link HierarchicalPathfinder}.
 *
 * @param maxCoarseNodes maximum coarse (portal) states expanded; &gt;= 1
 * @param maxSegmentNodes maximum nodes expanded by each fine in-region segment search; &gt;= 1
 * @param maxFlatNodes maximum nodes expanded by a flat A* search (near queries and fallbacks); &gt;= 1
 * @param nearDistance octile x/z distance at or below which the coarse layer is skipped and flat A* is
 *     used directly; &gt;= 0 ({@code 0} uses the coarse layer for every query of distinct columns)
 */
// THREADING: immutable value object; safe to share across any threads.
public record HierarchicalOptions(int maxCoarseNodes, int maxSegmentNodes, int maxFlatNodes,
    int nearDistance) {

  /**
   * Validates.
   *
   * @throws IllegalArgumentException if a node cap is &lt; 1 or {@code nearDistance} is negative
   */
  public HierarchicalOptions {
    requirePositive("maxCoarseNodes", maxCoarseNodes);
    requirePositive("maxSegmentNodes", maxSegmentNodes);
    requirePositive("maxFlatNodes", maxFlatNodes);
    if (nearDistance < 0) {
      throw new IllegalArgumentException("nearDistance must be >= 0: " + nearDistance);
    }
  }

  /**
   * Default options: 20,000 coarse nodes, 20,000 nodes per segment, 50,000 flat nodes, near distance
   * 48 blocks.
   *
   * @return the defaults
   */
  public static HierarchicalOptions defaults() {
    return new HierarchicalOptions(20_000, 20_000, 50_000, 48);
  }

  private static void requirePositive(String name, int value) {
    if (value < 1) {
      throw new IllegalArgumentException(name + " must be >= 1: " + value);
    }
  }
}
