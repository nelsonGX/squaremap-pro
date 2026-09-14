package dev.nelsongx.nav.fabric.graph;

import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.DIRECT;
import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.LOADED;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphBuilder;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.graph.GraphTestSupport.MaskedWorld;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class GraphUsePolicyTest {

  @Test
  void usesGraphOnlyWhenBoxFullyBuilt() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(1));
    RegionGraph full = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3), 16);
    int minY = w.minY();
    int maxY = w.maxY();
    assertSame(full, GraphUsePolicy.usableFor(full, new ChunkBox(0, 0, 3, 3), minY, maxY));
    assertSame(full, GraphUsePolicy.usableFor(full, new ChunkBox(1, 1, 2, 3), minY, maxY));
    assertNull(GraphUsePolicy.usableFor(full, new ChunkBox(0, 0, 4, 3), minY, maxY));
    assertNull(GraphUsePolicy.usableFor(full, new ChunkBox(-1, 0, 0, 0), minY, maxY));
    assertNull(GraphUsePolicy.usableFor(null, new ChunkBox(0, 0, 0, 0), minY, maxY));
    assertNull(GraphUsePolicy.usableFor(RegionGraph.empty(16, minY, maxY),
        new ChunkBox(0, 0, 0, 0), minY, maxY));
    assertNull(GraphUsePolicy.usableFor(full, new ChunkBox(0, 0, 1, 1), minY - 1, maxY),
        "Y range mismatch");
  }

  @Test
  void holeInsideBoxDisablesGraph() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(1));
    Set<SectorPos> loaded = new HashSet<>();
    for (int x = 0; x < 4; x++) {
      for (int z = 0; z < 4; z++) {
        loaded.add(new SectorPos(x, z));
      }
    }
    loaded.remove(new SectorPos(2, 2));
    RegionGraphManager m = new RegionGraphManager("t", w.minY(), w.maxY(), null, DIRECT, 0);
    ChunkBox all = new ChunkBox(0, 0, 3, 3);
    new BuildJob<>(m, all, BuildPlanner.plan(all),
        box -> CompletableFuture.completedFuture(new MaskedWorld(w, loaded)), LOADED, DIRECT, 0, null)
        .start().join();
    RegionGraph g = m.current();
    assertNull(GraphUsePolicy.usableFor(g, all, w.minY(), w.maxY()));
    assertSame(g, GraphUsePolicy.usableFor(g, new ChunkBox(0, 0, 1, 3), w.minY(), w.maxY()));
    assertNull(GraphUsePolicy.usableFor(RegionGraph.empty(8, w.minY(), w.maxY()),
        new ChunkBox(0, 0, 0, 0), w.minY(), w.maxY()), "sector size must be 16");
  }
}
