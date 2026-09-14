package dev.nelsongx.nav.core.region;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable coarse navigation graph over a set of built sectors.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Every built sector ({@link #sectors()}) was scanned for walkable feet positions over every
 *       column and every {@code y} in {@code [minY(), maxY()]}; a built sector may have zero regions.
 *   <li>Regions of a sector are the mutual-connectivity components of its nodes (see {@link Region}),
 *       indexed {@code 0..n-1} by ascending seed under {@link Region#NODE_ORDER}.
 *   <li>There is one {@link RegionLink} per legal directed MovementModel move between nodes of two
 *       different regions, and links exist only when both endpoints' sectors are built. So a fine search
 *       from any node of region A can follow any link out of A (move inside A to {@code fromPos}, then
 *       take the move), and the set of links is complete for moves among built sectors.
 *   <li>{@link #regionsIn} lists are ordered by index; {@link #linksFrom} and {@link #linksTo} lists by
 *       {@link RegionLink#POS_ORDER}. {@link #sectors()} iterates in {@link SectorPos} natural order.
 * </ul>
 *
 * <p>{@code equals}/{@code hashCode} compare content (sector size, Y range, sectors, regions, links).
 */
// THREADING: immutable value object; safe to share across any threads. withSectorsRebuilt/locate read the
// given WorldView and must run on worker threads against snapshot-backed views.
public final class RegionGraph {

  private final int sectorSize;
  private final int minY;
  private final int maxY;
  private final Set<SectorPos> sectors;
  private final Map<SectorPos, List<Region>> regionsBySector;
  private final Map<RegionId, List<RegionLink>> linksFrom;
  private final Map<RegionId, List<RegionLink>> linksTo;
  private final int regionCount;
  private final int linkCount;

  /**
   * Validating, canonicalizing constructor.
   *
   * @throws IllegalArgumentException if the parts are inconsistent: sector size &lt; 2, inverted or
   *     unsupported Y range, sectors outside the supported coordinate range, a region in an unbuilt
   *     sector, non-contiguous region indices, or a link whose regions do not exist or whose positions lie
   *     outside their regions' sectors
   */
  RegionGraph(int sectorSize, int minY, int maxY, Collection<SectorPos> sectors,
      Collection<Region> regions, Collection<RegionLink> links) {
    SectorPos.requireValidSize(sectorSize);
    if (minY > maxY) {
      throw new IllegalArgumentException("minY > maxY: " + minY + " > " + maxY);
    }
    if (minY < PosKeys.Y_MIN || maxY > PosKeys.Y_MAX) {
      throw new IllegalArgumentException("Y range [" + minY + ", " + maxY + "] unsupported");
    }
    this.sectorSize = sectorSize;
    this.minY = minY;
    this.maxY = maxY;

    TreeSet<SectorPos> sectorSet = new TreeSet<>();
    for (SectorPos s : sectors) {
      requireSupportedSector(s, sectorSize);
      sectorSet.add(s);
    }
    this.sectors = Collections.unmodifiableSortedSet(sectorSet);

    Map<SectorPos, List<Region>> bySector = new HashMap<>();
    Map<RegionId, Region> byId = new HashMap<>();
    for (Region r : regions) {
      SectorPos s = r.id().sector();
      if (!sectorSet.contains(s)) {
        throw new IllegalArgumentException("region " + r.id() + " in unbuilt sector");
      }
      if (byId.put(r.id(), r) != null) {
        throw new IllegalArgumentException("duplicate region " + r.id());
      }
      bySector.computeIfAbsent(s, k -> new ArrayList<>()).add(r);
    }
    Map<SectorPos, List<Region>> frozen = new HashMap<>();
    for (Map.Entry<SectorPos, List<Region>> e : bySector.entrySet()) {
      List<Region> list = e.getValue();
      list.sort(Comparator.comparingInt(r -> r.id().index()));
      for (int i = 0; i < list.size(); i++) {
        if (list.get(i).id().index() != i) {
          throw new IllegalArgumentException("non-contiguous region indices in " + e.getKey());
        }
      }
      frozen.put(e.getKey(), List.copyOf(list));
    }
    this.regionsBySector = frozen;
    this.regionCount = byId.size();

    Map<RegionId, List<RegionLink>> from = new HashMap<>();
    Map<RegionId, List<RegionLink>> to = new HashMap<>();
    int count = 0;
    for (RegionLink l : links) {
      if (!byId.containsKey(l.from()) || !byId.containsKey(l.to())) {
        throw new IllegalArgumentException("link references unknown region: " + l);
      }
      if (!l.from().sector().contains(l.fromPos().x(), l.fromPos().z(), sectorSize)
          || !l.to().sector().contains(l.toPos().x(), l.toPos().z(), sectorSize)) {
        throw new IllegalArgumentException("link position outside its sector: " + l);
      }
      from.computeIfAbsent(l.from(), k -> new ArrayList<>()).add(l);
      to.computeIfAbsent(l.to(), k -> new ArrayList<>()).add(l);
      count++;
    }
    this.linksFrom = freezeLinks(from);
    this.linksTo = freezeLinks(to);
    this.linkCount = count;
  }

  private static Map<RegionId, List<RegionLink>> freezeLinks(Map<RegionId, List<RegionLink>> m) {
    Map<RegionId, List<RegionLink>> out = new HashMap<>();
    for (Map.Entry<RegionId, List<RegionLink>> e : m.entrySet()) {
      List<RegionLink> list = e.getValue();
      list.sort(RegionLink.POS_ORDER.thenComparing(RegionLink::from).thenComparing(RegionLink::to));
      out.put(e.getKey(), List.copyOf(list));
    }
    return out;
  }

  static void requireSupportedSector(SectorPos s, int sectorSize) {
    Objects.requireNonNull(s, "sector");
    long x0 = (long) s.sx() * sectorSize;
    long z0 = (long) s.sz() * sectorSize;
    long x1 = x0 + sectorSize - 1;
    long z1 = z0 + sectorSize - 1;
    if (x0 < PosKeys.XZ_MIN || z0 < PosKeys.XZ_MIN || x1 > PosKeys.XZ_MAX || z1 > PosKeys.XZ_MAX) {
      throw new IllegalArgumentException("sector " + s + " outside the supported coordinate range");
    }
  }

  /**
   * An empty graph (no built sectors), the starting point for incremental building with
   * {@link #withSectorsRebuilt}.
   *
   * @param sectorSize sector edge length, &gt;= 2
   * @param minY inclusive lowest Y that will be scanned
   * @param maxY inclusive highest Y that will be scanned
   * @return an empty graph
   * @throws IllegalArgumentException if {@code sectorSize < 2} or the Y range is inverted/unsupported
   *     (supported: {@code [-2048, 2047]})
   */
  public static RegionGraph empty(int sectorSize, int minY, int maxY) {
    return new RegionGraph(sectorSize, minY, maxY, List.of(), List.of(), List.of());
  }

  /**
   * Sector edge length in blocks.
   *
   * @return sector size
   */
  public int sectorSize() {
    return sectorSize;
  }

  /**
   * Inclusive lowest Y scanned (the world's {@code minY()} at build time).
   *
   * @return min Y
   */
  public int minY() {
    return minY;
  }

  /**
   * Inclusive highest Y scanned (the world's {@code maxY()} at build time).
   *
   * @return max Y
   */
  public int maxY() {
    return maxY;
  }

  /**
   * Built sectors, including ones with zero regions.
   *
   * @return unmodifiable set iterating in {@link SectorPos} natural order
   */
  public Set<SectorPos> sectors() {
    return sectors;
  }

  /**
   * Looks up a region.
   *
   * @param id region id
   * @return the region, or empty if no such region exists
   */
  public Optional<Region> region(RegionId id) {
    Objects.requireNonNull(id, "id");
    List<Region> list = regionsBySector.get(id.sector());
    if (list == null || id.index() >= list.size()) {
      return Optional.empty();
    }
    return Optional.of(list.get(id.index()));
  }

  /**
   * Regions of a sector.
   *
   * @param sector sector
   * @return unmodifiable list ordered by index (list position == index); empty if the sector is unbuilt
   *     or has no regions
   */
  public List<Region> regionsIn(SectorPos sector) {
    Objects.requireNonNull(sector, "sector");
    return regionsBySector.getOrDefault(sector, List.of());
  }

  /**
   * Outgoing links of a region.
   *
   * @param id source region
   * @return unmodifiable list in {@link RegionLink#POS_ORDER}; empty if none or unknown region
   */
  public List<RegionLink> linksFrom(RegionId id) {
    Objects.requireNonNull(id, "id");
    return linksFrom.getOrDefault(id, List.of());
  }

  /**
   * Incoming links of a region.
   *
   * @param id target region
   * @return unmodifiable list in {@link RegionLink#POS_ORDER}; empty if none or unknown region
   */
  public List<RegionLink> linksTo(RegionId id) {
    Objects.requireNonNull(id, "id");
    return linksTo.getOrDefault(id, List.of());
  }

  /**
   * Total number of regions.
   *
   * @return region count
   */
  public int regionCount() {
    return regionCount;
  }

  /**
   * Total number of links.
   *
   * @return link count
   */
  public int linkCount() {
    return linkCount;
  }

  /**
   * Finds the region containing a standing position.
   *
   * <p>If {@code pos} is walkable in {@code world} and its sector is built, flood-fills the mutual
   * component containing {@code pos} within the sector (using {@code world}), takes its seed, and returns
   * the region of that sector with the same seed. Cost is proportional to the component's size.
   *
   * @param world world to query (should be the state the graph was built from)
   * @param pos feet position
   * @return the containing region; empty if {@code pos} is not walkable, is outside {@code [minY, maxY]},
   *     its sector is not built, or no region has the component's seed (graph is stale for that sector)
   */
  public Optional<RegionId> locate(WorldView world, GridPos pos) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(pos, "pos");
    SectorPos s = SectorPos.of(pos.x(), pos.z(), sectorSize);
    if (!sectors.contains(s) || pos.y() < minY || pos.y() > maxY
        || !world.walkable(pos.x(), pos.y(), pos.z())) {
      return Optional.empty();
    }
    long seed = RegionGraphBuilder.componentSeed(world, s, sectorSize, pos.x(), pos.y(), pos.z());
    GridPos seedPos = new GridPos(PosKeys.x(seed), PosKeys.y(seed), PosKeys.z(seed));
    for (Region r : regionsIn(s)) {
      if (r.seed().equals(seedPos)) {
        return Optional.of(r.id());
      }
    }
    return Optional.empty();
  }

  /**
   * Returns a new graph in which {@code rebuiltSectors} (added if not yet built) are recomputed from
   * {@code world}: their regions, all links out of them, and all links from built sectors into them.
   *
   * <p>Also recomputes the cross-sector links out of every built sector adjacent (8-neighbourhood) to a
   * rebuilt sector, because a diagonal move between two sectors depends on blocks in the sectors beside
   * it. Non-rebuilt sectors keep their regions.
   *
   * <p><b>Precondition:</b> every sector whose blocks differ between {@code world} and the world the
   * graph was built from is included in {@code rebuiltSectors}. Under that precondition the result equals
   * {@link RegionGraphBuilder#build} of {@code world} over the resulting sector set. Links into a
   * non-rebuilt sector are resolved by matching component seeds against its stored regions; targets whose
   * component matches no stored region are skipped (stale sector).
   *
   * @param world world to read; its {@code minY()}/{@code maxY()} must equal this graph's
   * @param rebuiltSectors sectors to (re)build; duplicates ignored
   * @return the new graph (this graph is unchanged)
   * @throws IllegalArgumentException if the world's Y range differs from the graph's or a sector is
   *     outside the supported coordinate range
   */
  public RegionGraph withSectorsRebuilt(WorldView world, Collection<SectorPos> rebuiltSectors) {
    return RegionGraphBuilder.rebuild(this, world, rebuiltSectors);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RegionGraph g)) {
      return false;
    }
    return sectorSize == g.sectorSize && minY == g.minY && maxY == g.maxY
        && regionCount == g.regionCount && linkCount == g.linkCount
        && sectors.equals(g.sectors) && regionsBySector.equals(g.regionsBySector)
        && linksFrom.equals(g.linksFrom);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sectorSize, minY, maxY, sectors, regionsBySector, linksFrom);
  }

  @Override
  public String toString() {
    return "RegionGraph[sectorSize=" + sectorSize + ", y=" + minY + ".." + maxY + ", sectors="
        + sectors.size() + ", regions=" + regionCount + ", links=" + linkCount + "]";
  }
}
