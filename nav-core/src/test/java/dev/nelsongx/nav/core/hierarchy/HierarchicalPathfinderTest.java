package dev.nelsongx.nav.core.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.PathResult.Failure;
import dev.nelsongx.nav.core.PathResult.FailureReason;
import dev.nelsongx.nav.core.PathResult.Success;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.path.AStar;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphBuilder;
import dev.nelsongx.nav.core.region.SectorPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HierarchicalPathfinderTest {

  private static final HierarchicalOptions COARSE =
      new HierarchicalOptions(20_000, 20_000, 1_000_000, 4);
  private static final int FLAT_CAP = 1_000_000;

  private static final FixtureWorld WORLD = HierarchyTestSupport.acceptanceWorld(false);
  private static final RegionGraph GRAPH = buildAll(WORLD);

  private static RegionGraph buildAll(FixtureWorld w) {
    int sectors = HierarchyTestSupport.SIZE / HierarchyTestSupport.SECTOR;
    return RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(sectors - 1, sectors - 1),
        HierarchyTestSupport.SECTOR);
  }

  private static Success success(PathResult r) {
    return assertInstanceOf(Success.class, r, () -> "expected success but got " + r);
  }

  private static Failure failure(PathResult r, FailureReason reason) {
    Failure f = assertInstanceOf(Failure.class, r, () -> "expected failure but got " + r);
    assertEquals(reason, f.reason(), f::detail);
    return f;
  }

  @Test
  void optionsValidate() {
    assertEquals(new HierarchicalOptions(20_000, 20_000, 50_000, 48), HierarchicalOptions.defaults());
    assertThrows(IllegalArgumentException.class, () -> new HierarchicalOptions(0, 1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new HierarchicalOptions(1, 0, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> new HierarchicalOptions(1, 1, 0, 1));
    assertThrows(IllegalArgumentException.class, () -> new HierarchicalOptions(1, 1, 1, -1));
  }

  @Test
  void acceptanceLongRouteMatchesEndpointsAndIsNearOptimal() {
    GridPos a = WORLD.marker('A');
    GridPos b = WORLD.marker('B');
    Success flat = success(AStar.findPath(WORLD, a, b, FLAT_CAP));
    Success hier = success(HierarchicalPathfinder.findPath(WORLD, GRAPH, a, b, COARSE));

    assertEquals(flat.points().get(0), hier.points().get(0));
    assertEquals(flat.points().get(flat.points().size() - 1),
        hier.points().get(hier.points().size() - 1));
    assertEquals(a, hier.points().get(0));
    assertEquals(b, hier.points().get(hier.points().size() - 1));

    double flatCost = AStar.pathCost(WORLD, flat.points());
    double hierCost = AStar.pathCost(WORLD, hier.points());
    System.out.printf("acceptance A->B: flat cost=%.3f nodes=%d | hierarchical cost=%.3f nodes=%d"
            + " (regions=%d links=%d)%n", flatCost, flat.nodesExpanded(), hierCost,
        hier.nodesExpanded(), GRAPH.regionCount(), GRAPH.linkCount());
    assertTrue(hierCost <= 1.5 * flatCost, () -> hierCost + " > 1.5 * " + flatCost);
    // The route must actually use the bridge (only way across the moat).
    assertTrue(hier.points().stream().anyMatch(p -> p.y() == 2));
  }

  @Test
  void enclosedGoalIsRejectedWithFewerExpansionsThanFlat() {
    GridPos a = WORLD.marker('A');
    GridPos c = WORLD.marker('C');
    Failure flat = failure(AStar.findPath(WORLD, a, c, FLAT_CAP), FailureReason.NO_PATH);
    Failure hier = failure(HierarchicalPathfinder.findPath(WORLD, GRAPH, a, c, COARSE),
        FailureReason.NO_PATH);
    System.out.printf("unreachable A->C: flat nodes=%d | hierarchical nodes=%d%n",
        flat.nodesExpanded(), hier.nodesExpanded());
    assertTrue(hier.nodesExpanded() < flat.nodesExpanded());
  }

  @Test
  void nearQueryIsIdenticalToFlat() {
    GridPos s = new GridPos(58, 1, 2);
    GridPos g = new GridPos(62, 1, 5);
    assertEquals(AStar.findPath(WORLD, s, g, COARSE.maxFlatNodes()),
        HierarchicalPathfinder.findPath(WORLD, GRAPH, s, g, COARSE));
  }

  @Test
  void sameRegionQueryIsIdenticalToFlat() {
    HierarchicalOptions noNear = new HierarchicalOptions(20_000, 20_000, 50_000, 0);
    GridPos s = new GridPos(56, 1, 0);
    GridPos g = new GridPos(63, 1, 7); // same open 8x8 sector => same region
    assertEquals(GRAPH.locate(WORLD, s), GRAPH.locate(WORLD, g));
    assertEquals(AStar.findPath(WORLD, s, g, noNear.maxFlatNodes()),
        HierarchicalPathfinder.findPath(WORLD, GRAPH, s, g, noNear));
  }

  @Test
  void invalidEndpoint() {
    GridPos a = WORLD.marker('A');
    Failure f = failure(HierarchicalPathfinder.findPath(WORLD, GRAPH, a, new GridPos(24, 1, 0),
        COARSE), FailureReason.INVALID_ENDPOINT);
    assertEquals(0, f.nodesExpanded());
    failure(HierarchicalPathfinder.findPath(WORLD, GRAPH, new GridPos(3, 5, 3), a, COARSE),
        FailureReason.INVALID_ENDPOINT);
  }

  @Test
  void endpointInUnbuiltSectorFallsBackToFlat() {
    RegionGraph partial = RegionGraphBuilder.build(WORLD, new SectorPos(0, 0), new SectorPos(3, 7),
        HierarchyTestSupport.SECTOR);
    GridPos a = WORLD.marker('A'); // x=60 => sector 7, not built
    GridPos b = new GridPos(2, 1, 60);
    PathResult flat = AStar.findPath(WORLD, a, b, COARSE.maxFlatNodes());
    success(flat);
    assertEquals(flat, HierarchicalPathfinder.findPath(WORLD, partial, a, b, COARSE));
    assertEquals(AStar.findPath(WORLD, b, a, COARSE.maxFlatNodes()),
        HierarchicalPathfinder.findPath(WORLD, partial, b, a, COARSE));
  }

  @Test
  void staleGraphFallsBackToFlatAndStaysLegal() {
    FixtureWorld changed = HierarchyTestSupport.acceptanceWorld(true);
    GridPos a = WORLD.marker('A');
    GridPos b = WORLD.marker('B');
    // Sanity: on the original world the coarse route goes through the (now closed) z=30..31 gap.
    Success original = success(HierarchicalPathfinder.findPath(WORLD, GRAPH, a, b, COARSE));
    assertTrue(original.points().contains(new GridPos(24, 1, 30))
        || original.points().contains(new GridPos(24, 1, 31)));

    Success flat = success(AStar.findPath(changed, a, b, COARSE.maxFlatNodes()));
    Success stale = success(HierarchicalPathfinder.findPath(changed, GRAPH, a, b, COARSE));
    AStar.pathCost(changed, stale.points());
    assertEquals(a, stale.points().get(0));
    assertEquals(b, stale.points().get(stale.points().size() - 1));
    // The fallback's points are the flat path; its effort includes the abandoned hierarchical work.
    assertEquals(flat.points(), stale.points());
    assertTrue(stale.nodesExpanded() > flat.nodesExpanded());
  }

  @Test
  void coarseCapExceeded() {
    HierarchicalOptions tiny = new HierarchicalOptions(1, 20_000, 50_000, 4);
    Failure f = failure(HierarchicalPathfinder.findPath(WORLD, GRAPH, WORLD.marker('A'),
        WORLD.marker('B'), tiny), FailureReason.CAP_EXCEEDED);
    assertEquals(1, f.nodesExpanded());
  }

  @Test
  void segmentCapExceeded() {
    HierarchicalOptions tiny = new HierarchicalOptions(20_000, 1, 50_000, 4);
    failure(HierarchicalPathfinder.findPath(WORLD, GRAPH, WORLD.marker('A'), WORLD.marker('B'), tiny),
        FailureReason.CAP_EXCEEDED);
  }

  @Test
  void deterministic() {
    assertEquals(HierarchicalPathfinder.findPath(WORLD, GRAPH, WORLD.marker('A'), WORLD.marker('B'),
            COARSE),
        HierarchicalPathfinder.findPath(WORLD, GRAPH, WORLD.marker('A'), WORLD.marker('B'), COARSE));
  }

  @Test
  void randomWorldsAgreeWithFlatOnReachability() {
    Random rnd = new Random(0x5eed_6L);
    HierarchicalOptions opts = new HierarchicalOptions(1_000_000, 1_000_000, 1_000_000, 0);
    int successes = 0;
    int noPaths = 0;
    for (int world = 0; world < 25; world++) {
      int size = 24;
      char[][][] cells = new char[3][size][size];
      for (int y = 0; y < 3; y++) {
        for (int z = 0; z < size; z++) {
          for (int x = 0; x < size; x++) {
            double v = rnd.nextDouble();
            double solid = y == 0 ? 0.95 : 0.2;
            cells[y][z][x] = v < solid ? '#' : v < solid + 0.03 ? '~' : '.';
          }
        }
      }
      FixtureWorld w = FixtureWorld.parse(HierarchyTestSupport.toFixture(cells));
      RegionGraph g = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(5, 5), 4);
      List<GridPos> walkable = walkable(w, size);
      for (int q = 0; q < 20; q++) {
        GridPos s = walkable.get(rnd.nextInt(walkable.size()));
        GridPos t = walkable.get(rnd.nextInt(walkable.size()));
        PathResult flat = AStar.findPath(w, s, t, 1_000_000);
        PathResult hier = HierarchicalPathfinder.findPath(w, g, s, t, opts);
        String ctx = "world " + world + " " + s + " -> " + t + ": flat=" + flat + " hier=" + hier;
        if (flat instanceof Success fs) {
          Success hs = assertInstanceOf(Success.class, hier, ctx);
          assertEquals(fs.points().get(0), hs.points().get(0), ctx);
          assertEquals(fs.points().get(fs.points().size() - 1),
              hs.points().get(hs.points().size() - 1), ctx);
          double hc = AStar.pathCost(w, hs.points());
          assertTrue(hc + 1e-9 >= AStar.pathCost(w, fs.points()), ctx);
          successes++;
        } else {
          Failure ff = (Failure) flat;
          assertEquals(FailureReason.NO_PATH, ff.reason(), ctx);
          Failure hf = assertInstanceOf(Failure.class, hier, ctx);
          assertEquals(FailureReason.NO_PATH, hf.reason(), ctx);
          noPaths++;
        }
      }
    }
    System.out.printf("random: %d successes, %d no-path queries%n", successes, noPaths);
    assertTrue(successes > 100 && noPaths > 20, successes + " / " + noPaths);
  }

  private static List<GridPos> walkable(WorldView w, int size) {
    List<GridPos> out = new ArrayList<>();
    for (int y = w.minY(); y <= w.maxY(); y++) {
      for (int z = 0; z < size; z++) {
        for (int x = 0; x < size; x++) {
          if (w.walkable(x, y, z)) {
            out.add(new GridPos(x, y, z));
          }
        }
      }
    }
    return out;
  }
}
