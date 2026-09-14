package dev.nelsongx.nav.core.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.PathResult.Success;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.path.AStar;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphBuilder;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.core.simplify.LineOfSight;
import dev.nelsongx.nav.core.simplify.PathSimplifier;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouterTest {

  private static final FixtureWorld WORLD = HierarchyTestSupport.acceptanceWorld(false);
  private static final HierarchicalOptions OPTS = new HierarchicalOptions(20_000, 20_000, 1_000_000, 4);

  @Test
  void simplifiedHierarchicalRouteKeepsEndpointsAndClearSegments() {
    RegionGraph graph = RegionGraphBuilder.build(WORLD, new SectorPos(0, 0), new SectorPos(7, 7), 8);
    GridPos a = WORLD.marker('A');
    GridPos b = WORLD.marker('B');
    PathResult raw = HierarchicalPathfinder.findPath(WORLD, graph, a, b, OPTS);
    PathResult r = Router.route(WORLD, graph, a, b, OPTS);
    List<GridPos> pts = assertInstanceOf(Success.class, r, r::toString).points();
    assertEquals(raw.nodesExpanded(), r.nodesExpanded());
    assertEquals(a, pts.get(0));
    assertEquals(b, pts.get(pts.size() - 1));
    assertTrue(pts.size() < ((Success) raw).points().size());
    for (int i = 1; i < pts.size(); i++) {
      GridPos p = pts.get(i - 1);
      GridPos q = pts.get(i);
      assertTrue(LineOfSight.clear(WORLD, p, q), () -> "segment not clear: " + p + " -> " + q);
    }
  }

  @Test
  void nullGraphRoutesFlat() {
    GridPos a = WORLD.marker('A');
    GridPos b = WORLD.marker('B');
    Success flat = assertInstanceOf(Success.class, AStar.findPath(WORLD, a, b, OPTS.maxFlatNodes()));
    PathResult r = Router.route(WORLD, null, a, b, OPTS);
    assertEquals(new Success(PathSimplifier.simplify(WORLD, flat.points()), flat.nodesExpanded()), r);
  }

  @Test
  void failuresPassThrough() {
    GridPos a = WORLD.marker('A');
    GridPos c = WORLD.marker('C');
    assertEquals(AStar.findPath(WORLD, a, c, OPTS.maxFlatNodes()), Router.route(WORLD, null, a, c, OPTS));
  }
}
