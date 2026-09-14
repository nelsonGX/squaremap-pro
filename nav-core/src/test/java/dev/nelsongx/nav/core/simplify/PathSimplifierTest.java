package dev.nelsongx.nav.core.simplify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import dev.nelsongx.nav.core.PathResult.Success;
import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.path.AStar;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PathSimplifierTest {

  private static String floor(int width, int depth) {
    StringBuilder sb = new StringBuilder("y=0\n");
    for (int z = 0; z < depth; z++) {
      sb.append("#".repeat(width)).append('\n');
    }
    return sb.toString();
  }

  private static List<GridPos> astar(WorldView w, GridPos s, GridPos g) {
    PathResult r = AStar.findPath(w, s, g, 1_000_000);
    return assertInstanceOf(Success.class, r, () -> "expected success but got " + r).points();
  }

  private static void assertSubsequence(List<GridPos> input, List<GridPos> output) {
    int i = 0;
    for (GridPos p : output) {
      while (i < input.size() && !input.get(i).equals(p)) {
        i++;
      }
      assertTrue(i < input.size(), "output point " + p + " not in input order");
      i++;
    }
  }

  private static void assertWellFormed(WorldView w, List<GridPos> input, List<GridPos> output) {
    assertEquals(input.get(0), output.get(0));
    assertEquals(input.get(input.size() - 1), output.get(output.size() - 1));
    assertSubsequence(input, output);
    for (int i = 1; i < output.size(); i++) {
      assertTrue(LineOfSight.clear(w, output.get(i - 1), output.get(i)),
          "segment " + output.get(i - 1) + " -> " + output.get(i) + " not clear");
    }
    assertThrows(UnsupportedOperationException.class, () -> output.add(output.get(0)));
  }

  @Test
  void staircaseOf200NodesCollapses() {
    FixtureWorld w = FixtureWorld.parse(floor(110, 110));
    List<GridPos> path = new ArrayList<>();
    int x = 2;
    int z = 2;
    path.add(new GridPos(x, 1, z));
    while (path.size() < 200) {
      if (path.size() % 2 == 1) {
        x++;
      } else {
        z++;
      }
      path.add(new GridPos(x, 1, z));
    }
    assertEquals(200, path.size());
    AStar.pathCost(w, path); // throws if not a legal path

    List<GridPos> out = PathSimplifier.simplify(w, path);
    assertTrue(out.size() < 10, "size " + out.size());
    assertWellFormed(w, path, out);
    System.out.println("staircase 200 -> " + out.size() + " points: " + out);
  }

  /** Terrain rising one block every {@code run} blocks along x. */
  private static FixtureWorld risingTerrain(int width, int depth, int run) {
    int top = (width - 1) / run;
    StringBuilder sb = new StringBuilder();
    for (int y = 0; y <= top + 1; y++) {
      sb.append("y=").append(y).append('\n');
      for (int z = 0; z < depth; z++) {
        for (int x = 0; x < width; x++) {
          sb.append(y <= x / run ? '#' : '.');
        }
        sb.append('\n');
      }
    }
    return FixtureWorld.parse(sb.toString());
  }

  @Test
  void climbingStaircaseCollapses() {
    FixtureWorld w = risingTerrain(48, 16, 4);
    GridPos s = new GridPos(0, 1, 0);
    GridPos g = new GridPos(47, 47 / 4 + 1, 15);
    List<GridPos> path = astar(w, s, g);
    assertTrue(path.size() > 40);
    List<GridPos> out = PathSimplifier.simplify(w, path);
    assertTrue(out.size() <= 4, "size " + out.size() + ": " + out);
    assertWellFormed(w, path, out);
    System.out.println("climbing " + path.size() + " -> " + out.size() + " points: " + out);
  }

  private static final String L_CORRIDOR = """
      y=1
      A..#######
      ...#######
      ...#######
      ...#######
      ...#######
      ...#######
      ...#######
      ..........
      ..........
      .........B
      """;

  private static FixtureWorld lCorridor() {
    String walls = L_CORRIDOR.replace("y=1\n", "").replace('A', '.').replace('B', '.');
    return FixtureWorld.parse(floor(10, 10) + L_CORRIDOR + "y=2\n" + walls);
  }

  private static void assertCornerKept(FixtureWorld w, List<GridPos> path, List<GridPos> out) {
    assertTrue(out.size() >= 3, "corner dropped: " + out);
    assertWellFormed(w, path, out);
    for (int i = 1; i < out.size(); i++) {
      GridPos a = out.get(i - 1);
      GridPos b = out.get(i);
      for (int[] c : LineOfSight.supercover(a.x(), a.z(), b.x(), b.z())) {
        assertFalse(w.solid(c[0], 1, c[1]),
            "segment " + a + " -> " + b + " cuts wall at " + c[0] + "," + c[1]);
      }
    }
  }

  @Test
  void lCorridorKeepsCorner() {
    FixtureWorld w = lCorridor();
    List<GridPos> path = astar(w, w.marker('A'), w.marker('B'));
    assertFalse(LineOfSight.clear(w, w.marker('A'), w.marker('B')));
    assertCornerKept(w, path, PathSimplifier.simplify(w, path));
  }

  @Test
  void lCorridorKeepsCornerWithDouglasPeuckerOnly() {
    FixtureWorld w = lCorridor();
    List<GridPos> path = astar(w, w.marker('A'), w.marker('B'));
    // Lookahead 1 disables string pulling; a huge epsilon would drop the corner if unconstrained.
    assertCornerKept(w, path, PathSimplifier.simplify(w, path, 1000.0, 1));
  }

  @Test
  void douglasPeuckerCollapsesStraightRunBeyondLookahead() {
    FixtureWorld w = FixtureWorld.parse(floor(40, 3));
    List<GridPos> path = astar(w, new GridPos(0, 1, 1), new GridPos(39, 1, 1));
    List<GridPos> out = PathSimplifier.simplify(w, path, 0.0, 1);
    assertEquals(List.of(new GridPos(0, 1, 1), new GridPos(39, 1, 1)), out);
  }

  @Test
  void subsequenceEndpointsAndIdempotence() {
    FixtureWorld w = FixtureWorld.parse(floor(20, 20) + """
        y=1
        ....................
        ....................
        ....####............
        ....####............
        ...........#........
        ...........#........
        ...........#........
        ..###......#........
        ..###...............
        ....................
        ..............###...
        ..............###...
        .......#............
        .......#............
        .......#.....#......
        .............#......
        ....................
        ...######...........
        ....................
        ....................
        """);
    GridPos[][] queries = {
        {new GridPos(0, 1, 0), new GridPos(19, 1, 19)},
        {new GridPos(19, 1, 0), new GridPos(0, 1, 19)},
        {new GridPos(5, 1, 0), new GridPos(5, 1, 19)},
        {new GridPos(0, 1, 9), new GridPos(19, 1, 12)},
    };
    for (GridPos[] q : queries) {
      List<GridPos> path = astar(w, q[0], q[1]);
      for (double eps : new double[] {0.0, 1.0, 3.0}) {
        List<GridPos> once = PathSimplifier.simplify(w, path, eps);
        assertWellFormed(w, path, once);
        assertEquals(once, PathSimplifier.simplify(w, once, eps), "idempotence " + q[0]);
      }
    }
  }

  @Test
  void smallInputsAreCopies() {
    FixtureWorld w = FixtureWorld.parse(floor(4, 4));
    assertEquals(List.of(), PathSimplifier.simplify(w, new ArrayList<>()));
    List<GridPos> one = new ArrayList<>(List.of(new GridPos(1, 1, 1)));
    List<GridPos> two = new ArrayList<>(List.of(new GridPos(1, 1, 1), new GridPos(2, 1, 1)));
    List<GridPos> outOne = PathSimplifier.simplify(w, one);
    List<GridPos> outTwo = PathSimplifier.simplify(w, two);
    assertEquals(one, outOne);
    assertEquals(two, outTwo);
    assertNotSame(one, outOne);
    assertNotSame(two, outTwo);
    assertThrows(UnsupportedOperationException.class, () -> outTwo.add(new GridPos(0, 1, 0)));
  }

  @Test
  void invalidArguments() {
    FixtureWorld w = FixtureWorld.parse(floor(4, 4));
    List<GridPos> p = List.of(new GridPos(1, 1, 1));
    assertThrows(IllegalArgumentException.class, () -> PathSimplifier.simplify(w, p, -0.1));
    assertThrows(IllegalArgumentException.class, () -> PathSimplifier.simplify(w, p, Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> PathSimplifier.simplify(w, p, 1.0, 0));
    assertThrows(NullPointerException.class, () -> PathSimplifier.simplify(null, p));
    assertThrows(NullPointerException.class, () -> PathSimplifier.simplify(w, null));
  }

  @Test
  void distanceToSegment() {
    GridPos a = new GridPos(0, 0, 0);
    GridPos b = new GridPos(10, 0, 0);
    assertEquals(3.0, PathSimplifier.distanceToSegment(new GridPos(5, 0, 3), a, b), 1e-9);
    assertEquals(5.0, PathSimplifier.distanceToSegment(new GridPos(5, 4, 3), a, b), 1e-9);
    assertEquals(5.0, PathSimplifier.distanceToSegment(new GridPos(-3, 0, 4), a, b), 1e-9);
    assertEquals(5.0, PathSimplifier.distanceToSegment(new GridPos(3, 4, 0), a, a), 1e-9);
  }

  private static FixtureWorld randomWorld(Random rnd, int size) {
    int layers = 7;
    char[][][] cells = new char[layers][size][size];
    for (int z = 0; z < size; z++) {
      for (int x = 0; x < size; x++) {
        int height = rnd.nextDouble() < 0.12 ? layers : 1 + rnd.nextInt(rnd.nextDouble() < 0.5 ? 1 : 3);
        boolean fluid = rnd.nextDouble() < 0.04;
        int floating = rnd.nextDouble() < 0.1 ? height + 1 + rnd.nextInt(3) : -1;
        for (int y = 0; y < layers; y++) {
          char c = '.';
          if (y < height || y == floating) {
            c = '#';
          } else if (y == height && fluid) {
            c = '~';
          }
          cells[y][z][x] = c;
        }
      }
    }
    StringBuilder sb = new StringBuilder();
    for (int y = 0; y < layers; y++) {
      sb.append("y=").append(y).append('\n');
      for (int z = 0; z < size; z++) {
        sb.append(cells[y][z]).append('\n');
      }
    }
    return FixtureWorld.parse(sb.toString());
  }

  private static GridPos randomWalkable(Random rnd, FixtureWorld w) {
    for (int tries = 0; tries < 1000; tries++) {
      int x = rnd.nextInt(w.width());
      int z = rnd.nextInt(w.depth());
      OptionalInt gy = w.groundY(x, z);
      if (gy.isPresent()) {
        return new GridPos(x, gy.getAsInt(), z);
      }
    }
    throw new AssertionError("no walkable cell");
  }

  @Test
  void randomWorldsProduceClearSubsequences() {
    int checked = 0;
    long inputPoints = 0;
    long outputPoints = 0;
    for (long seed = 1; seed <= 80; seed++) {
      Random rnd = new Random(seed);
      FixtureWorld w = randomWorld(rnd, 16 + rnd.nextInt(16));
      for (int q = 0; q < 6; q++) {
        GridPos s = randomWalkable(rnd, w);
        GridPos g = randomWalkable(rnd, w);
        PathResult r = AStar.findPath(w, s, g, 1_000_000);
        if (!(r instanceof Success ok) || ok.points().size() < 3) {
          continue;
        }
        List<GridPos> path = ok.points();
        double eps = rnd.nextInt(4) * 0.75;
        int lookahead = rnd.nextBoolean() ? PathSimplifier.DEFAULT_MAX_LOOKAHEAD : 1 + rnd.nextInt(8);
        List<GridPos> out = PathSimplifier.simplify(w, path, eps, lookahead);
        assertWellFormed(w, path, out);
        assertEquals(out, PathSimplifier.simplify(w, path, eps, lookahead), "deterministic");
        checked++;
        inputPoints += path.size();
        outputPoints += out.size();
      }
    }
    assertTrue(checked >= 100, "only " + checked + " paths checked");
    assertTrue(outputPoints < inputPoints, "no reduction at all");
  }
}
