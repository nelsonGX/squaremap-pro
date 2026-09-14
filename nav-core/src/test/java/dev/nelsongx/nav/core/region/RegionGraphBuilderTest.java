package dev.nelsongx.nav.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.path.MovementModel;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class RegionGraphBuilderTest {

  private static final SectorPos S00 = new SectorPos(0, 0);

  private static RegionGraph buildSingle(FixtureWorld w, int size) {
    return RegionGraphBuilder.build(w, S00, S00, size);
  }

  private static RegionId locate(RegionGraph g, WorldView w, int x, int y, int z) {
    return g.locate(w, new GridPos(x, y, z)).orElseThrow(
        () -> new AssertionError("not located: " + x + "," + y + "," + z));
  }

  @Test
  void sectorPosUsesFloorDiv() {
    assertEquals(new SectorPos(0, 0), SectorPos.of(0, 15, 16));
    assertEquals(new SectorPos(-1, 1), SectorPos.of(-1, 16, 16));
    assertEquals(new SectorPos(-2, -1), SectorPos.of(-17, -16, 16));
    assertThrows(IllegalArgumentException.class, () -> SectorPos.of(0, 0, 1));
    assertTrue(new SectorPos(-1, 0).contains(-4, 3, 4));
    assertFalse(new SectorPos(-1, 0).contains(0, 3, 4));
  }

  @Test
  void regionCenterIsBoundsMidpoint() {
    assertThrows(IllegalArgumentException.class,
        () -> new Region(new RegionId(0, 0, 0), new GridPos(9, 1, 0), 1, 0, 1, 0, 3, 1, 0));
    Region r = new Region(new RegionId(0, 0, 0), new GridPos(-3, 1, 0), 2, -3, 1, 0, 0, 4, 1);
    assertEquals(new GridPos(-2, 2, 0), r.center());
  }

  @Test
  void wallSplitsSectorIntoTwoRegionsAndOpeningMergesThem() {
    String closed = """
        y=0
        ####
        ####
        ####
        ####
        y=1
        ..#.
        ..#.
        ..#.
        ..#.
        y=2
        ..~.
        ..~.
        ..~.
        ..~.
        """;
    FixtureWorld w = FixtureWorld.parse(closed);
    RegionGraph g = buildSingle(w, 4);
    assertEquals(2, g.regionCount());
    List<Region> regions = g.regionsIn(S00);
    assertEquals(8, regions.get(0).nodeCount());
    assertEquals(new GridPos(0, 1, 0), regions.get(0).seed());
    assertEquals(4, regions.get(1).nodeCount());
    assertEquals(new GridPos(3, 1, 0), regions.get(1).seed());
    assertEquals(0, g.linkCount());
    RegionTestSupport.assertMatchesBruteForce(w, g);

    String open = closed.replace("y=1\n..#.", "y=1\n....").replace("y=2\n..~.", "y=2\n....");
    FixtureWorld w2 = FixtureWorld.parse(open);
    RegionGraph g2 = buildSingle(w2, 4);
    assertEquals(1, g2.regionCount());
    assertEquals(13, g2.regionsIn(S00).get(0).nodeCount());
    RegionTestSupport.assertMatchesBruteForce(w2, g2);
  }

  @Test
  void bridgeOverFloorIsSeparateRegionWithOneWayIntraSectorLinks() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ####
        ####
        ####
        ####
        y=3
        ....
        ####
        ....
        ....
        """);
    RegionGraph g = buildSingle(w, 4);
    assertEquals(2, g.regionCount());
    RegionId floor = locate(g, w, 0, 1, 1);
    RegionId bridge = locate(g, w, 0, 4, 1);
    assertNotEquals(floor, bridge);
    assertEquals(new RegionId(0, 0, 0), floor);
    assertEquals(new RegionId(0, 0, 1), bridge);
    assertEquals(16, g.region(floor).orElseThrow().nodeCount());
    assertEquals(4, g.region(bridge).orElseThrow().nodeCount());
    assertTrue(g.linksFrom(floor).isEmpty(), "cannot climb onto the bridge");
    assertFalse(g.linksFrom(bridge).isEmpty(), "can drop off the bridge");
    for (RegionLink l : g.linksFrom(bridge)) {
      assertEquals(floor, l.to());
      assertEquals(4, l.fromPos().y());
      assertEquals(1, l.toPos().y());
      assertEquals(MovementModel.moveCost(true, -3), l.cost(), 1e-12);
    }
    assertEquals(g.linksFrom(bridge), g.linksTo(floor));
    RegionTestSupport.assertMatchesBruteForce(w, g);
  }

  @Test
  void oneWayDropAcrossSectorBoundary() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ....####
        y=3
        ####....
        """);
    RegionGraph g = RegionGraphBuilder.build(w, S00, new SectorPos(1, 0), 4);
    RegionId a = locate(g, w, 3, 4, 0);
    RegionId b = locate(g, w, 4, 1, 0);
    assertEquals(new SectorPos(0, 0), a.sector());
    assertEquals(new SectorPos(1, 0), b.sector());
    assertEquals(List.of(new RegionLink(a, b, new GridPos(3, 4, 0), new GridPos(4, 1, 0),
        MovementModel.moveCost(true, -3))), g.linksFrom(a));
    assertTrue(g.linksFrom(b).isEmpty());
    assertTrue(g.linksTo(a).isEmpty());
    assertEquals(1, g.linkCount());
  }

  @Test
  void corridorAcrossSectorBoundaryLinksBothWaysWithMovementCosts() {
    // Step up by one at the boundary: 3 -> 4 climbs, 4 -> 3 drops.
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ########
        y=1
        ....####
        """);
    RegionGraph g = RegionGraphBuilder.build(w, S00, new SectorPos(1, 0), 4);
    RegionId a = locate(g, w, 3, 1, 0);
    RegionId b = locate(g, w, 4, 2, 0);
    assertEquals(List.of(new RegionLink(a, b, new GridPos(3, 1, 0), new GridPos(4, 2, 0),
        MovementModel.moveCost(true, 1))), g.linksFrom(a));
    assertEquals(List.of(new RegionLink(b, a, new GridPos(4, 2, 0), new GridPos(3, 1, 0),
        MovementModel.moveCost(true, -1))), g.linksFrom(b));
    assertEquals(1.5, g.linksFrom(a).get(0).cost(), 1e-12);
    assertEquals(1.25, g.linksFrom(b).get(0).cost(), 1e-12);

    // Flat 3-wide corridor: cardinal and diagonal crossings, all symmetric.
    FixtureWorld flat = FixtureWorld.parse("""
        y=0
        ########
        ########
        ########
        """);
    RegionGraph gf = RegionGraphBuilder.build(flat, S00, new SectorPos(1, 0), 4);
    RegionId left = new RegionId(0, 0, 0);
    RegionId right = new RegionId(1, 0, 0);
    assertEquals(2, gf.regionCount());
    // 3 cardinal + 4 diagonal crossings each way.
    assertEquals(7, gf.linksFrom(left).size());
    assertEquals(7, gf.linksFrom(right).size());
    for (RegionLink l : gf.linksFrom(left)) {
      boolean cardinal = l.fromPos().z() == l.toPos().z();
      assertEquals(MovementModel.moveCost(cardinal, 0), l.cost(), 1e-12);
    }
    RegionTestSupport.assertMatchesBruteForce(flat, gf);
  }

  @Test
  void deterministicIndicesAndMinimalSeeds() {
    FixtureWorld w = FixtureWorld.parse(RegionTestSupport.ACCEPTANCE);
    RegionGraph g1 = RegionGraphBuilder.build(w, S00, new SectorPos(2, 2), 4);
    RegionGraph g2 = RegionGraphBuilder.build(w, S00, new SectorPos(2, 2), 4);
    assertEquals(g1, g2);
    assertEquals(g1.hashCode(), g2.hashCode());
    RegionTestSupport.assertMatchesBruteForce(w, g1);
  }

  @Test
  void acceptanceFixtureStructure() {
    FixtureWorld w = FixtureWorld.parse(RegionTestSupport.ACCEPTANCE);
    RegionGraph g = RegionGraphBuilder.build(w, S00, new SectorPos(2, 2), 4);
    assertEquals(9, g.sectors().size());
    assertEquals(2, g.regionsIn(new SectorPos(0, 0)).size(), "wall splits (0,0)");
    assertEquals(2, g.regionsIn(new SectorPos(1, 0)).size(), "floor + bridge");
    assertTrue(g.regionsIn(new SectorPos(2, 0)).isEmpty(), "empty sector");
    assertTrue(g.sectors().contains(new SectorPos(2, 0)));
    assertEquals(2, g.regionsIn(new SectorPos(1, 1)).size(), "floor + platform");
    // Platform drops into (0,1) one way.
    RegionId platform = locate(g, w, 4, 4, 5);
    RegionId west = locate(g, w, 3, 1, 5);
    assertTrue(g.linksFrom(platform).stream().anyMatch(l -> l.to().equals(west)));
    assertTrue(g.linksFrom(west).stream().noneMatch(l -> l.to().equals(platform)));
  }

  @Test
  void locateFloorVsBridgeAndMisses() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ########
        ########
        ########
        ########
        y=3
        ........
        ####....
        ........
        ........
        """);
    RegionGraph g = buildSingle(w, 4); // only sector (0,0) built
    RegionId floor = locate(g, w, 1, 1, 1);
    RegionId bridge = locate(g, w, 1, 4, 1);
    assertNotEquals(floor, bridge);
    assertEquals(floor, locate(g, w, 3, 1, 3));
    assertEquals(bridge, locate(g, w, 3, 4, 1));
    assertEquals(Optional.empty(), g.locate(w, new GridPos(1, 2, 1)), "not walkable");
    assertEquals(Optional.empty(), g.locate(w, new GridPos(1, 3, 1)), "inside bridge");
    assertEquals(Optional.empty(), g.locate(w, new GridPos(5, 1, 1)), "unbuilt sector");
    assertEquals(Optional.empty(), g.locate(w, new GridPos(1, 1, -1)), "outside world");
  }

  @Test
  void emptyGraphAndValidation() {
    RegionGraph e = RegionGraph.empty(16, 0, 10);
    assertEquals(0, e.regionCount());
    assertEquals(0, e.linkCount());
    assertTrue(e.sectors().isEmpty());
    assertTrue(e.regionsIn(S00).isEmpty());
    assertTrue(e.linksFrom(new RegionId(0, 0, 0)).isEmpty());
    assertEquals(Optional.empty(), e.region(new RegionId(0, 0, 0)));
    assertThrows(IllegalArgumentException.class, () -> RegionGraph.empty(1, 0, 10));
    FixtureWorld w = FixtureWorld.parse("y=0\n####\n");
    assertThrows(IllegalArgumentException.class,
        () -> RegionGraphBuilder.build(w, new SectorPos(1, 0), S00, 4));
    assertThrows(IllegalArgumentException.class, () -> e.withSectorsRebuilt(w, List.of(S00)),
        "Y range mismatch");
    assertTrue(buildSingle(w, 4).toString().contains("regions=1"));
  }

  /** Layered world: a slab every 5 blocks with a scattering of holes, plus a column of stairs. */
  private static final class TallWorld implements WorldView {
    @Override
    public int minY() {
      return 0;
    }

    @Override
    public int maxY() {
      return 320;
    }

    private boolean solid(int x, int y, int z) {
      if (y < 0 || y > 320) {
        return false;
      }
      if (y % 5 == 0) {
        return Math.floorMod(x * 7 + z * 13 + y, 11) != 0;
      }
      return Math.floorMod(x, 16) == 3 && Math.floorMod(z + y, 16) == 0; // stair-ish pillars
    }

    @Override
    public boolean passable(int x, int y, int z) {
      return y >= 0 && y <= 320 && !solid(x, y, z);
    }

    @Override
    public boolean walkable(int x, int y, int z) {
      return solid(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
    }

    @Override
    public OptionalInt groundY(int x, int z) {
      for (int y = 320; y >= 0; y--) {
        if (walkable(x, y, z)) {
          return OptionalInt.of(y);
        }
      }
      return OptionalInt.empty();
    }
  }

  @Test
  void tallSixteenBlockSectorsBuild() {
    WorldView w = new TallWorld();
    RegionGraph g = RegionGraphBuilder.build(w, new SectorPos(-1, -1), S00, 16);
    assertEquals(4, g.sectors().size());
    assertTrue(g.regionCount() >= 4 * 60, "at least one region per slab per sector: " + g);
    assertTrue(g.linkCount() > 0);
    int nodes = g.regionsIn(S00).stream().mapToInt(Region::nodeCount).sum();
    assertTrue(nodes > 10_000, "nodes=" + nodes);
  }
}
