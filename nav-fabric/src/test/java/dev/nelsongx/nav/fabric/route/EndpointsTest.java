package dev.nelsongx.nav.fabric.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class EndpointsTest {

  /**
   * Single column (x=0, z=0): solid floor at y=0 (cave floor, feet y=1, head y=2 air), cave ceiling
   * y=3..5 solid, surface feet y=6.
   */
  private static final FixtureWorld CAVE = FixtureWorld.parse("""
      y=0
      #
      y=1
      .
      y=2
      .
      y=3
      #
      y=4
      #
      y=5
      #
      y=6
      .
      """);

  @Test
  void caveUnderSurfaceStaysInCave() {
    assertEquals(OptionalInt.of(6), CAVE.groundY(0, 0)); // surface exists
    assertEquals(OptionalInt.of(1), Endpoints.nearestWalkableY(CAVE, 0, 1, 0));
    // mid-jump one block up in the cave: nearest is the cave floor, not the surface
    assertEquals(OptionalInt.of(1), Endpoints.nearestWalkableY(CAVE, 0, 2, 0));
  }

  @Test
  void offsetPicksNearestPreferringBelow() {
    // feet reported inside the cave ceiling at y=4: y=3 not walkable, y=5 not, y=2 not,
    // y=6 walkable (+2) and y=1 walkable (-3) -> +2 is nearer
    assertEquals(OptionalInt.of(6), Endpoints.nearestWalkableY(CAVE, 0, 4, 0));
    // equidistant candidates (walkable y=1 and y=5, start y=3) prefer below
    FixtureWorld twoFloors = FixtureWorld.parse("""
        y=0
        #
        y=1
        .
        y=2
        .
        y=3
        .
        y=4
        #
        y=5
        .
        """);
    assertEquals(OptionalInt.of(1), Endpoints.nearestWalkableY(twoFloors, 0, 3, 0));
  }

  @Test
  void nothingWithinToleranceIsEmpty() {
    assertEquals(OptionalInt.empty(), Endpoints.nearestWalkableY(CAVE, 0, 10, 0)); // y=6 is -4 away
    assertEquals(OptionalInt.empty(), Endpoints.nearestWalkableY(CAVE, 5, 1, 5)); // outside grid
  }
}
