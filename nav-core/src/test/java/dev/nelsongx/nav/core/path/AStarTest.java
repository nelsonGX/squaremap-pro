package dev.nelsongx.nav.core.path;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AStarTest {

  private static final double EPS = 1e-9;
  private static final double SQRT2 = Math.sqrt(2);

  private static Success success(PathResult r) {
    return assertInstanceOf(Success.class, r, () -> "expected success but got " + r);
  }

  private static Failure failure(PathResult r, FailureReason reason) {
    Failure f = assertInstanceOf(Failure.class, r, () -> "expected failure but got " + r);
    assertEquals(reason, f.reason(), f.detail());
    return f;
  }

  /** A solid floor layer at y=0 of the given size (feet level y=1). */
  private static String floor(int width, int depth) {
    StringBuilder sb = new StringBuilder("y=0\n");
    for (int z = 0; z < depth; z++) {
      sb.append("#".repeat(width)).append('\n');
    }
    return sb.toString();
  }

  private static void assertValidPath(WorldView w, Success s, GridPos start, GridPos goal) {
    List<GridPos> pts = s.points();
    assertEquals(start, pts.get(0));
    assertEquals(goal, pts.get(pts.size() - 1));
    AStar.pathCost(w, pts); // throws if any move is illegal
  }

  @Test
  void straightLine() {
    FixtureWorld w = FixtureWorld.parse(floor(12, 3) + """
        y=1
        ............
        S.........G.
        ............
        """);
    GridPos s = w.marker('S');
    GridPos g = w.marker('G');
    Success r = success(AStar.findPath(w, s, g, 10_000));
    assertEquals(11, r.points().size());
    for (int i = 0; i < 11; i++) {
      assertEquals(new GridPos(i, 1, 1), r.points().get(i));
    }
    assertEquals(10.0, AStar.pathCost(w, r.points()), EPS);
    assertTrue(r.nodesExpanded() >= 11);
  }

  @Test
  void wallWithGap() {
    String wall = """
        .......
        .......
        .......
        ###.###
        .......
        .......
        .......
        """;
    FixtureWorld w = FixtureWorld.parse(floor(7, 7) + """
        y=1
        S......
        .......
        .......
        ###.###
        .......
        .......
        ......G
        """ + "y=2\n" + wall);
    GridPos s = w.marker('S');
    GridPos g = w.marker('G');
    Success r = success(AStar.findPath(w, s, g, 10_000));
    assertValidPath(w, r, s, g);
    assertTrue(r.points().contains(new GridPos(3, 1, 3)), r.points().toString());
    // (0,0)->(3,2): 2 diagonals + 1 cardinal; (3,2)->(3,3)->(3,4): 2; (3,4)->(6,6): 2 diag + 1.
    double expected = 4.0 + 4.0 * SQRT2;
    assertEquals(expected, AStar.pathCost(w, r.points()), EPS);
  }

  @Test
  void unreachableGoal() {
    String ring = """
        .......
        .#####.
        .#...#.
        .#...#.
        .#####.
        """;
    FixtureWorld w = FixtureWorld.parse(floor(7, 5) + """
        y=1
        S......
        .#####.
        .#...#.
        .#.G.#.
        .#####.
        """ + "y=2\n" + ring);
    Failure f = failure(AStar.findPath(w, w.marker('S'), w.marker('G'), 10_000),
        FailureReason.NO_PATH);
    assertTrue(f.nodesExpanded() > 0);
    // Everything outside the ring: 7*5 - 5*4 (ring + interior) = 15 cells, all expanded.
    assertEquals(15, f.nodesExpanded());
  }

  @Test
  void capExceeded() {
    FixtureWorld w = FixtureWorld.parse(floor(64, 64));
    Failure f = failure(AStar.findPath(w, new GridPos(0, 1, 0), new GridPos(63, 1, 63), 10),
        FailureReason.CAP_EXCEEDED);
    assertEquals(10, f.nodesExpanded());
  }

  @Test
  void capExactlyEnoughSucceeds() {
    FixtureWorld w = FixtureWorld.parse(floor(12, 1));
    // Straight corridor: A* expands exactly the 11 path nodes.
    Success r = success(AStar.findPath(w, new GridPos(0, 1, 0), new GridPos(10, 1, 0), 11));
    assertEquals(11, r.nodesExpanded());
    failure(AStar.findPath(w, new GridPos(0, 1, 0), new GridPos(10, 1, 0), 10),
        FailureReason.CAP_EXCEEDED);
  }

  @Test
  void invalidEndpoints() {
    FixtureWorld w = FixtureWorld.parse(floor(5, 5) + """
        y=1
        .....
        ..#..
        .....
        .....
        .....
        """);
    GridPos ok = new GridPos(0, 1, 0);
    Failure inWall = failure(AStar.findPath(w, new GridPos(2, 1, 1), ok, 100),
        FailureReason.INVALID_ENDPOINT);
    assertEquals(0, inWall.nodesExpanded());
    failure(AStar.findPath(w, new GridPos(0, 2, 4), ok, 100), FailureReason.INVALID_ENDPOINT);
    failure(AStar.findPath(w, ok, new GridPos(3, 3, 3), 100), FailureReason.INVALID_ENDPOINT);
    failure(AStar.findPath(w, ok, new GridPos(99, 1, 0), 100), FailureReason.INVALID_ENDPOINT);
    failure(AStar.findPath(w, new GridPos(1 << 26, 1, 0), ok, 100),
        FailureReason.INVALID_ENDPOINT);
  }

  @Test
  void maxNodesMustBePositive() {
    FixtureWorld w = FixtureWorld.parse(floor(2, 1));
    GridPos p = new GridPos(0, 1, 0);
    assertThrows(IllegalArgumentException.class, () -> AStar.findPath(w, p, p, 0));
  }

  @Test
  void startEqualsGoal() {
    FixtureWorld w = FixtureWorld.parse(floor(2, 1));
    GridPos p = new GridPos(1, 1, 0);
    Success r = success(AStar.findPath(w, p, p, 1));
    assertEquals(List.of(p), r.points());
    assertEquals(0, r.nodesExpanded());
  }

  /** Floor at y=0; right half raised by {@code height} blocks. */
  private static FixtureWorld terrace(int height) {
    StringBuilder sb = new StringBuilder(floor(6, 1));
    for (int y = 1; y <= height; y++) {
      sb.append("y=").append(y).append("\n...###\n");
    }
    return FixtureWorld.parse(sb.toString());
  }

  @Test
  void stepUpOneAllowed() {
    FixtureWorld w = terrace(1);
    GridPos s = new GridPos(0, 1, 0);
    GridPos g = new GridPos(5, 2, 0);
    Success r = success(AStar.findPath(w, s, g, 1000));
    assertValidPath(w, r, s, g);
    assertEquals(5.5, AStar.pathCost(w, r.points()), EPS);
  }

  @Test
  void stepUpTwoBlocked() {
    FixtureWorld w = terrace(2);
    failure(AStar.findPath(w, new GridPos(0, 1, 0), new GridPos(5, 3, 0), 1000),
        FailureReason.NO_PATH);
  }

  @Test
  void dropThreeAllowed() {
    FixtureWorld w = terrace(3);
    GridPos s = new GridPos(5, 4, 0);
    GridPos g = new GridPos(0, 1, 0);
    Success r = success(AStar.findPath(w, s, g, 1000));
    assertValidPath(w, r, s, g);
    assertEquals(5.75, AStar.pathCost(w, r.points()), EPS);
    // And not back up.
    failure(AStar.findPath(w, g, s, 1000), FailureReason.NO_PATH);
  }

  @Test
  void dropFourBlocked() {
    FixtureWorld w = terrace(4);
    failure(AStar.findPath(w, new GridPos(5, 5, 0), new GridPos(0, 1, 0), 1000),
        FailureReason.NO_PATH);
  }

  @Test
  void dropUnderOverhangIsNoPath() {
    String world = """
        y=0
        ###
        y=1
        #..
        y=2
        #..
        y=3
        ...
        y=4
        .%s.
        """;
    GridPos s = new GridPos(0, 3, 0);
    GridPos g = new GridPos(2, 1, 0);
    FixtureWorld blocked = FixtureWorld.parse(world.formatted("#"));
    failure(AStar.findPath(blocked, s, g, 1000), FailureReason.NO_PATH);
    FixtureWorld open = FixtureWorld.parse(world.formatted("."));
    Success r = success(AStar.findPath(open, s, g, 1000));
    assertEquals(2.5, AStar.pathCost(open, r.points()), EPS);
  }

  @Test
  void stepUpUnderCeilingIsNoPath() {
    String world = floor(6, 1) + """
        y=1
        ...###
        y=3
        ..%s...
        """;
    GridPos s = new GridPos(0, 1, 0);
    GridPos g = new GridPos(5, 2, 0);
    failure(AStar.findPath(FixtureWorld.parse(world.formatted("#")), s, g, 1000),
        FailureReason.NO_PATH);
    success(AStar.findPath(FixtureWorld.parse(world.formatted(".")), s, g, 1000));
  }

  @Test
  void noCornerCuttingAroundPillar() {
    String pillar = """
        ...
        .#.
        ...
        """;
    FixtureWorld w = FixtureWorld.parse(floor(3, 3) + "y=1\n" + pillar + "y=2\n" + pillar);
    GridPos s = new GridPos(1, 1, 0);
    GridPos g = new GridPos(2, 1, 1);
    // (1,0) -> (2,1) diagonally would clip the pillar corner at (1,1): must go via (2,0).
    Success r = success(AStar.findPath(w, s, g, 100));
    assertEquals(List.of(s, new GridPos(2, 1, 0), g), r.points());
    assertEquals(2.0, AStar.pathCost(w, r.points()), EPS);
    // Around the whole pillar from (0,0) to (2,2): 4 cardinal moves, no diagonal shortcut.
    Success around = success(AStar.findPath(w, new GridPos(0, 1, 0), new GridPos(2, 1, 2), 100));
    assertEquals(4.0, AStar.pathCost(w, around.points()), EPS);
  }

  @Test
  void deterministic() {
    FixtureWorld w = FixtureWorld.parse(floor(32, 32));
    GridPos s = new GridPos(3, 1, 5);
    GridPos g = new GridPos(28, 1, 19);
    PathResult a = AStar.findPath(w, s, g, 100_000);
    PathResult b = AStar.findPath(w, s, g, 100_000);
    success(a);
    assertEquals(a, b);
  }

  @Test
  void pathCostRejectsIllegalMoves() {
    FixtureWorld w = FixtureWorld.parse(floor(4, 1));
    assertEquals(0.0, AStar.pathCost(w, List.of()), EPS);
    assertEquals(0.0, AStar.pathCost(w, List.of(new GridPos(0, 1, 0))), EPS);
    assertThrows(IllegalArgumentException.class,
        () -> AStar.pathCost(w, List.of(new GridPos(0, 1, 0), new GridPos(2, 1, 0))));
    assertThrows(IllegalArgumentException.class,
        () -> AStar.pathCost(w, List.of(new GridPos(0, 1, 0), new GridPos(0, 1, 0))));
  }

  @Test
  void packRoundTrip() {
    int[][] cases = {
        {0, 0, 0}, {-1, -1, -1}, {AStar.XZ_MAX, AStar.Y_MAX, AStar.XZ_MAX},
        {AStar.XZ_MIN, AStar.Y_MIN, AStar.XZ_MIN}, {33_554_431, 2047, -33_554_431},
        {-33_554_431, -2047, 33_554_431}, {12345, -64, -9876}};
    for (int[] c : cases) {
      long k = AStar.pack(c[0], c[1], c[2]);
      assertEquals(c[0], AStar.unpackX(k));
      assertEquals(c[1], AStar.unpackY(k));
      assertEquals(c[2], AStar.unpackZ(k));
    }
    assertTrue(AStar.inRange(33_554_431, 2047, -33_554_431));
  }

  @Test
  void worksAtNegativeCoordinates() {
    FixtureWorld base = FixtureWorld.parse(floor(8, 8));
    // Shift the world far into negative x/z and y to exercise packing.
    int ox = -20_000_000;
    int oy = -1000;
    int oz = -30_000_000;
    WorldView shifted = new WorldView() {
      @Override
      public int minY() {
        return base.minY() + oy;
      }

      @Override
      public int maxY() {
        return base.maxY() + oy;
      }

      @Override
      public boolean passable(int x, int y, int z) {
        return base.passable(x - ox, y - oy, z - oz);
      }

      @Override
      public boolean walkable(int x, int y, int z) {
        return base.walkable(x - ox, y - oy, z - oz);
      }

      @Override
      public OptionalInt groundY(int x, int z) {
        OptionalInt g = base.groundY(x - ox, z - oz);
        return g.isPresent() ? OptionalInt.of(g.getAsInt() + oy) : g;
      }
    };
    GridPos s = new GridPos(ox, 1 + oy, oz);
    GridPos g = new GridPos(ox + 7, 1 + oy, oz + 3);
    Success r = success(AStar.findPath(shifted, s, g, 1000));
    assertValidPath(shifted, r, s, g);
    assertEquals(4 + 3 * SQRT2, AStar.pathCost(shifted, r.points()), EPS);
  }

  // ---- optimality vs brute-force Dijkstra ----

  private static double dijkstra(WorldView w, GridPos start, GridPos goal) {
    Map<GridPos, Double> dist = new HashMap<>();
    PriorityQueue<Object[]> pq = new PriorityQueue<>((a, b) -> Double.compare((double) a[0],
        (double) b[0]));
    dist.put(start, 0.0);
    pq.add(new Object[] {0.0, start});
    while (!pq.isEmpty()) {
      Object[] e = pq.poll();
      double d = (double) e[0];
      GridPos p = (GridPos) e[1];
      if (d > dist.get(p)) {
        continue;
      }
      if (p.equals(goal)) {
        return d;
      }
      List<Object[]> next = new ArrayList<>();
      MovementModel.forEachNeighbor(w, p.x(), p.y(), p.z(),
          (x, y, z, c) -> next.add(new Object[] {d + c, new GridPos(x, y, z)}));
      for (Object[] n : next) {
        GridPos q = (GridPos) n[1];
        double nd = (double) n[0];
        Double old = dist.get(q);
        if (old == null || nd < old) {
          dist.put(q, nd);
          pq.add(n);
        }
      }
    }
    return Double.POSITIVE_INFINITY;
  }

  private static FixtureWorld randomWorld(Random rnd, int size) {
    int layers = 7;
    char[][][] cells = new char[layers][size][size];
    for (int z = 0; z < size; z++) {
      for (int x = 0; x < size; x++) {
        int height = rnd.nextDouble() < 0.12 ? layers : 1 + rnd.nextInt(4);
        boolean fluid = rnd.nextDouble() < 0.05;
        // Floating block (overhang / ceiling) 1-3 above the surface, to exercise clearance rules.
        int floating = rnd.nextDouble() < 0.15 ? height + 1 + rnd.nextInt(3) : -1;
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
  void optimalAgainstDijkstraOnRandomWorlds() {
    int successes = 0;
    int noPaths = 0;
    for (long seed = 1; seed <= 60; seed++) {
      Random rnd = new Random(seed);
      FixtureWorld w = randomWorld(rnd, 10 + rnd.nextInt(6));
      for (int q = 0; q < 5; q++) {
        GridPos s = randomWalkable(rnd, w);
        GridPos g = randomWalkable(rnd, w);
        double expected = dijkstra(w, s, g);
        PathResult r = AStar.findPath(w, s, g, 1_000_000);
        String ctx = "seed " + seed + " query " + q + " " + s + " -> " + g;
        if (Double.isInfinite(expected)) {
          assertEquals(FailureReason.NO_PATH,
              assertInstanceOf(Failure.class, r, ctx).reason(), ctx);
          noPaths++;
        } else {
          Success ok = assertInstanceOf(Success.class, r, ctx);
          assertValidPath(w, ok, s, g);
          assertEquals(expected, AStar.pathCost(w, ok.points()), EPS, ctx);
          assertEquals(r, AStar.findPath(w, s, g, 1_000_000), ctx);
          successes++;
        }
      }
    }
    assertTrue(successes > 50, "too few successful random queries: " + successes);
    assertTrue(noPaths > 0, "random worlds never produced an unreachable query");
  }
}
