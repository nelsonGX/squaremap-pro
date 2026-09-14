package dev.nelsongx.nav.core.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class FixtureWorldTest {

  @Test
  void singleLayerFloorIsWalkableOneAbove() {
    FixtureWorld w = FixtureWorld.parse("""
        ; a 3x2 floor
        y=0
        ###
        ###
        """);
    assertEquals(3, w.width());
    assertEquals(2, w.depth());
    assertEquals(0, w.minY());
    assertEquals(2, w.maxY());
    for (int x = 0; x < 3; x++) {
      for (int z = 0; z < 2; z++) {
        assertTrue(w.solid(x, 0, z));
        assertFalse(w.passable(x, 0, z));
        assertTrue(w.passable(x, 1, z));
        assertTrue(w.walkable(x, 1, z));
        assertFalse(w.walkable(x, 0, z));
        assertFalse(w.walkable(x, 2, z), "head would be above maxY");
        assertEquals(OptionalInt.of(1), w.groundY(x, z));
      }
    }
  }

  @Test
  void bridgeOverGapGroundYPicksHigherSurface() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##.##
        ##.##
        ##.##

        y=3
        .....
        #####
        .....
        """);
    assertEquals(0, w.minY());
    assertEquals(5, w.maxY());
    // Beside the bridge: only the floor.
    assertEquals(OptionalInt.of(1), w.groundY(0, 0));
    assertTrue(w.walkable(0, 1, 0));
    // Under the bridge on the floor: headroom y=1,2 free -> walkable on both levels.
    assertTrue(w.walkable(0, 1, 1));
    assertTrue(w.walkable(0, 4, 1));
    assertEquals(OptionalInt.of(4), w.groundY(0, 1));
    // Over the gap: only the bridge.
    assertFalse(w.walkable(2, 1, 1));
    assertTrue(w.walkable(2, 4, 1));
    assertEquals(OptionalInt.of(4), w.groundY(2, 1));
    // Gap column beside the bridge: nothing.
    assertEquals(OptionalInt.empty(), w.groundY(2, 0));
    // Unlisted levels are air.
    assertTrue(w.passable(2, 1, 1));
    assertFalse(w.solid(0, 2, 0));
  }

  @Test
  void headroomBlockedBySolidTwoAbove() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ###
        y=2
        .#.
        """);
    assertTrue(w.walkable(0, 1, 0));
    assertFalse(w.walkable(1, 1, 0));
    assertTrue(w.walkable(1, 3, 0));
    assertEquals(OptionalInt.of(3), w.groundY(1, 0));
    assertEquals(OptionalInt.of(1), w.groundY(0, 0));
  }

  @Test
  void fluidIsNeitherStandableNorPassable() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        #~#
        y=1
        ..~
        """);
    assertFalse(w.solid(1, 0, 0));
    assertFalse(w.passable(1, 0, 0));
    assertFalse(w.walkable(1, 1, 0));
    assertEquals(OptionalInt.empty(), w.groundY(1, 0));
    assertTrue(w.walkable(0, 1, 0));
    assertFalse(w.walkable(2, 1, 0), "fluid in feet block");
  }

  @Test
  void markersRecordedAtWrittenLayer() {
    FixtureWorld w = FixtureWorld.parse("""
        y=5
        ####
        ####
        y=6
        S...
        ...G
        """);
    assertEquals(new GridPos(0, 6, 0), w.marker('S'));
    assertEquals(new GridPos(3, 6, 1), w.marker('G'));
    assertEquals(Map.of('S', new GridPos(0, 6, 0), 'G', new GridPos(3, 6, 1)), w.markers());
    assertTrue(w.walkable(0, 6, 0));
    assertTrue(w.walkable(3, 6, 1));
    assertTrue(w.passable(0, 6, 0));
    assertThrows(NoSuchElementException.class, () -> w.marker('X'));
    assertThrows(UnsupportedOperationException.class,
        () -> w.markers().put('Z', new GridPos(0, 0, 0)));
  }

  @Test
  void outOfBoundsIsFalseOrEmpty() {
    FixtureWorld w = FixtureWorld.parse("""
        y=0
        ##
        ##
        """);
    assertFalse(w.walkable(-1, 1, 0));
    assertFalse(w.walkable(2, 1, 0));
    assertFalse(w.walkable(0, 1, -1));
    assertFalse(w.walkable(0, 1, 2));
    assertFalse(w.solid(-1, 0, 0));
    assertFalse(w.passable(-1, 1, 0));
    assertFalse(w.solid(0, -1, 0));
    assertFalse(w.passable(0, 3, 0));
    assertFalse(w.walkable(0, 100, 0));
    assertFalse(w.walkable(0, -100, 0));
    assertEquals(OptionalInt.empty(), w.groundY(5, 5));
    assertEquals(OptionalInt.empty(), w.groundY(-1, 0));
  }

  @Test
  void layersInAnyOrderAndCrlf() {
    FixtureWorld w = FixtureWorld.parse("y=3\r\n#.\r\ny=-2\r\n##\r\n");
    assertEquals(-2, w.minY());
    assertEquals(5, w.maxY());
    assertTrue(w.walkable(0, 4, 0));
    assertTrue(w.walkable(1, -1, 0));
    assertEquals(OptionalInt.of(-1), w.groundY(1, 0));
  }

  static Stream<Arguments> malformed() {
    return Stream.of(
        Arguments.of("invalid char", "y=0\n#x#\n", 2),
        Arguments.of("lowercase marker", "y=0\n;c\n#a#\n", 3),
        Arguments.of("ragged row", "y=0\n###\n##\n", 3),
        Arguments.of("layer width mismatch", "y=0\n###\ny=1\n####\n", 4),
        Arguments.of("layer depth mismatch", "y=0\n###\n###\ny=1\n###\n", 4),
        Arguments.of("empty layer", "y=0\n###\n\ny=1\n", 4),
        Arguments.of("duplicate y", "y=0\n#\n; again\ny=0\n#\n", 4),
        Arguments.of("duplicate marker", "y=0\nS#\ny=1\n.S\n", 4),
        Arguments.of("rows before header", "; comment\n\n###\ny=0\n###\n", 3));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformed")
  void malformedInputReportsLineNumber(String name, String text, int line) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> FixtureWorld.parse(text));
    assertTrue(e.getMessage().startsWith("line " + line + ":"),
        () -> "expected line " + line + " but got: " + e.getMessage());
  }

  @Test
  void noLayersRejected() {
    assertThrows(IllegalArgumentException.class, () -> FixtureWorld.parse("; nothing\n"));
  }
}
