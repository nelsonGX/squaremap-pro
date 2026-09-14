package dev.nelsongx.nav.core.simplify;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineOfSightTest {

  private static String floor(int width, int depth) {
    StringBuilder sb = new StringBuilder("y=0\n");
    for (int z = 0; z < depth; z++) {
      sb.append("#".repeat(width)).append('\n');
    }
    return sb.toString();
  }

  @Test
  void clearOverOpenFloor() {
    FixtureWorld w = FixtureWorld.parse(floor(10, 10));
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 0), new GridPos(9, 1, 6)));
    assertTrue(LineOfSight.clear(w, new GridPos(9, 1, 6), new GridPos(0, 1, 0)));
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 9), new GridPos(9, 1, 0)));
    assertTrue(LineOfSight.clear(w, new GridPos(3, 1, 3), new GridPos(3, 1, 3)));
  }

  @Test
  void samePointNotWalkableIsFalse() {
    FixtureWorld w = FixtureWorld.parse(floor(4, 4));
    assertFalse(LineOfSight.clear(w, new GridPos(1, 2, 1), new GridPos(1, 2, 1)));
  }

  @Test
  void blockedByPillar() {
    String layer = """
        .........
        .........
        ....#....
        .........
        """;
    FixtureWorld w = FixtureWorld.parse(floor(9, 4) + "y=1\n" + layer + "y=2\n" + layer);
    assertFalse(LineOfSight.clear(w, new GridPos(0, 1, 2), new GridPos(8, 1, 2)));
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 0), new GridPos(8, 1, 0)));
  }

  @Test
  void cornerExactDiagonalTouchingSolidIsFalse() {
    // The diagonal (0,0)->(3,3) passes exactly through the corner between (1,1) and (2,2), whose
    // side cells are (2,1) and (1,2). A 2-high pillar at (2,1) touches that corner only.
    String layer = """
        ....
        ..#.
        ....
        ....
        """;
    FixtureWorld w = FixtureWorld.parse(floor(4, 4) + "y=1\n" + layer + "y=2\n" + layer);
    assertFalse(LineOfSight.clear(w, new GridPos(0, 1, 0), new GridPos(3, 1, 3)));
    assertFalse(LineOfSight.clear(w, new GridPos(3, 1, 3), new GridPos(0, 1, 0)));
    // A corner crossing whose side cells are free is fine.
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 0), new GridPos(1, 1, 1)));
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 1), new GridPos(1, 1, 2)));
  }

  @Test
  void legalStepUpAlongLineIsTrue() {
    FixtureWorld w = FixtureWorld.parse(floor(10, 5) + """
        y=1
        .....#####
        .....#####
        .....#####
        .....#####
        .....#####
        """);
    assertTrue(LineOfSight.clear(w, new GridPos(0, 1, 1), new GridPos(9, 2, 3)));
    assertTrue(LineOfSight.clear(w, new GridPos(9, 2, 3), new GridPos(0, 1, 1)));
  }

  @Test
  void stepUpOfTwoIsFalse() {
    String high = """
        .....#####
        .....#####
        .....#####
        """;
    FixtureWorld w = FixtureWorld.parse(floor(10, 3) + "y=1\n" + high + "y=2\n" + high);
    assertFalse(LineOfSight.clear(w, new GridPos(0, 1, 1), new GridPos(9, 3, 1)));
  }

  @Test
  void finalYMismatchIsFalse() {
    // A deck at y=4 over the east half: both (9,1,1) below it and (9,5,1) on top are walkable.
    FixtureWorld w = FixtureWorld.parse(floor(10, 3) + """
        y=4
        .....#####
        .....#####
        .....#####
        """);
    GridPos low = new GridPos(0, 1, 1);
    assertTrue(w.walkable(9, 1, 1));
    assertTrue(w.walkable(9, 5, 1));
    assertTrue(LineOfSight.clear(w, low, new GridPos(9, 1, 1)));
    // The walk from the floor stays under the deck, so it ends at y=1, not y=5.
    assertFalse(LineOfSight.clear(w, low, new GridPos(9, 5, 1)));
  }

  @Test
  void supercoverCells() {
    List<int[]> cells = LineOfSight.supercover(0, 0, 2, 2);
    int[][] expected = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}, {2, 2}};
    assertEquals(expected.length, cells.size());
    for (int i = 0; i < expected.length; i++) {
      assertArrayEquals(expected[i], cells.get(i));
    }
    List<int[]> shallow = LineOfSight.supercover(0, 0, 4, 1);
    int[][] expectedShallow = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {3, 1}, {4, 1}};
    assertEquals(expectedShallow.length, shallow.size());
    for (int i = 0; i < expectedShallow.length; i++) {
      assertArrayEquals(expectedShallow[i], shallow.get(i));
    }
  }
}
