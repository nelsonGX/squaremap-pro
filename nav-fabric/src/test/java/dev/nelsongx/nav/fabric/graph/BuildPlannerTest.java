package dev.nelsongx.nav.fabric.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuildPlannerTest {

  private static void assertCoversExactlyOnce(ChunkBox area, List<BuildPlanner.Batch> batches,
      int batchSize) {
    Set<SectorPos> seen = new HashSet<>();
    int i = 0;
    for (BuildPlanner.Batch b : batches) {
      assertEquals(i++, b.index());
      assertTrue(b.chunks().chunkCount() <= batchSize, "batch too large: " + b);
      for (SectorPos s : b.sectors()) {
        assertTrue(s.sx() >= area.minChunkX() && s.sx() <= area.maxChunkX()
            && s.sz() >= area.minChunkZ() && s.sz() <= area.maxChunkZ(), "outside area: " + s);
        assertTrue(seen.add(s), "covered twice: " + s);
      }
    }
    assertEquals(area.chunkCount(), seen.size());
  }

  @Test
  void coversRectanglesExactlyOnce() {
    int[][] shapes = {{1, 1}, {1, 200}, {200, 1}, {129, 129}, {16, 16}, {7, 33}, {65, 3}, {128, 128}};
    for (int[] shape : shapes) {
      for (int batchSize : new int[] {1, 5, 64, 100}) {
        ChunkBox area = new ChunkBox(-40, 17, -40 + shape[0] - 1, 17 + shape[1] - 1);
        assertCoversExactlyOnce(area, BuildPlanner.plan(area, batchSize, 1), batchSize);
      }
    }
  }

  @Test
  void deterministicRowMajor() {
    ChunkBox area = new ChunkBox(0, 0, 9, 4);
    List<BuildPlanner.Batch> a = BuildPlanner.plan(area, 4, 1);
    assertEquals(a, BuildPlanner.plan(area, 4, 1));
    // width 10 >= 4: tiles are 4x1, row-major.
    assertEquals(new ChunkBox(0, 0, 3, 0), a.get(0).chunks());
    assertEquals(new ChunkBox(4, 0, 7, 0), a.get(1).chunks());
    assertEquals(new ChunkBox(8, 0, 9, 0), a.get(2).chunks());
    assertEquals(new ChunkBox(0, 1, 3, 1), a.get(3).chunks());
    assertEquals(15, a.size());
    assertEquals(List.of(new SectorPos(0, 0), new SectorPos(1, 0), new SectorPos(2, 0),
        new SectorPos(3, 0)), a.get(0).sectors());

    // narrow area: tiles are width x (batchSize / width)
    List<BuildPlanner.Batch> narrow = BuildPlanner.plan(new ChunkBox(5, 5, 6, 10), 64, 1);
    assertEquals(1, narrow.size());
    List<BuildPlanner.Batch> narrow4 = BuildPlanner.plan(new ChunkBox(5, 5, 6, 10), 4, 1);
    assertEquals(new ChunkBox(5, 5, 6, 6), narrow4.get(0).chunks());
    assertEquals(3, narrow4.size());
  }

  @Test
  void expansionByMargin() {
    ChunkBox area = new ChunkBox(-3, 2, 20, 9);
    for (BuildPlanner.Batch b : BuildPlanner.plan(area, 8, 1)) {
      ChunkBox c = b.chunks();
      assertEquals(new ChunkBox(c.minChunkX() - 1, c.minChunkZ() - 1, c.maxChunkX() + 1,
          c.maxChunkZ() + 1), b.prepareBox());
    }
    for (BuildPlanner.Batch b : BuildPlanner.plan(area)) {
      ChunkBox c = b.chunks();
      assertEquals(new ChunkBox(c.minChunkX() - 2, c.minChunkZ() - 2, c.maxChunkX() + 2,
          c.maxChunkZ() + 2), b.prepareBox());
      assertTrue(b.prepareBox().chunkCount() <= 4096);
    }
  }

  @Test
  void maxJobsStayWithinSnapshotLimit() {
    for (ChunkBox area : List.of(new ChunkBox(0, 0, 16383, 0), new ChunkBox(0, 0, 0, 16383),
        new ChunkBox(-64, -64, 64, 64), new ChunkBox(0, 0, 127, 127))) {
      for (BuildPlanner.Batch b : BuildPlanner.plan(area)) {
        assertTrue(b.prepareBox().chunkCount() <= 4096, b.toString());
      }
    }
  }

  @Test
  void rejectsInvalid() {
    assertThrows(IllegalArgumentException.class,
        () -> BuildPlanner.plan(new ChunkBox(1, 0, 0, 0), 4, 1));
    assertThrows(IllegalArgumentException.class,
        () -> BuildPlanner.plan(new ChunkBox(0, 0, 0, 0), 0, 1));
    assertThrows(IllegalArgumentException.class,
        () -> BuildPlanner.plan(new ChunkBox(0, 0, 0, 0), 4, -1));
  }
}
