package dev.nelsongx.nav.fabric.graph;

import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.DIRECT;
import static dev.nelsongx.nav.fabric.graph.GraphTestSupport.LOADED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.RegionGraphBuilder;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.graph.GraphTestSupport.MaskedWorld;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirtyRebuildTest {

  private static final ChunkBox ALL = new ChunkBox(0, 0, 3, 3);
  private static final SectorPos MIN = new SectorPos(0, 0);
  private static final SectorPos MAX = new SectorPos(3, 3);

  private static Set<SectorPos> all() {
    Set<SectorPos> s = new HashSet<>();
    for (int x = 0; x < 4; x++) {
      for (int z = 0; z < 4; z++) {
        s.add(new SectorPos(x, z));
      }
    }
    return s;
  }

  private static RegionGraphManager built(FixtureWorld w) {
    RegionGraphManager m = new RegionGraphManager("t", w.minY(), w.maxY(), null, DIRECT, 0);
    new BuildJob<>(m, ALL, BuildPlanner.plan(ALL),
        box -> CompletableFuture.completedFuture(new MaskedWorld(w, all())), LOADED, DIRECT, 0, null)
        .start().join();
    return m;
  }

  @Test
  void dirtyRebuildEqualsFullBuildOfModifiedWorld() {
    String text = GraphTestSupport.worldText(21);
    FixtureWorld before = FixtureWorld.parse(text);
    // Remove the floor across the border of chunks (1,1)/(2,1).
    FixtureWorld after = GraphTestSupport.modified(text, 0, 28, 20, 36, 26, '.');
    RegionGraphManager m = built(before);
    RegionGraph expected = RegionGraphBuilder.build(after, MIN, MAX, 16);
    assertNotEquals(expected, m.current(), "modification must change the graph");

    long t = System.nanoTime();
    for (SectorPos s : List.of(new SectorPos(1, 1), new SectorPos(2, 1))) {
      assertTrue(m.acceptsChange(s.sx(), s.sz()));
      m.markChanged(s, t);
    }
    assertEquals(2, m.dirtyCount());
    List<ChunkBox> prepared = new ArrayList<>();
    m.rebuildDirty(box -> {
      prepared.add(box);
      return CompletableFuture.completedFuture(new MaskedWorld(after, all()));
    }, LOADED).join();
    assertEquals(expected, m.current());
    assertEquals(0, m.dirtyCount());
    assertEquals(List.of(new ChunkBox(-1, -1, 4, 3)), prepared);
  }

  @Test
  void changesOutsideTheGraphAreIgnored() {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(2));
    RegionGraphManager m = built(w);
    assertFalse(m.acceptsChange(10, 10));
    assertTrue(m.acceptsChange(3, 3));
    m.setActiveJobArea(new ChunkBox(10, 10, 12, 12));
    assertTrue(m.acceptsChange(11, 10));
  }

  @Test
  void changeAfterPreparationIsRebuiltAgain() {
    String text = GraphTestSupport.worldText(9);
    FixtureWorld before = FixtureWorld.parse(text);
    FixtureWorld after = GraphTestSupport.modified(text, 0, 2, 2, 12, 12, '.');
    RegionGraphManager m = built(before);
    SectorPos s = new SectorPos(0, 0);
    m.markChanged(s, System.nanoTime());
    AtomicInteger rounds = new AtomicInteger();
    m.rebuildDirty(box -> {
      rounds.incrementAndGet();
      // The block changes again while the (stale) view is being prepared.
      m.markChanged(s, System.nanoTime());
      return CompletableFuture.completedFuture(new MaskedWorld(before, all()));
    }, LOADED).join();
    assertEquals(1, m.dirtyCount(), "sector must be dirty again");
    m.rebuildDirty(box -> CompletableFuture.completedFuture(new MaskedWorld(after, all())), LOADED)
        .join();
    assertEquals(0, m.dirtyCount());
    assertEquals(RegionGraphBuilder.build(after, MIN, MAX, 16), m.current());
  }

  @Test
  void unloadedNeighbourDefersUntilChunkLoads() {
    String text = GraphTestSupport.worldText(13);
    FixtureWorld before = FixtureWorld.parse(text);
    FixtureWorld after = GraphTestSupport.modified(text, 0, 20, 20, 26, 26, '.');
    RegionGraphManager m = built(before);
    RegionGraph original = m.current();
    Set<SectorPos> loaded = all();
    loaded.remove(new SectorPos(3, 3)); // built, within 2 sectors of (1,1)
    m.markChanged(new SectorPos(1, 1), System.nanoTime());
    m.rebuildDirty(box -> CompletableFuture.completedFuture(new MaskedWorld(after, loaded)), LOADED)
        .join();
    assertEquals(original, m.current(), "must not rebuild with a built neighbour unloaded");
    assertEquals(0, m.dirtyCount());
    assertEquals(1, m.waitingCount());
    m.onChunkLoaded(3, 3);
    assertEquals(1, m.dirtyCount());
    m.rebuildDirty(box -> CompletableFuture.completedFuture(new MaskedWorld(after, all())), LOADED)
        .join();
    assertEquals(RegionGraphBuilder.build(after, MIN, MAX, 16), m.current());
  }

  @Test
  void laneSerializesTasks() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      RegionGraphManager m = new RegionGraphManager("t", 0, 5, null, pool, 0);
      AtomicInteger inFlight = new AtomicInteger();
      AtomicInteger max = new AtomicInteger();
      List<Integer> order = new ArrayList<>();
      List<CompletableFuture<Integer>> fs = new ArrayList<>();
      for (int i = 0; i < 200; i++) {
        int n = i;
        fs.add(m.onLane(() -> {
          max.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          order.add(n);
          inFlight.decrementAndGet();
          return n;
        }));
      }
      CompletableFuture.allOf(fs.toArray(new CompletableFuture<?>[0])).get(30, TimeUnit.SECONDS);
      assertEquals(1, max.get());
      for (int i = 0; i < 200; i++) {
        assertEquals(i, order.get(i));
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void saveOnStopAndLoadRoundTrip(@TempDir Path dir) {
    FixtureWorld w = FixtureWorld.parse(GraphTestSupport.worldText(4));
    Path file = dir.resolve(RegionStorePaths.relativePath("minecraft", "overworld"));
    RegionGraphManager m = new RegionGraphManager("t", w.minY(), w.maxY(), file, DIRECT, 60_000);
    new BuildJob<>(m, ALL, BuildPlanner.plan(ALL),
        box -> CompletableFuture.completedFuture(new MaskedWorld(w, all())), LOADED, DIRECT, 0, null)
        .start().join();
    m.saveOnStop();
    assertTrue(Files.isRegularFile(file));

    RegionGraphManager loaded = new RegionGraphManager("t", w.minY(), w.maxY(), file, DIRECT, 0);
    loaded.load().join();
    assertEquals(m.current(), loaded.current());
    assertFalse(loaded.loading());

    RegionGraphManager otherY = new RegionGraphManager("t", w.minY() - 16, w.maxY(), file, DIRECT, 0);
    otherY.load().join();
    assertTrue(otherY.current().sectors().isEmpty(), "mismatched Y range must be discarded");
  }

  @Test
  void loadFailureKeepsEmpty(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("broken.sqlite");
    Files.writeString(file, "not a database");
    RegionGraphManager m = new RegionGraphManager("t", 0, 5, file, DIRECT, 0);
    m.load().join();
    assertTrue(m.current().sectors().isEmpty());
  }
}
