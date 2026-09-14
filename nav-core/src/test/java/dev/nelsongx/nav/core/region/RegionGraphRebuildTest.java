package dev.nelsongx.nav.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegionGraphRebuildTest {

  private static final int SIZE = 4;
  private static final int W = 12;
  private static final int D = 12;
  private static final int LAYERS = 5;
  private static final SectorPos MIN = new SectorPos(-1, -1);
  private static final SectorPos MAX = new SectorPos(3, 3);

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20})
  void partialRebuildEqualsFullBuild(long seed) {
    Random rnd = new Random(seed);
    char[][][] cells = RegionTestSupport.randomCells(rnd, W, D, LAYERS);
    FixtureWorld before = FixtureWorld.parse(RegionTestSupport.toFixture(cells));
    RegionGraph original = RegionGraphBuilder.build(before, MIN, MAX, SIZE);
    RegionTestSupport.assertMatchesBruteForce(before, original);

    // Pick a random subset of in-grid sectors and mutate blocks only inside them.
    List<SectorPos> changed = new ArrayList<>();
    for (int sx = 0; sx < W / SIZE; sx++) {
      for (int sz = 0; sz < D / SIZE; sz++) {
        if (rnd.nextInt(3) == 0) {
          changed.add(new SectorPos(sx, sz));
        }
      }
    }
    if (changed.isEmpty()) {
      changed.add(new SectorPos(rnd.nextInt(W / SIZE), rnd.nextInt(D / SIZE)));
    }
    for (SectorPos s : changed) {
      for (int y = 0; y < LAYERS; y++) {
        for (int z = s.minBlockZ(SIZE); z < s.minBlockZ(SIZE) + SIZE; z++) {
          for (int x = s.minBlockX(SIZE); x < s.minBlockX(SIZE) + SIZE; x++) {
            if (rnd.nextInt(3) == 0) {
              cells[y][z][x] = RegionTestSupport.randomCell(rnd, y);
            }
          }
        }
      }
    }
    FixtureWorld after = FixtureWorld.parse(RegionTestSupport.toFixture(cells));
    RegionGraph full = RegionGraphBuilder.build(after, MIN, MAX, SIZE);
    RegionTestSupport.assertMatchesBruteForce(after, full);

    // Rebuilding exactly the changed sectors, plus (sometimes) some unchanged ones.
    List<SectorPos> rebuild = new ArrayList<>(changed);
    if (rnd.nextBoolean()) {
      rebuild.add(new SectorPos(-1, rnd.nextInt(5) - 1));
      rebuild.add(new SectorPos(rnd.nextInt(3), rnd.nextInt(3)));
    }
    RegionGraph partial = original.withSectorsRebuilt(after, rebuild);
    assertEquals(full, partial);
    assertEquals(full.hashCode(), partial.hashCode());
    assertEquals(full.linkCount(), partial.linkCount());

    // Rebuilding everything must agree too.
    List<SectorPos> all = new ArrayList<>(full.sectors());
    assertEquals(full, original.withSectorsRebuilt(after, all));
  }

  @ParameterizedTest
  @ValueSource(longs = {101, 102, 103, 104, 105})
  void addingSectorsEqualsFullBuild(long seed) {
    Random rnd = new Random(seed);
    FixtureWorld w = FixtureWorld.parse(
        RegionTestSupport.toFixture(RegionTestSupport.randomCells(rnd, W, D, LAYERS)));
    RegionGraph small = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(1, 1), SIZE);
    List<SectorPos> added = List.of(new SectorPos(2, 0), new SectorPos(2, 1), new SectorPos(2, 2),
        new SectorPos(0, 2), new SectorPos(1, 2));
    RegionGraph grown = small.withSectorsRebuilt(w, added);
    RegionGraph full = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(2, 2), SIZE);
    assertEquals(full, grown);
    RegionTestSupport.assertMatchesBruteForce(w, grown);
  }

  @Test
  void acceptanceFixtureOpenWallRebuild() {
    FixtureWorld before = FixtureWorld.parse(RegionTestSupport.ACCEPTANCE);
    RegionGraph original = RegionGraphBuilder.build(before, new SectorPos(0, 0),
        new SectorPos(2, 2), SIZE);
    // Open the wall in (0,0) at z=0.
    String text = RegionTestSupport.ACCEPTANCE
        .replace("y=1\n..#.........", "y=1\n............")
        .replace("y=2\n..~.........", "y=2\n............");
    FixtureWorld after = FixtureWorld.parse(text);
    RegionGraph partial = original.withSectorsRebuilt(after, List.of(new SectorPos(0, 0)));
    RegionGraph full = RegionGraphBuilder.build(after, new SectorPos(0, 0), new SectorPos(2, 2), SIZE);
    assertNotEquals(original, full);
    assertEquals(full, partial);
    assertEquals(1, partial.regionsIn(new SectorPos(0, 0)).size());
  }
}
