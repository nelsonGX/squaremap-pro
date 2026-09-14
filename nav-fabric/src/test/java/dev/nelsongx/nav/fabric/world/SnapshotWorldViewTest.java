package dev.nelsongx.nav.fabric.world;

import static dev.nelsongx.nav.fabric.world.BlockClass.BLOCKED;
import static dev.nelsongx.nav.fabric.world.BlockClass.PASSABLE;
import static dev.nelsongx.nav.fabric.world.BlockClass.STANDABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SnapshotWorldViewTest {

  private static final int MIN_Y = -16;
  private static final int HEIGHT = 48;
  private static final int MAX_Y = MIN_Y + HEIGHT - 1;

  /** Flat world chunk: STANDABLE below y=0, PASSABLE from y=0 up. */
  private static ChunkSnapshot.Builder flat(int cx, int cz) {
    ChunkSnapshot.Builder b = ChunkSnapshot.builder(cx, cz, MIN_Y, HEIGHT);
    b.setUniformSection(0, STANDABLE);
    b.setUniformSection(1, PASSABLE);
    b.setUniformSection(2, PASSABLE);
    return b;
  }

  @Test
  void flatWorldContract() {
    SnapshotWorldView v = new SnapshotWorldView(MIN_Y, MAX_Y, List.of(flat(0, 0).build()));
    assertEquals(MIN_Y, v.minY());
    assertEquals(MAX_Y, v.maxY());
    assertTrue(v.walkable(5, 0, 5));
    assertFalse(v.walkable(5, 1, 5));
    assertFalse(v.walkable(5, -1, 5));
    assertTrue(v.passable(5, 0, 5));
    assertFalse(v.passable(5, -1, 5));
    assertEquals(OptionalInt.of(0), v.groundY(5, 5));
  }

  @Test
  void verticalBounds() {
    // Standable at maxY-1 with passable only at maxY: head block would be above maxY.
    ChunkSnapshot.Builder b = ChunkSnapshot.builder(0, 0, MIN_Y, HEIGHT);
    for (int s = 0; s < b.sectionCount(); s++) {
      b.setUniformSection(s, PASSABLE);
    }
    b.set(1, MAX_Y - 1, 1, STANDABLE);
    b.set(2, MAX_Y - 2, 2, STANDABLE);
    b.set(3, MIN_Y, 3, STANDABLE);
    SnapshotWorldView v = new SnapshotWorldView(MIN_Y, MAX_Y, List.of(b.build()));
    assertFalse(v.walkable(1, MAX_Y, 1));
    assertEquals(OptionalInt.empty(), v.groundY(1, 1));
    assertTrue(v.walkable(2, MAX_Y - 1, 2));
    assertEquals(OptionalInt.of(MAX_Y - 1), v.groundY(2, 2));
    assertTrue(v.walkable(3, MIN_Y + 1, 3));
    assertFalse(v.walkable(4, MIN_Y, 4)); // feet at minY: block below is out of range
    assertFalse(v.passable(0, MIN_Y - 1, 0));
    assertFalse(v.passable(0, MAX_Y + 1, 0));
    assertTrue(v.passable(0, MAX_Y, 0));
  }

  @Test
  void missingChunkIsUnknown() {
    SnapshotWorldView v = new SnapshotWorldView(MIN_Y, MAX_Y, List.of(flat(0, 0).build()));
    assertFalse(v.passable(16, 0, 0));
    assertFalse(v.walkable(16, 0, 0));
    assertFalse(v.passable(-1, 0, 0));
    assertFalse(v.walkable(0, 0, -1));
    assertEquals(OptionalInt.empty(), v.groundY(-1, -1));
    assertNull(v.chunk(1, 0));
    assertEquals(BLOCKED, v.classify(16, 0, 0));
  }

  @Test
  void emptyView() {
    SnapshotWorldView v = new SnapshotWorldView(0, 10, List.of());
    assertEquals(0, v.chunkCount());
    assertFalse(v.passable(0, 5, 0));
    assertEquals(OptionalInt.empty(), v.groundY(0, 0));
    assertThrows(IllegalArgumentException.class, () -> new SnapshotWorldView(5, 4, List.of()));
  }

  @Test
  void chunkBordersAtNegativeCoordinates() {
    // Four chunks around the origin with distinct ground heights.
    ChunkSnapshot.Builder nn = flat(-1, -1);
    ChunkSnapshot.Builder np = flat(-1, 0);
    ChunkSnapshot.Builder pn = flat(0, -1);
    ChunkSnapshot.Builder pp = flat(0, 0);
    raise(nn, -16, -16, 1);  // ground at y=1 in chunk (-1,-1)
    raise(pn, 0, -16, 2);    // ground at y=2 in chunk (0,-1)
    raise(np, -16, 0, 3);    // ground at y=3 in chunk (-1,0)
    SnapshotWorldView v = new SnapshotWorldView(MIN_Y, MAX_Y,
        List.of(nn.build(), np.build(), pn.build(), pp.build()));
    assertEquals(4, v.chunkCount());
    assertEquals(OptionalInt.of(1), v.groundY(-1, -1));
    assertEquals(OptionalInt.of(1), v.groundY(-16, -16));
    assertEquals(OptionalInt.of(2), v.groundY(0, -1));
    assertEquals(OptionalInt.of(2), v.groundY(15, -16));
    assertEquals(OptionalInt.of(3), v.groundY(-1, 0));
    assertEquals(OptionalInt.of(3), v.groundY(-16, 15));
    assertEquals(OptionalInt.of(0), v.groundY(0, 0));
    assertEquals(OptionalInt.of(0), v.groundY(15, 15));
    assertTrue(v.walkable(-1, 1, -1));
    assertFalse(v.walkable(-1, 0, -1));
    assertFalse(v.passable(-1, 0, -1));
    assertTrue(v.passable(0, 0, 0));
    assertEquals(OptionalInt.empty(), v.groundY(16, 0));
    assertEquals(OptionalInt.empty(), v.groundY(-17, 0));
  }

  /** Makes the whole chunk standable in [0, top-1] (chunk starts at world (x0, z0)). */
  private static void raise(ChunkSnapshot.Builder b, int x0, int z0, int top) {
    for (int y = 0; y < top; y++) {
      for (int dz = 0; dz < 16; dz++) {
        for (int dx = 0; dx < 16; dx++) {
          b.set(x0 + dx, y, z0 + dz, STANDABLE);
        }
      }
    }
  }

  @Test
  void blockedCellsAreNeitherPassableNorStandable() {
    ChunkSnapshot.Builder b = flat(0, 0);
    b.set(1, -1, 1, BLOCKED);  // e.g. lava floor
    b.set(2, 0, 2, BLOCKED);   // e.g. water at feet
    b.set(3, 1, 3, BLOCKED);   // e.g. fence at head
    SnapshotWorldView v = new SnapshotWorldView(MIN_Y, MAX_Y, List.of(b.build()));
    assertFalse(v.walkable(1, 0, 1));
    assertFalse(v.walkable(2, 0, 2));
    assertFalse(v.passable(2, 0, 2));
    assertFalse(v.walkable(3, 0, 3));
    assertEquals(OptionalInt.of(0), v.groundY(4, 4));
    // column 1: only standable blocks under y=-1 are at y=-2 with BLOCKED above → none below 0
    assertEquals(OptionalInt.empty(), v.groundY(1, 1));
  }

  @Test
  void manyChunksLookup() {
    List<ChunkSnapshot> list = new ArrayList<>();
    for (int cx = -20; cx < 20; cx++) {
      for (int cz = -20; cz < 20; cz++) {
        list.add(ChunkSnapshot.uniform(cx, cz, 0, 16, (cx + cz) % 2 == 0 ? PASSABLE : BLOCKED));
      }
    }
    SnapshotWorldView v = new SnapshotWorldView(0, 15, list);
    assertEquals(1600, v.chunkCount());
    for (int cx = -20; cx < 20; cx++) {
      for (int cz = -20; cz < 20; cz++) {
        ChunkSnapshot c = v.chunk(cx, cz);
        assertNotNull(c);
        assertEquals(cx, c.chunkX());
        assertEquals(cz, c.chunkZ());
        assertEquals((cx + cz) % 2 == 0, v.passable(cx * 16 + 7, 3, cz * 16 + 9));
      }
    }
    assertNull(v.chunk(20, 0));
  }

  private static final String FIXTURE = """
      y=-2
      ##########################
      ##########################
      ##########################
      ##########################
      ##########################
      ##########################
      y=-1
      ..~~.....#################
      ..~~......................
      ####......................
      ...#......#...............
      ...#......#.....###.......
      .........##...............
      y=0
      ..........................
      ...............#..........
      ####..........###.........
      ..........#...............
      ...#......................
      ........~~#...............
      y=1
      ..........................
      ..........................
      ..........................
      ...............#..........
      ...#......................
      ..........................
      """;

  @Test
  void crossCheckAgainstFixtureWorldAcrossChunkBorders() {
    FixtureWorld f = FixtureWorld.parse(FIXTURE);
    // Translate so the grid straddles chunk borders at x=0 and z=0 in negative space.
    int ox = -13;
    int oz = -3;
    int minY = f.minY();
    int height = f.maxY() - f.minY() + 1;
    Map<Long, ChunkSnapshot.Builder> builders = new HashMap<>();
    int minCx = Math.floorDiv(ox, 16);
    int maxCx = Math.floorDiv(ox + f.width() - 1, 16);
    int minCz = Math.floorDiv(oz, 16);
    int maxCz = Math.floorDiv(oz + f.depth() - 1, 16);
    for (int cx = minCx; cx <= maxCx; cx++) {
      for (int cz = minCz; cz <= maxCz; cz++) {
        builders.put(ChunkSnapshot.key(cx, cz), ChunkSnapshot.builder(cx, cz, minY, height));
      }
    }
    for (int x = 0; x < f.width(); x++) {
      for (int z = 0; z < f.depth(); z++) {
        for (int y = f.minY(); y <= f.maxY(); y++) {
          byte c = f.solid(x, y, z) ? STANDABLE : f.passable(x, y, z) ? PASSABLE : BLOCKED;
          int wx = x + ox;
          int wz = z + oz;
          builders.get(ChunkSnapshot.key(wx >> 4, wz >> 4)).set(wx, y, wz, c);
        }
      }
    }
    List<ChunkSnapshot> snaps = new ArrayList<>();
    builders.values().forEach(b -> snaps.add(b.build()));
    SnapshotWorldView v = new SnapshotWorldView(f.minY(), f.maxY(), snaps);
    assertEquals(4, v.chunkCount());
    assertEquals(f.minY(), v.minY());
    assertEquals(f.maxY(), v.maxY());

    int checked = 0;
    for (int x = -3; x < f.width() + 3; x++) {
      for (int z = -3; z < f.depth() + 3; z++) {
        int wx = x + ox;
        int wz = z + oz;
        boolean inChunk = wx >> 4 >= minCx && wx >> 4 <= maxCx && wz >> 4 >= minCz
            && wz >> 4 <= maxCz;
        for (int y = f.minY() - 2; y <= f.maxY() + 2; y++) {
          String at = "(" + x + "," + y + "," + z + ")";
          assertEquals(f.passable(x, y, z), v.passable(wx, y, wz), "passable " + at);
          assertEquals(f.walkable(x, y, z), v.walkable(wx, y, wz), "walkable " + at);
          checked++;
        }
        assertEquals(f.groundY(x, z), v.groundY(wx, wz), "groundY " + x + "," + z);
        if (!inChunk) {
          assertEquals(OptionalInt.empty(), v.groundY(wx, wz));
        }
      }
    }
    assertTrue(checked > 1000);
    // sanity: the fixture has both walkable and non-walkable columns
    assertEquals(OptionalInt.of(-1), f.groundY(0, 0));
    assertEquals(OptionalInt.of(1), f.groundY(15, 1));
  }
}
