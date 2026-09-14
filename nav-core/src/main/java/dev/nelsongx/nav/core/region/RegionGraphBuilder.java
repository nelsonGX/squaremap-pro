package dev.nelsongx.nav.core.region;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.path.MovementModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Builds {@link RegionGraph}s from a {@link WorldView}. All legality comes from
 * {@link MovementModel#forEachNeighbor}.
 *
 * <p><b>Pure:</b> no static mutable state, no threads, no I/O; all working state is per call.
 */
// THREADING: pure function; runs on worker threads against snapshot-backed WorldViews, never the server
// thread.
public final class RegionGraphBuilder {

  private RegionGraphBuilder() {
  }

  /**
   * Builds the graph for every sector in the inclusive rectangle {@code [min, max]}, scanning every
   * column's {@code y} in {@code [world.minY(), world.maxY()]}.
   *
   * @param world world to read
   * @param min inclusive lower sector corner
   * @param max inclusive upper sector corner
   * @param sectorSize sector edge length, &gt;= 2
   * @return the built graph
   * @throws IllegalArgumentException if {@code sectorSize < 2}, {@code min} exceeds {@code max} on an
   *     axis, or the world's Y range / a sector is outside the supported coordinate range
   * @throws NullPointerException if an argument is null
   */
  public static RegionGraph build(WorldView world, SectorPos min, SectorPos max, int sectorSize) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(min, "min");
    Objects.requireNonNull(max, "max");
    if (min.sx() > max.sx() || min.sz() > max.sz()) {
      throw new IllegalArgumentException("min " + min + " exceeds max " + max);
    }
    List<SectorPos> all = new ArrayList<>();
    for (int sx = min.sx(); sx <= max.sx(); sx++) {
      for (int sz = min.sz(); sz <= max.sz(); sz++) {
        all.add(new SectorPos(sx, sz));
      }
    }
    return RegionGraph.empty(sectorSize, world.minY(), world.maxY()).withSectorsRebuilt(world, all);
  }

  // ---------------------------------------------------------------------------------------------
  // Incremental rebuild (also used by build)
  // ---------------------------------------------------------------------------------------------

  static RegionGraph rebuild(RegionGraph old, WorldView world, Collection<SectorPos> rebuiltSectors) {
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(rebuiltSectors, "rebuiltSectors");
    if (world.minY() != old.minY() || world.maxY() != old.maxY()) {
      throw new IllegalArgumentException("world Y range [" + world.minY() + ", " + world.maxY()
          + "] differs from graph Y range [" + old.minY() + ", " + old.maxY() + "]");
    }
    int size = old.sectorSize();
    Set<SectorPos> rebuilt = new TreeSet<>();
    for (SectorPos s : rebuiltSectors) {
      RegionGraph.requireSupportedSector(s, size);
      rebuilt.add(s);
    }
    Set<SectorPos> newSectors = new TreeSet<>(old.sectors());
    newSectors.addAll(rebuilt);

    // Regions of rebuilt sectors, with full node labelling.
    Map<SectorPos, Labeler> labelers = new HashMap<>();
    List<Region> regions = new ArrayList<>();
    Map<SectorPos, SectorLabels> full = new HashMap<>();
    for (SectorPos s : rebuilt) {
      SectorLabels labels = SectorLabels.compute(world, s, size);
      full.put(s, labels);
      labelers.put(s, labels);
      regions.addAll(labels.regions);
    }
    for (SectorPos s : old.sectors()) {
      if (!rebuilt.contains(s)) {
        regions.addAll(old.regionsIn(s));
      }
    }

    // Sectors whose outgoing links must be recomputed.
    Set<SectorPos> affected = new LinkedHashSet<>();
    for (SectorPos s : rebuilt) {
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          SectorPos n = new SectorPos(s.sx() + dx, s.sz() + dz);
          if (newSectors.contains(n)) {
            affected.add(n);
          }
        }
      }
    }

    List<RegionLink> links = new ArrayList<>();
    for (SectorPos s : old.sectors()) {
      boolean isAffected = affected.contains(s);
      for (Region r : old.regionsIn(s)) {
        for (RegionLink l : old.linksFrom(r.id())) {
          // Unaffected sectors keep everything; affected non-rebuilt sectors keep intra-sector links.
          if (!isAffected || (!rebuilt.contains(s) && l.to().sector().equals(s))) {
            links.add(l);
          }
        }
      }
    }

    LinkEmitter emitter = new LinkEmitter(world, size, links, sector -> {
      if (!newSectors.contains(sector)) {
        return null;
      }
      return labelers.computeIfAbsent(sector,
          k -> new LazyLabels(world, k, size, old.regionsIn(k)));
    });
    for (SectorPos s : affected) {
      SectorLabels labels = full.get(s);
      if (labels != null) {
        emitter.emitAll(s, labels);
      } else {
        emitter.emitCrossSectorFromRing(s, old.minY(), old.maxY());
      }
    }

    return new RegionGraph(size, old.minY(), old.maxY(), newSectors, regions, links);
  }

  // ---------------------------------------------------------------------------------------------
  // Labelling
  // ---------------------------------------------------------------------------------------------

  /** Maps a walkable node key of one sector to its region index. */
  private interface Labeler {
    /** Region index of the node, or -1 if the node is not a known region member. */
    int regionIndex(long key);
  }

  /** Full computation of one sector: nodes, mutual adjacency, components. */
  private static final class SectorLabels implements Labeler {
    final long[] nodes;
    final int[] regionOf;
    final LongIntHashMap index;
    final List<Region> regions;

    private SectorLabels(long[] nodes, int[] regionOf, LongIntHashMap index, List<Region> regions) {
      this.nodes = nodes;
      this.regionOf = regionOf;
      this.index = index;
      this.regions = regions;
    }

    @Override
    public int regionIndex(long key) {
      int i = index.get(key);
      return i < 0 ? -1 : regionOf[i];
    }

    static SectorLabels compute(WorldView world, SectorPos s, int size) {
      int x0 = s.minBlockX(size);
      int z0 = s.minBlockZ(size);
      int minY = world.minY();
      int maxY = world.maxY();

      // 1. Nodes in (y, z, x) order.
      long[] nodes = new long[64];
      int n = 0;
      for (int y = minY; y <= maxY; y++) {
        for (int z = z0; z < z0 + size; z++) {
          for (int x = x0; x < x0 + size; x++) {
            if (world.walkable(x, y, z)) {
              if (n == nodes.length) {
                nodes = Arrays.copyOf(nodes, n * 2);
              }
              nodes[n++] = PosKeys.pack(x, y, z);
            }
          }
        }
      }
      nodes = Arrays.copyOf(nodes, n);
      LongIntHashMap index = new LongIntHashMap(n);
      for (int i = 0; i < n; i++) {
        index.put(nodes[i], i);
      }

      // 2. In-sector directed adjacency, 8 slots per node.
      int[] adj = new int[n * 8];
      byte[] deg = new byte[n];
      AdjacencySink sink = new AdjacencySink(s, size, index, adj, deg);
      for (int i = 0; i < n; i++) {
        long k = nodes[i];
        sink.current = i;
        MovementModel.forEachNeighbor(world, PosKeys.x(k), PosKeys.y(k), PosKeys.z(k), sink);
      }

      // 3. Components under mutual adjacency; discovery in node order => seed = first node, and
      //    component order == seed order.
      int[] regionOf = new int[n];
      Arrays.fill(regionOf, -1);
      int[] stack = new int[Math.max(1, n)];
      List<Region> regions = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        if (regionOf[i] >= 0) {
          continue;
        }
        int r = regions.size();
        int sp = 0;
        stack[sp++] = i;
        regionOf[i] = r;
        int count = 0;
        int minX = Integer.MAX_VALUE;
        int minYb = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxYb = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        while (sp > 0) {
          int c = stack[--sp];
          long k = nodes[c];
          int x = PosKeys.x(k);
          int y = PosKeys.y(k);
          int z = PosKeys.z(k);
          count++;
          minX = Math.min(minX, x);
          minYb = Math.min(minYb, y);
          minZ = Math.min(minZ, z);
          maxX = Math.max(maxX, x);
          maxYb = Math.max(maxYb, y);
          maxZ = Math.max(maxZ, z);
          for (int e = 0; e < deg[c]; e++) {
            int j = adj[c * 8 + e];
            if (regionOf[j] < 0 && hasEdge(adj, deg, j, c)) {
              regionOf[j] = r;
              stack[sp++] = j;
            }
          }
        }
        long seed = nodes[i];
        regions.add(new Region(new RegionId(s.sx(), s.sz(), r),
            new GridPos(PosKeys.x(seed), PosKeys.y(seed), PosKeys.z(seed)), count,
            minX, minYb, minZ, maxX, maxYb, maxZ));
      }
      return new SectorLabels(nodes, regionOf, index, List.copyOf(regions));
    }

    private static boolean hasEdge(int[] adj, byte[] deg, int from, int to) {
      int base = from * 8;
      for (int e = 0; e < deg[from]; e++) {
        if (adj[base + e] == to) {
          return true;
        }
      }
      return false;
    }
  }

  private static final class AdjacencySink implements MovementModel.NeighborSink {
    private final SectorPos sector;
    private final int size;
    private final LongIntHashMap index;
    private final int[] adj;
    private final byte[] deg;
    int current;

    AdjacencySink(SectorPos sector, int size, LongIntHashMap index, int[] adj, byte[] deg) {
      this.sector = sector;
      this.size = size;
      this.index = index;
      this.adj = adj;
      this.deg = deg;
    }

    @Override
    public void accept(int x, int y, int z, double cost) {
      if (!sector.contains(x, z, size)) {
        return;
      }
      int j = index.get(PosKeys.pack(x, y, z));
      if (j >= 0) {
        adj[current * 8 + deg[current]++] = j;
      }
    }
  }

  /**
   * Labels nodes of a non-rebuilt sector on demand: flood-fills the node's component, finds its seed,
   * and matches it against the stored regions of that sector.
   */
  private static final class LazyLabels implements Labeler {
    private static final int UNMATCHED = Integer.MAX_VALUE - 1;
    private final WorldView world;
    private final SectorPos sector;
    private final int size;
    private final Map<GridPos, Integer> seedToIndex = new HashMap<>();
    private final LongIntHashMap cache = new LongIntHashMap(64);

    LazyLabels(WorldView world, SectorPos sector, int size, List<Region> stored) {
      this.world = world;
      this.sector = sector;
      this.size = size;
      for (Region r : stored) {
        seedToIndex.put(r.seed(), r.id().index());
      }
    }

    @Override
    public int regionIndex(long key) {
      int cached = cache.get(key);
      if (cached >= 0) {
        return cached == UNMATCHED ? -1 : cached;
      }
      long[] component = flood(world, sector, size, PosKeys.x(key), PosKeys.y(key), PosKeys.z(key));
      long seed = minYzx(component);
      Integer idx = seedToIndex.get(new GridPos(PosKeys.x(seed), PosKeys.y(seed), PosKeys.z(seed)));
      int value = idx == null ? UNMATCHED : idx;
      for (long k : component) {
        cache.put(k, value);
      }
      return idx == null ? -1 : idx;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Flood fill (locate / lazy labels)
  // ---------------------------------------------------------------------------------------------

  /** Seed (packed) of the mutual component containing walkable {@code (x, y, z)} within {@code s}. */
  static long componentSeed(WorldView world, SectorPos s, int size, int x, int y, int z) {
    return minYzx(flood(world, s, size, x, y, z));
  }

  private static long minYzx(long[] keys) {
    long best = keys[0];
    for (int i = 1; i < keys.length; i++) {
      if (PosKeys.compareYzx(keys[i], best) < 0) {
        best = keys[i];
      }
    }
    return best;
  }

  /**
   * All node keys of the mutual component containing walkable {@code (x, y, z)} restricted to sector
   * {@code s}. The start is assumed walkable.
   */
  private static long[] flood(WorldView world, SectorPos s, int size, int x, int y, int z) {
    long[] queue = new long[64];
    int head = 0;
    int tail = 0;
    LongIntHashMap visited = new LongIntHashMap(64);
    long start = PosKeys.pack(x, y, z);
    queue[tail++] = start;
    visited.put(start, 0);
    FloodSink sink = new FloodSink(world, s, size);
    while (head < tail) {
      long c = queue[head++];
      sink.reset(c);
      MovementModel.forEachNeighbor(world, PosKeys.x(c), PosKeys.y(c), PosKeys.z(c), sink);
      for (int i = 0; i < sink.count; i++) {
        long nk = sink.found[i];
        if (visited.get(nk) >= 0) {
          continue;
        }
        visited.put(nk, 0);
        if (tail == queue.length) {
          queue = Arrays.copyOf(queue, tail * 2);
        }
        queue[tail++] = nk;
      }
    }
    return Arrays.copyOf(queue, tail);
  }

  /** Collects in-sector neighbours of the current node that also have the current node as neighbour. */
  private static final class FloodSink implements MovementModel.NeighborSink {
    private final WorldView world;
    private final SectorPos sector;
    private final int size;
    final long[] found = new long[8];
    int count;
    private final ReverseCheck reverse = new ReverseCheck();

    FloodSink(WorldView world, SectorPos sector, int size) {
      this.world = world;
      this.sector = sector;
      this.size = size;
    }

    void reset(long current) {
      count = 0;
      reverse.target = current;
    }

    @Override
    public void accept(int x, int y, int z, double cost) {
      if (!sector.contains(x, z, size)) {
        return;
      }
      reverse.hit = false;
      MovementModel.forEachNeighbor(world, x, y, z, reverse);
      if (reverse.hit) {
        found[count++] = PosKeys.pack(x, y, z);
      }
    }
  }

  private static final class ReverseCheck implements MovementModel.NeighborSink {
    long target;
    boolean hit;

    @Override
    public void accept(int x, int y, int z, double cost) {
      if (PosKeys.pack(x, y, z) == target) {
        hit = true;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Links
  // ---------------------------------------------------------------------------------------------

  @FunctionalInterface
  private interface LabelerLookup {
    /** Labeler for a built sector, or null if the sector is not built. */
    Labeler get(SectorPos sector);
  }

  private static final class LinkEmitter implements MovementModel.NeighborSink {
    private final WorldView world;
    private final int size;
    private final List<RegionLink> out;
    private final LabelerLookup lookup;

    private SectorPos src;
    private Labeler srcLabels;
    private boolean crossOnly;
    private RegionId fromId;
    private GridPos fromPos;

    LinkEmitter(WorldView world, int size, List<RegionLink> out, LabelerLookup lookup) {
      this.world = world;
      this.size = size;
      this.out = out;
      this.lookup = lookup;
    }

    /** All links (intra- and cross-sector) out of a freshly computed sector. */
    void emitAll(SectorPos s, SectorLabels labels) {
      src = s;
      srcLabels = labels;
      crossOnly = false;
      for (int i = 0; i < labels.nodes.length; i++) {
        emitFrom(labels.nodes[i], labels.regionOf[i]);
      }
    }

    /**
     * Cross-sector links out of a non-rebuilt sector. Only border columns can have cross-sector moves.
     */
    void emitCrossSectorFromRing(SectorPos s, int minY, int maxY) {
      src = s;
      srcLabels = lookup.get(s);
      crossOnly = true;
      int x0 = s.minBlockX(size);
      int z0 = s.minBlockZ(size);
      int x1 = x0 + size - 1;
      int z1 = z0 + size - 1;
      for (int y = minY; y <= maxY; y++) {
        for (int z = z0; z <= z1; z++) {
          for (int x = x0; x <= x1; x++) {
            if (x != x0 && x != x1 && z != z0 && z != z1) {
              continue;
            }
            if (!world.walkable(x, y, z)) {
              continue;
            }
            long key = PosKeys.pack(x, y, z);
            int r = srcLabels.regionIndex(key);
            if (r >= 0) {
              emitFrom(key, r);
            }
          }
        }
      }
    }

    private void emitFrom(long key, int regionIndex) {
      fromId = new RegionId(src.sx(), src.sz(), regionIndex);
      fromPos = new GridPos(PosKeys.x(key), PosKeys.y(key), PosKeys.z(key));
      MovementModel.forEachNeighbor(world, fromPos.x(), fromPos.y(), fromPos.z(), this);
    }

    @Override
    public void accept(int x, int y, int z, double cost) {
      int tsx = Math.floorDiv(x, size);
      int tsz = Math.floorDiv(z, size);
      int targetIndex;
      if (tsx == src.sx() && tsz == src.sz()) {
        if (crossOnly) {
          return;
        }
        targetIndex = srcLabels.regionIndex(PosKeys.pack(x, y, z));
        if (targetIndex == fromId.index()) {
          return;
        }
      } else {
        Labeler target = lookup.get(new SectorPos(tsx, tsz));
        if (target == null) {
          return;
        }
        targetIndex = target.regionIndex(PosKeys.pack(x, y, z));
      }
      if (targetIndex < 0) {
        return;
      }
      out.add(new RegionLink(fromId, new RegionId(tsx, tsz, targetIndex), fromPos,
          new GridPos(x, y, z), cost));
    }
  }
}
