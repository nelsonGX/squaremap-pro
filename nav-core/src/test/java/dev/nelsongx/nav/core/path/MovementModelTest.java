package dev.nelsongx.nav.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MovementModelTest {

  private static final double EPS = 1e-12;

  private static Map<GridPos, Double> neighbors(FixtureWorld w, int x, int y, int z) {
    Map<GridPos, Double> out = new LinkedHashMap<>();
    MovementModel.forEachNeighbor(w, x, y, z, (nx, ny, nz, c) -> {
      Double prev = out.put(new GridPos(nx, ny, nz), c);
      if (prev != null) {
        throw new AssertionError("duplicate neighbor " + nx + "," + ny + "," + nz);
      }
    });
    return out;
  }

  @Test
  void constants() {
    assertEquals(1, MovementModel.MAX_STEP_UP);
    assertEquals(3, MovementModel.MAX_DROP);
  }

  @Test
  void openFloorHasEightNeighborsWithCardinalAndDiagonalCosts() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ###
        ###
        ###
        """);
    Map<GridPos, Double> n = neighbors(w, 1, 1, 1);
    assertEquals(8, n.size());
    for (Map.Entry<GridPos, Double> e : n.entrySet()) {
      GridPos p = e.getKey();
      assertEquals(1, p.y());
      boolean diagonal = p.x() != 1 && p.z() != 1;
      assertEquals(diagonal ? Math.sqrt(2) : 1.0, e.getValue(), EPS, p.toString());
    }
  }

  @Test
  void worldEdgeLimitsNeighbors() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ###
        ###
        ###
        """);
    Map<GridPos, Double> n = neighbors(w, 0, 1, 0);
    assertEquals(3, n.size());
    assertTrue(n.containsKey(new GridPos(1, 1, 0)));
    assertTrue(n.containsKey(new GridPos(0, 1, 1)));
    assertTrue(n.containsKey(new GridPos(1, 1, 1)));
  }

  @Test
  void stepUpCostsExtraHalf() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        y=1
        .#
        """);
    Map<GridPos, Double> n = neighbors(w, 0, 1, 0);
    assertEquals(Map.of(new GridPos(1, 2, 0), 1.5), n);
  }

  @Test
  void stepUpTwoIsNotANeighbor() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        y=1
        .#
        y=2
        .#
        """);
    assertTrue(neighbors(w, 0, 1, 0).isEmpty());
  }

  @Test
  void dropsCostQuarterPerBlockUpToThree() {
    // Column x=0 is a tower of height drop+1 (feet at drop+1); column x=1 is floor (feet y=1).
    for (int drop = 1; drop <= 4; drop++) {
      StringBuilder sb = new StringBuilder("y=0\n##\n");
      for (int y = 1; y <= drop; y++) {
        sb.append("y=").append(y).append("\n#.\n");
      }
      FixtureWorld w = FixtureWorld.parse(sb.toString());
      int feet = drop + 1;
      assertTrue(w.walkable(0, feet, 0));
      Map<GridPos, Double> n = neighbors(w, 0, feet, 0);
      if (drop <= MovementModel.MAX_DROP) {
        assertEquals(Map.of(new GridPos(1, 1, 0), 1.0 + 0.25 * drop), n, "drop " + drop);
      } else {
        assertTrue(n.isEmpty(), "drop " + drop);
      }
    }
  }

  @Test
  void prefersSameLevelOverStepUp() {
    // East column: feet y=1 walkable on the floor, and feet y=2 is not (y=1 is air), so same level.
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        """);
    assertEquals(Map.of(new GridPos(1, 1, 0), 1.0), neighbors(w, 0, 1, 0));

    // East column has a block at y=1 (feet y=2); feet y=0 below would need y=1 as head -> blocked.
    FixtureWorld w2 = FixtureWorld.parse("""
        y=-1
        ##
        y=0
        #.
        y=1
        .#
        """);
    assertEquals(Map.of(new GridPos(1, 2, 0), 1.5), neighbors(w2, 0, 1, 0));
  }

  @Test
  void dropThroughSolidBlockRejectedStepUpTaken() {
    // East column x=1: block y=1 (feet y=2) above a pocket with floor y=-2 (feet y=-1 walkable).
    // The drop to y=-1 would pass through the solid block at y=1, so only the step up is legal.
    FixtureWorld w = FixtureWorld.parse("""
        y=-2
        ##
        y=-1
        #.
        y=0
        #.
        y=1
        .#
        """);
    assertTrue(w.walkable(1, 2, 0));
    assertTrue(w.walkable(1, -1, 0));
    assertTrue(w.walkable(0, 1, 0));
    assertEquals(Map.of(new GridPos(1, 2, 0), 1.5), neighbors(w, 0, 1, 0));

    // Same, plus a ceiling two above the mover: step up blocked, drop still blocked -> nothing.
    FixtureWorld capped = FixtureWorld.parse("""
        y=-2
        ##
        y=-1
        #.
        y=0
        #.
        y=1
        .#
        y=3
        #.
        """);
    assertTrue(capped.walkable(0, 1, 0));
    assertTrue(neighbors(capped, 0, 1, 0).isEmpty());
  }

  @Test
  void stepUpBlockedByCeilingTwoAboveMover() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        y=1
        .#
        y=3
        #.
        """);
    assertTrue(w.walkable(0, 1, 0));
    assertTrue(w.walkable(1, 2, 0));
    assertTrue(neighbors(w, 0, 1, 0).isEmpty());
  }

  @Test
  void dropBlockedByOverhangInTargetColumn() {
    // Mover on a tower at x=0 (feet y=3); landing floor at x=1 (feet y=1). Overhang in the target
    // column at the mover's head height (y=4) or feet+head height (y=3..4).
    String[] overhangs = {"""
        y=3
        ..
        y=4
        .#
        """, """
        y=3
        .#
        y=4
        .#
        """};
    String base = """
        y=0
        ##
        y=1
        #.
        y=2
        #.
        """;
    for (String overhang : overhangs) {
      FixtureWorld w = FixtureWorld.parse(base + overhang);
      assertTrue(w.walkable(0, 3, 0));
      assertTrue(w.walkable(1, 1, 0));
      assertTrue(neighbors(w, 0, 3, 0).isEmpty(), overhang);
    }
    // Without the overhang the drop of 2 is legal.
    FixtureWorld open = FixtureWorld.parse(base + """
        y=3
        ..
        """);
    assertEquals(Map.of(new GridPos(1, 1, 0), 1.5), neighbors(open, 0, 3, 0));
  }

  @Test
  void dropClearanceChecksEveryBlockDownToLanding() {
    // Drop of 3 from feet y=4 (tower x=0) to feet y=1 (floor x=1). In the blocked world the target
    // column has a solid block at y=3 -- below both of the mover's feet/head levels and above the
    // landing's head -- plus a block at y=5 so that (1,4,0) is not a standing spot either.
    String base = """
        y=0
        ##
        y=1
        #.
        y=2
        #.
        """;
    FixtureWorld blocked = FixtureWorld.parse(base + """
        y=3
        ##
        y=4
        ..
        y=5
        .#
        """);
    assertTrue(blocked.walkable(0, 4, 0));
    assertTrue(blocked.walkable(1, 1, 0));
    assertFalse(blocked.walkable(1, 4, 0));
    assertTrue(neighbors(blocked, 0, 4, 0).isEmpty());

    FixtureWorld clear = FixtureWorld.parse(base + """
        y=3
        #.
        y=4
        ..
        """);
    assertEquals(Map.of(new GridPos(1, 1, 0), 1.75), neighbors(clear, 0, 4, 0));
  }

  @Test
  void noCornerCuttingPastPillar() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        ##
        y=1
        .#
        ..
        y=2
        .#
        ..
        """);
    Map<GridPos, Double> n = neighbors(w, 0, 1, 0);
    assertFalse(n.containsKey(new GridPos(1, 1, 1)));
    assertEquals(Map.of(new GridPos(0, 1, 1), 1.0), n);
  }

  @Test
  void noDiagonalClimb() {
    // Diagonal target is a step up; the cardinal cells are at origin level.
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        ##
        y=1
        ..
        .#
        """);
    Map<GridPos, Double> n = neighbors(w, 0, 1, 0);
    assertFalse(n.containsKey(new GridPos(1, 2, 1)));
    assertFalse(n.containsKey(new GridPos(1, 1, 1)));
  }

  @Test
  void moveCostFormula() {
    assertEquals(1.0, MovementModel.moveCost(true, 0), EPS);
    assertEquals(Math.sqrt(2), MovementModel.moveCost(false, 0), EPS);
    assertEquals(1.5, MovementModel.moveCost(true, 1), EPS);
    assertEquals(1.75, MovementModel.moveCost(true, -3), EPS);
  }

  @Test
  void octileHeuristic() {
    assertEquals(0.0, Heuristics.octile(0, 0), EPS);
    assertEquals(5.0, Heuristics.octile(-5, 0), EPS);
    assertEquals(3 * Math.sqrt(2), Heuristics.octile(3, -3), EPS);
    assertEquals(4 + 2 * (Math.sqrt(2) - 1), Heuristics.octile(2, 4), EPS);
  }
}
