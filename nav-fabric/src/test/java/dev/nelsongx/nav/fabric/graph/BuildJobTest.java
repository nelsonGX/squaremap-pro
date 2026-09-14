package dev.nelsongx.nav.fabric.graph;

import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.DIRECT;
import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.LOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphBuilder;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.graph.GraphTestSupport.MaskedWorld;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BuildJobTest {

  private static final ChunkBox ALL = new ChunkBox(0, 0, 3, 3);

  private static Set<SectorPos> allSectors() {
    Set<SectorPos> s = new HashSet<>();
    for (int x = 0; x < 4; x++) {
      for (int z = 0; z < 4; z++) {
        s.add(new SectorPos(x, z));
      }
    }
    return s;
  }

  private static RegionGraphManager manager(FixtureWorld w) {
    return new RegionGraphManager("test", w.minY(), w.maxY(), null, DIRECT, 0);
  }

  private static BuildJob<MaskedWorld> job(RegionGraphManager m, MaskedWorld view, int batchSize,
      int margin, List<ChunkBox> prepared) {
    return new BuildJob<>(m, ALL, BuildPlanner.plan(ALL, batchSize, margin), box -> {
      prepared.add(box);
      return CompletableFuture.completedFuture(view);
    }, LOADED, DIRECT, 0, null);
  }

  @Test
  void allLoadedEqualsFullBuild() {
    for (long seed = 1; seed <= 3; seed++) {
      FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(seed));
      MaskedWorld view = new MaskedWorld(w, allSectors());
      for (int batchSize : new int[] {1, 3, 5, 64}) {
        RegionGraphManager m = manager(w);
        List<ChunkBox> prepared = new ArrayList<>();
        BuildJob<MaskedWorld> job = job(m, view, batchSize, BuildPlanner.DEFAULT_MARGIN, prepared);
        BuildJob.Progress p = job.start().join();
        RegionGraph expected = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3),
            RegionGraphManager.SECTOR_SIZE);
        assertTrue(expected.regionCount() > 16 && expected.linkCount() > 0, "fixture is non-trivial");
        assertEquals(expected, m.current(), "seed " + seed + " batchSize " + batchSize);
        assertEquals(16, p.done());
        assertEquals(0, p.skipped());
        assertEquals(16, p.total());
        assertTrue(p.complete());
        assertEquals(BuildPlanner.plan(ALL, batchSize, 2).stream().map(BuildPlanner.Batch::prepareBox)
            .toList(), prepared);
      }
    }
  }

  /** Like a SnapshotCache view: only chunks inside the prepared box (and loaded) are present. */
  private static MaskedWorld boxView(FixtureWorld w, ChunkBox box, Set<SectorPos> loaded) {
    Set<SectorPos> in = new HashSet<>();
    for (int x = box.minChunkX(); x <= box.maxChunkX(); x++) {
      for (int z = box.minChunkZ(); z <= box.maxChunkZ(); z++) {
        in.add(new SectorPos(x, z));
      }
    }
    in.retainAll(loaded);
    return new MaskedWorld(w, in);
  }

  @Test
  void boxLimitedViewsNeedMarginTwo() {
    for (long seed = 1; seed <= 5; seed++) {
      FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(seed));
      RegionGraph expected = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3),
          16);
      for (int batchSize : new int[] {1, 2, 4, 64}) {
        RegionGraphManager m = manager(w);
        BuildJob.Progress p = new BuildJob<>(m, ALL, BuildPlanner.plan(ALL, batchSize, 2),
            box -> CompletableFuture.completedFuture(boxView(w, box, allSectors())), LOADED, DIRECT,
            0, null).start().join();
        assertEquals(expected, m.current(), "seed " + seed + " batchSize " + batchSize);
        assertEquals(16, p.done());
      }
      // With a 1-chunk margin, built sectors two chunks away are missing from the view, so the
      // rebuild safety rule defers sectors instead of silently dropping links.
      RegionGraphManager m1 = manager(w);
      BuildJob.Progress p1 = new BuildJob<>(m1, ALL, BuildPlanner.plan(ALL, 1, 1),
          box -> CompletableFuture.completedFuture(boxView(w, box, allSectors())), LOADED, DIRECT, 0,
          null).start().join();
      assertTrue(p1.skipped() > 0, "margin 1 must not claim a complete build");
      assertEquals(RegionGraph.empty(16, w.minY(), w.maxY()).withSectorsRebuilt(w,
          m1.current().sectors()), m1.current(),
          "whatever was built with margin 1 is still consistent");
    }
  }

  @Test
  void unloadedChunksAreSkippedNotBuilt() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(7));
    Set<SectorPos> loaded = allSectors();
    loaded.remove(new SectorPos(1, 2));
    loaded.remove(new SectorPos(3, 0));
    loaded.remove(new SectorPos(2, 2));
    MaskedWorld view = new MaskedWorld(w, loaded);
    for (int batchSize : new int[] {1, 4, 64}) {
      RegionGraphManager m = manager(w);
      BuildJob.Progress p = job(m, view, batchSize, BuildPlanner.DEFAULT_MARGIN, new ArrayList<>())
          .start().join();
      RegionGraph expected = RegionGraph.empty(16, w.minY(), w.maxY())
          .withSectorsRebuilt(view, loaded);
      assertEquals(expected, m.current(), "batchSize " + batchSize);
      assertEquals(loaded, m.current().sectors());
      assertEquals(13, p.done());
      assertEquals(3, p.skipped());
      assertEquals(16, p.total());
    }
  }

  @Test
  void laterLoadCompletesTheGraph() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(11));
    Set<SectorPos> loaded = allSectors();
    loaded.remove(new SectorPos(1, 1));
    RegionGraphManager m = manager(w);
    job(m, new MaskedWorld(w, loaded), 4, 2, new ArrayList<>()).start().join();
    BuildJob.Progress p = job(m, new MaskedWorld(w, allSectors()), 4, 2, new ArrayList<>())
        .start().join();
    assertEquals(16, p.done());
    assertEquals(RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3), 16),
        m.current());
  }

  @Test
  void cancelStopsFurtherBatches() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(3));
    MaskedWorld view = new MaskedWorld(w, allSectors());
    RegionGraphManager m = manager(w);
    AtomicInteger prepares = new AtomicInteger();
    AtomicReference<BuildJob<MaskedWorld>> ref = new AtomicReference<>();
    BuildJob<MaskedWorld> job = new BuildJob<>(m, ALL, BuildPlanner.plan(ALL, 4, 2), box -> {
      prepares.incrementAndGet();
      return CompletableFuture.completedFuture(view);
    }, LOADED, DIRECT, 0, p -> ref.get().cancel());
    ref.set(job);
    BuildJob.Progress p = job.start().join();
    assertEquals(1, prepares.get());
    assertTrue(job.cancelled());
    assertFalse(p.complete());
    assertEquals(1, p.batchesDone());
    assertEquals(4, p.done());
    assertEquals(4, m.current().sectors().size());
  }

  @Test
  void runsAsynchronouslyWithDelayOneBatchAtATime() throws Exception {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(5));
    MaskedWorld view = new MaskedWorld(w, allSectors());
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      RegionGraphManager m = new RegionGraphManager("t", w.minY(), w.maxY(), null, pool, 0);
      AtomicInteger inFlight = new AtomicInteger();
      AtomicInteger maxInFlight = new AtomicInteger();
      BuildJob<MaskedWorld> job = new BuildJob<>(m, ALL, BuildPlanner.plan(ALL, 2, 2), box -> {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        return CompletableFuture.supplyAsync(() -> view, pool);
      }, LOADED, pool, 5, p -> inFlight.decrementAndGet());
      BuildJob.Progress p = job.start().get(30, TimeUnit.SECONDS);
      assertEquals(1, maxInFlight.get());
      assertEquals(8, p.batchesDone());
      assertEquals(RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3), 16),
          m.current());
    } finally {
      pool.shutdownNow();
    }
  }
}
