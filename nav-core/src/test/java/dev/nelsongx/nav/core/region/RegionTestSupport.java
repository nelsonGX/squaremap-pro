package dev.nelsongx.nav.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.path.MovementModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** Shared fixtures and brute-force oracles for region graph tests. */
final class RegionTestSupport {

  private RegionTestSupport() {
  }

  /**
   * 12x12 world, sectorSize 4 => 3x3 sectors.
   *
   * <ul>
   *   <li>(0,0): wall (solid y=1, fluid cap y=2) at x=2 splits it into 2 regions.
   *   <li>(1,0): floor with a bridge (y=3, row z=1) over it; bridge drops onto floor (intra one-way).
   *   <li>(2,0): no floor at all => built but empty.
   *   <li>(1,1): raised platform (y=3) over floor; platform drops into (0,1), (2,1), (1,0), (1,2)
   *       one way (cross-sector).
   *   <li>(1,2): fluid pool in the floor.
   * </ul>
   */
  static final String ACCEPTANCE = """
      ; floor
      y=0
      ########....
      ########....
      ########....
      ########....
      ############
      ############
      ############
      ############
      ############
      #####~~#####
      #####~~#####
      ############
      ; wall in sector (0,0)
      y=1
      ..#.........
      ..#.........
      ..#.........
      ..#.........
      ............
      ............
      ............
      ............
      ............
      ............
      ............
      ............
      y=2
      ..~.........
      ..~.........
      ..~.........
      ..~.........
      ............
      ............
      ............
      ............
      ............
      ............
      ............
      ............
      ; bridge (row z=1) and platform (sector (1,1))
      y=3
      ............
      ....####....
      ............
      ............
      ....####....
      ....####....
      ....####....
      ....####....
      ............
      ............
      ............
      ............
      """;

  /** Every walkable node of a sector. */
  static List<GridPos> nodesIn(WorldView w, SectorPos s, int size) {
    List<GridPos> out = new ArrayList<>();
    for (int y = w.minY(); y <= w.maxY(); y++) {
      for (int z = s.minBlockZ(size); z < s.minBlockZ(size) + size; z++) {
        for (int x = s.minBlockX(size); x < s.minBlockX(size) + size; x++) {
          if (w.walkable(x, y, z)) {
            out.add(new GridPos(x, y, z));
          }
        }
      }
    }
    return out;
  }

  /**
   * Checks the graph against brute force: every node locates to a region whose seed is the
   * (y,z,x)-minimal member, node counts and bounds match, indices are in seed order, and the link set is
   * exactly the set of legal moves between different regions of built sectors.
   */
  static void assertMatchesBruteForce(WorldView w, RegionGraph g) {
    int size = g.sectorSize();
    Map<GridPos, RegionId> regionOf = new HashMap<>();
    for (SectorPos s : g.sectors()) {
      Map<RegionId, List<GridPos>> members = new HashMap<>();
      for (GridPos p : nodesIn(w, s, size)) {
        Optional<RegionId> id = g.locate(w, p);
        assertTrue(id.isPresent(), () -> "node " + p + " not located");
        assertEquals(s, id.get().sector());
        members.computeIfAbsent(id.get(), k -> new ArrayList<>()).add(p);
        regionOf.put(p, id.get());
      }
      List<Region> regions = g.regionsIn(s);
      assertEquals(members.size(), regions.size(), "region count in " + s);
      GridPos prevSeed = null;
      for (int i = 0; i < regions.size(); i++) {
        Region r = regions.get(i);
        assertEquals(i, r.id().index());
        List<GridPos> m = members.get(r.id());
        assertEquals(m.size(), r.nodeCount(), "node count of " + r.id());
        GridPos min = m.stream().min(Region.NODE_ORDER).orElseThrow();
        assertEquals(min, r.seed(), "seed of " + r.id());
        assertEquals(m.stream().mapToInt(GridPos::x).min().orElseThrow(), r.minX());
        assertEquals(m.stream().mapToInt(GridPos::y).min().orElseThrow(), r.minY());
        assertEquals(m.stream().mapToInt(GridPos::z).min().orElseThrow(), r.minZ());
        assertEquals(m.stream().mapToInt(GridPos::x).max().orElseThrow(), r.maxX());
        assertEquals(m.stream().mapToInt(GridPos::y).max().orElseThrow(), r.maxY());
        assertEquals(m.stream().mapToInt(GridPos::z).max().orElseThrow(), r.maxZ());
        if (prevSeed != null) {
          assertTrue(Region.NODE_ORDER.compare(prevSeed, r.seed()) < 0, "seed order in " + s);
        }
        prevSeed = r.seed();
      }
    }

    Set<RegionLink> expected = new HashSet<>();
    for (Map.Entry<GridPos, RegionId> e : regionOf.entrySet()) {
      GridPos p = e.getKey();
      MovementModel.forEachNeighbor(w, p.x(), p.y(), p.z(), (x, y, z, cost) -> {
        RegionId to = regionOf.get(new GridPos(x, y, z));
        if (to != null && !to.equals(e.getValue())) {
          expected.add(new RegionLink(e.getValue(), to, p, new GridPos(x, y, z), cost));
        }
      });
    }
    Set<RegionLink> actual = new HashSet<>();
    int fromTotal = 0;
    int toTotal = 0;
    for (SectorPos s : g.sectors()) {
      for (Region r : g.regionsIn(s)) {
        List<RegionLink> out = g.linksFrom(r.id());
        for (RegionLink l : out) {
          assertEquals(r.id(), l.from());
        }
        for (RegionLink l : g.linksTo(r.id())) {
          assertEquals(r.id(), l.to());
        }
        actual.addAll(out);
        fromTotal += out.size();
        toTotal += g.linksTo(r.id()).size();
      }
    }
    assertEquals(expected, actual);
    assertEquals(expected.size(), g.linkCount());
    assertEquals(g.linkCount(), fromTotal);
    assertEquals(g.linkCount(), toTotal);
  }

  /** Random fixture text: layers y=0..layers-1 (all present so the Y range is stable). */
  static char[][][] randomCells(Random rnd, int width, int depth, int layers) {
    char[][][] cells = new char[layers][depth][width];
    for (int y = 0; y < layers; y++) {
      for (int z = 0; z < depth; z++) {
        for (int x = 0; x < width; x++) {
          cells[y][z][x] = randomCell(rnd, y);
        }
      }
    }
    return cells;
  }

  static char randomCell(Random rnd, int y) {
    double v = rnd.nextDouble();
    double solid = y == 0 ? 0.95 : 0.15;
    if (v < solid) {
      return '#';
    }
    return v < solid + 0.03 ? '~' : '.';
  }

  static String toFixture(char[][][] cells) {
    StringBuilder sb = new StringBuilder();
    for (int y = 0; y < cells.length; y++) {
      sb.append("y=").append(y).append('\n');
      for (char[] row : cells[y]) {
        sb.append(row).append('\n');
      }
    }
    return sb.toString();
  }
}
