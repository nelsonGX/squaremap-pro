package dev.nelsongx.nav.core.region;

import dev.nelsongx.nav.core.GridPos;
import java.util.Comparator;
import java.util.Objects;

/**
 * A region: a maximal set of standing positions (nodes) inside one sector in which every two
 * neighbouring nodes are <em>mutually</em> connected (each is a legal
 * {@link dev.nelsongx.nav.core.path.MovementModel} neighbour of the other). A region is therefore
 * strongly connected: a fine search restricted to the region's sector can reach any node of it from any
 * other.
 *
 * @param id region id
 * @param seed the region's first node under {@link #NODE_ORDER}; always walkable at build time
 * @param nodeCount number of nodes, &gt;= 1
 * @param minX min x over member nodes
 * @param minY min feet y over member nodes
 * @param minZ min z over member nodes
 * @param maxX max x over member nodes
 * @param maxY max feet y over member nodes
 * @param maxZ max z over member nodes
 */
public record Region(RegionId id, GridPos seed, int nodeCount, int minX, int minY, int minZ,
    int maxX, int maxY, int maxZ) {

  /** Node order used for seeds and region indices: {@code y}, then {@code z}, then {@code x}, ascending. */
  public static final Comparator<GridPos> NODE_ORDER = Comparator.comparingInt(GridPos::y)
      .thenComparingInt(GridPos::z).thenComparingInt(GridPos::x);

  /**
   * Validates.
   *
   * @throws NullPointerException if {@code id} or {@code seed} is null
   * @throws IllegalArgumentException if {@code nodeCount < 1}, bounds are inverted, or the seed is
   *     outside the bounds
   */
  public Region {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(seed, "seed");
    if (nodeCount < 1) {
      throw new IllegalArgumentException("nodeCount must be >= 1: " + nodeCount);
    }
    if (minX > maxX || minY > maxY || minZ > maxZ) {
      throw new IllegalArgumentException("inverted bounds");
    }
    if (seed.x() < minX || seed.x() > maxX || seed.y() < minY || seed.y() > maxY
        || seed.z() < minZ || seed.z() > maxZ) {
      throw new IllegalArgumentException("seed " + seed + " outside bounds");
    }
  }

  /**
   * Integer midpoint of the bounds ({@code floorDiv(min + max, 2)} per axis). A convenience for
   * heuristics and display only: it is <b>not</b> necessarily walkable nor a member of the region.
   *
   * @return bounds midpoint
   */
  public GridPos center() {
    return new GridPos(Math.floorDiv(minX + maxX, 2), Math.floorDiv(minY + maxY, 2),
        Math.floorDiv(minZ + maxZ, 2));
  }
}
