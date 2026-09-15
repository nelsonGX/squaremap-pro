package dev.nelsongx.map.core.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureValidatorTest {
  private static final FeatureValidator V = new FeatureValidator();
  private static final RailwayLookup NONE = id -> Optional.empty();

  private static Vertex v(int x, int z) {
    return new Vertex(x, z);
  }

  private static List<Vertex> vs(int... xz) {
    List<Vertex> out = new ArrayList<>();
    for (int i = 0; i < xz.length; i += 2) {
      out.add(v(xz[i], xz[i + 1]));
    }
    return out;
  }

  private static List<ValidationError> validate(FeatureData data) {
    return V.validate(data, NONE);
  }

  private static void assertValid(FeatureData data) {
    assertEquals(List.of(), validate(data));
  }

  private static void assertInvalid(FeatureData data, String field) {
    assertInvalid(data, NONE, field);
  }

  private static void assertInvalid(FeatureData data, RailwayLookup lookup, String field) {
    List<ValidationError> errors = V.validate(data, lookup);
    assertFalse(errors.isEmpty(), "expected an error on " + field);
    assertTrue(
        errors.stream().anyMatch(e -> e.field().equals(field)),
        "expected error on " + field + " but got " + errors);
  }

  private static BuildingData building(List<Vertex> ring) {
    return new BuildingData(ring, "House", BuildingCategory.RESIDENTIAL, "");
  }

  private static RoadData road(List<Vertex> line) {
    return new RoadData(line, "Main Street", RoadClass.STREET);
  }

  private static RailwayData railway(List<Vertex> line) {
    return new RailwayData(line, "Red Line", "#ff0000");
  }

  private static String repeat(int n) {
    return "a".repeat(n);
  }

  @Test
  void nullDataIsTypeError() {
    assertEquals(List.of(new ValidationError("type", "feature type is required")), validate(null));
  }

  @Test
  void nullLookupIsProgrammerError() {
    assertThrows(NullPointerException.class, () -> V.validate(road(vs(0, 0, 1, 1)), null));
  }

  @Nested
  class WireNames {
    @ParameterizedTest
    @EnumSource(FeatureType.class)
    void featureTypeRoundTrips(FeatureType t) {
      assertEquals(Optional.of(t), FeatureType.fromWire(t.wireName()));
      assertEquals(t.name().toLowerCase(), t.wireName());
    }

    @ParameterizedTest
    @EnumSource(RoadClass.class)
    void roadClassRoundTrips(RoadClass c) {
      assertEquals(Optional.of(c), RoadClass.fromWire(c.wireName()));
      assertEquals(c.name().toLowerCase(), c.wireName());
    }

    @ParameterizedTest
    @EnumSource(BuildingCategory.class)
    void buildingCategoryRoundTrips(BuildingCategory c) {
      assertEquals(Optional.of(c), BuildingCategory.fromWire(c.wireName()));
      assertEquals(c.name().toLowerCase(), c.wireName());
    }

    @Test
    void unknownNullAndWrongCaseAreEmpty() {
      assertEquals(Optional.empty(), FeatureType.fromWire(null));
      assertEquals(Optional.empty(), FeatureType.fromWire("ROAD"));
      assertEquals(Optional.empty(), RoadClass.fromWire("motorway"));
      assertEquals(Optional.empty(), BuildingCategory.fromWire(""));
    }
  }

  @Nested
  class Model {
    @Test
    void listsAreDefensivelyCopiedAndImmutable() {
      List<Vertex> src = vs(0, 0, 10, 0);
      RoadData r = road(src);
      src.add(v(20, 20));
      assertEquals(2, r.line().size());
      assertThrows(UnsupportedOperationException.class, () -> r.line().add(v(1, 1)));
    }

    @Test
    void nullElementsAreCopiedWithoutNpeButStillImmutable() {
      RoadData r = road(Arrays.asList(v(0, 0), null));
      assertEquals(2, r.line().size());
      assertThrows(UnsupportedOperationException.class, () -> r.line().add(v(1, 1)));
      assertInvalid(r, "geometry");
    }

    @Test
    void namesAreStrippedAndBuildingNullsBecomeEmpty() {
      assertEquals("Main", new RoadData(vs(0, 0, 1, 0), "  Main \t", RoadClass.MAIN).name());
      BuildingData b = new BuildingData(vs(0, 0, 1, 0, 0, 1), null, BuildingCategory.OTHER, null);
      assertEquals("", b.name());
      assertEquals("", b.description());
      assertEquals(null, new StationData(v(0, 0), null, "x").name());
    }

    @Test
    void typeAndGeometry() {
      assertEquals(FeatureType.BUILDING, building(vs(0, 0, 1, 0, 0, 1)).type());
      assertEquals(FeatureType.ROAD, road(vs(0, 0, 1, 0)).type());
      assertEquals(FeatureType.RAILWAY, railway(vs(0, 0, 1, 0)).type());
      StationData s = new StationData(v(3, 4), "S", "r");
      assertEquals(FeatureType.STATION, s.type());
      assertEquals(List.of(v(3, 4)), s.geometry());
      assertEquals(List.of(), new StationData(null, "S", "r").geometry());
    }

    @Test
    void featureRequiresMetadata() {
      Actor a = new Actor(UUID.randomUUID(), "Steve");
      Instant t = Instant.parse("2026-09-15T10:00:00Z");
      Feature f = new Feature("f_1", "minecraft:overworld", 1, road(vs(0, 0, 1, 0)), a, t, a, t);
      assertEquals(FeatureType.ROAD, f.type());
      assertThrows(
          NullPointerException.class,
          () -> new Feature("f_1", "minecraft:overworld", 1, null, a, t, a, t));
      assertThrows(NullPointerException.class, () -> new Actor(null, "x"));
    }
  }

  @Nested
  class Names {
    @Test
    void roadRailwayStationNeedNonBlankName() {
      assertInvalid(new RoadData(vs(0, 0, 1, 0), null, RoadClass.MAIN), "name");
      assertInvalid(new RoadData(vs(0, 0, 1, 0), "   ", RoadClass.MAIN), "name");
      assertInvalid(new RailwayData(vs(0, 0, 1, 0), "", "#000000"), "name");
      RailwayLookup lookup = id -> Optional.of(railway(vs(0, 0, 1, 0)));
      assertInvalid(new StationData(v(0, 0), " ", "r"), lookup, "name");
    }

    @Test
    void buildingMayBeUnnamed() {
      assertValid(new BuildingData(vs(0, 0, 4, 0, 0, 4), null, BuildingCategory.PUBLIC, null));
      assertValid(new BuildingData(vs(0, 0, 4, 0, 0, 4), "  ", BuildingCategory.PUBLIC, ""));
    }

    @Test
    void nameLengthLimitAppliesAfterStripping() {
      assertValid(new RoadData(vs(0, 0, 1, 0), "  " + repeat(64) + "  ", RoadClass.MAIN));
      assertInvalid(new RoadData(vs(0, 0, 1, 0), repeat(65), RoadClass.MAIN), "name");
      assertInvalid(
          new BuildingData(vs(0, 0, 4, 0, 0, 4), repeat(65), BuildingCategory.OTHER, ""), "name");
    }

    @Test
    void lengthCountsCodePointsNotUtf16Units() {
      String emoji = "🚂".repeat(64); // 64 code points, 128 chars
      assertValid(new RoadData(vs(0, 0, 1, 0), emoji, RoadClass.MAIN));
    }

    @Test
    void descriptionLimit() {
      assertValid(new BuildingData(vs(0, 0, 4, 0, 0, 4), "", BuildingCategory.OTHER, repeat(1000)));
      assertInvalid(
          new BuildingData(vs(0, 0, 4, 0, 0, 4), "", BuildingCategory.OTHER, repeat(1001)),
          "description");
    }
  }

  @Nested
  class Enums {
    @Test
    void nullCategoryAndRoadClassAreErrors() {
      assertInvalid(new BuildingData(vs(0, 0, 4, 0, 0, 4), "", null, ""), "category");
      assertInvalid(new RoadData(vs(0, 0, 1, 0), "Road", null), "roadClass");
    }
  }

  @Nested
  class Coordinates {
    @Test
    void limitsAreInclusive() {
      assertValid(road(vs(30_000_000, -30_000_000, -30_000_000, 30_000_000)));
      assertValid(building(vs(-30_000_000, -30_000_000, 30_000_000, -30_000_000, 30_000_000, 30_000_000)));
    }

    @Test
    void outOfRangeRejected() {
      assertInvalid(road(vs(0, 0, 30_000_001, 0)), "geometry");
      assertInvalid(road(vs(0, -30_000_001, 1, 0)), "geometry");
      assertInvalid(road(vs(Integer.MIN_VALUE, 0, 1, 0)), "geometry");
      assertInvalid(building(vs(0, 0, 4, 0, 0, Integer.MAX_VALUE)), "geometry");
    }

    @Test
    void stationOutOfRange() {
      RailwayLookup lookup = id -> Optional.of(railway(vs(0, 0, 1, 0)));
      assertInvalid(new StationData(v(40_000_000, 0), "S", "r"), lookup, "geometry");
    }

    @Test
    void extremeLargeBuildingIsExact() {
      int m = 30_000_000;
      assertValid(building(vs(-m, -m, m, -m, m, m, -m, m)));
      // Thin sliver at max extent: non-zero area computed exactly.
      assertValid(building(vs(-m, -m, m, m, m - 1, m)));
    }
  }

  @Nested
  class Polylines {
    @Test
    void validRoadAndRailway() {
      assertValid(road(vs(0, 0, 10, 0, 10, 10)));
      assertValid(railway(vs(0, 0, 10, 0)));
    }

    @Test
    void selfCrossingAndLoopsAreAllowed() {
      assertValid(road(vs(0, 0, 10, 10, 10, 0, 0, 10)));
      assertValid(road(vs(0, 0, 10, 0, 10, 10, 0, 0)));
    }

    @Test
    void nullOrTooFewVertices() {
      assertInvalid(road(null), "geometry");
      assertInvalid(road(List.of()), "geometry");
      assertInvalid(road(vs(0, 0)), "geometry");
      assertInvalid(railway(vs(5, 5)), "geometry");
    }

    @Test
    void consecutiveDuplicatesRejected() {
      assertInvalid(road(vs(0, 0, 0, 0)), "geometry");
      assertInvalid(railway(vs(0, 0, 5, 5, 5, 5, 9, 9)), "geometry");
    }

    @Test
    void maxVertices() {
      List<Vertex> ok = IntStream.range(0, 2000).mapToObj(i -> v(i, 0)).toList();
      assertValid(road(ok));
      List<Vertex> tooMany = IntStream.range(0, 2001).mapToObj(i -> v(i, 0)).toList();
      assertInvalid(road(tooMany), "geometry");
      assertInvalid(railway(tooMany), "geometry");
    }

    @ParameterizedTest
    @ValueSource(strings = {"#ff00aa", "#FF00AA", "#0a1B2c"})
    void validColours(String colour) {
      assertValid(new RailwayData(vs(0, 0, 1, 0), "L", colour));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ff00aa", "#ff00a", "#ff00aa0", "#gg00aa", " #ff00aa", "#ff00aa\n"})
    void invalidColours(String colour) {
      assertInvalid(new RailwayData(vs(0, 0, 1, 0), "L", colour), "colour");
    }

    @Test
    void nullColour() {
      assertInvalid(new RailwayData(vs(0, 0, 1, 0), "L", null), "colour");
    }
  }

  @Nested
  class Buildings {
    @Test
    void validTriangleSquareAndBothOrientations() {
      assertValid(building(vs(0, 0, 10, 0, 0, 10)));
      assertValid(building(vs(0, 0, 10, 0, 10, 10, 0, 10)));
      assertValid(building(vs(0, 0, 0, 10, 10, 10, 10, 0)));
    }

    @Test
    void validLShape() {
      assertValid(building(vs(0, 0, 10, 0, 10, 4, 4, 4, 4, 10, 0, 10)));
    }

    @Test
    void validConcaveWithCollinearNonOverlappingVertex() {
      // Vertex (5,0) sits straight on the bottom edge: collinear but not folding back.
      assertValid(building(vs(0, 0, 5, 0, 10, 0, 10, 10, 5, 3, 0, 10)));
    }

    @Test
    void nullOrTooFewVertices() {
      assertInvalid(building(null), "geometry");
      assertInvalid(building(vs(0, 0, 1, 1)), "geometry");
    }

    @Test
    void nullVertex() {
      assertInvalid(building(Arrays.asList(v(0, 0), null, v(0, 5))), "geometry");
    }

    @Test
    void closedRingRejected() {
      assertInvalid(building(vs(0, 0, 10, 0, 0, 10, 0, 0)), "geometry");
    }

    @Test
    void zeroLengthEdgeRejected() {
      assertInvalid(building(vs(0, 0, 10, 0, 10, 0, 0, 10)), "geometry");
    }

    @Test
    void fewerThanThreeDistinctRejected() {
      assertInvalid(building(vs(0, 0, 5, 5, 0, 0, 5, 5)), "geometry");
    }

    @Test
    void bowTieRejected() {
      List<ValidationError> errors = validate(building(vs(0, 0, 10, 10, 10, 0, 0, 10)));
      assertEquals(1, errors.size());
      assertEquals("geometry", errors.get(0).field());
      assertTrue(errors.get(0).message().contains("intersect"), errors.toString());
    }

    @Test
    void touchingAtVertexOfNonAdjacentEdgesRejected() {
      // Figure-eight: two triangles sharing vertex (5,5), visited twice.
      assertInvalid(building(vs(0, 0, 5, 5, 10, 0, 10, 10, 5, 5, 0, 10)), "geometry");
    }

    @Test
    void vertexTouchingInteriorOfNonAdjacentEdgeRejected() {
      // Vertex 3 (5,0) lies in the interior of edge 0 (0,0)-(10,0).
      assertInvalid(building(vs(0, 0, 10, 0, 10, 10, 5, 0, 0, 10)), "geometry");
    }

    @Test
    void closingEdgeIntersectionRejected() {
      // Closing edge (5,10)->(10,0) crosses edge 1 (10,10)->(0,0).
      assertInvalid(building(vs(10, 0, 10, 10, 0, 0, 5, 10)), "geometry");
    }

    @Test
    void collinearOverlapOfAdjacentEdgesRejected() {
      // Spike folding back along itself: (0,0)->(10,0)->(5,0) overlaps.
      assertInvalid(building(vs(0, 0, 10, 0, 5, 0, 5, 5)), "geometry");
    }

    @Test
    void collinearOverlapAcrossClosingEdgeRejected() {
      // Closing edge (5,0)->(0,0) overlaps first edge (0,0)->(10,0).
      assertInvalid(building(vs(0, 0, 10, 0, 10, 10, 5, 0)), "geometry");
    }

    @Test
    void collinearOverlapOfNonAdjacentEdgesRejected() {
      assertInvalid(building(vs(0, 0, 10, 0, 10, 5, 6, 5, 6, 0, 3, 0, 3, 5, 0, 5)), "geometry");
    }

    @Test
    void degenerateCollinearTriangleRejected() {
      assertInvalid(building(vs(0, 0, 5, 0, 10, 0)), "geometry");
      assertInvalid(building(vs(0, 0, 10, 0, 5, 0)), "geometry");
    }
  }

  @Nested
  class Stations {
    private final RailwayLookup lookup =
        id -> Optional.ofNullable(Map.of("r1", railway(vs(0, 0, 50, 0, 50, 50))).get(id));

    @Test
    void onVertex() {
      assertEquals(List.of(), V.validate(new StationData(v(50, 0), "Central", "r1"), lookup));
      assertEquals(List.of(), V.validate(new StationData(v(50, 50), "End", "r1"), lookup));
    }

    @Test
    void onSegmentButNotVertexRejected() {
      assertInvalid(new StationData(v(25, 0), "Mid", "r1"), lookup, "geometry");
    }

    @Test
    void offRailwayRejected() {
      assertInvalid(new StationData(v(7, 7), "Nowhere", "r1"), lookup, "geometry");
    }

    @Test
    void missingRailwayRejected() {
      List<ValidationError> errors = V.validate(new StationData(v(0, 0), "S", "r404"), lookup);
      assertEquals(1, errors.size());
      assertEquals("railwayId", errors.get(0).field());
    }

    @Test
    void nullOrBlankRailwayIdRejected() {
      assertInvalid(new StationData(v(0, 0), "S", null), lookup, "railwayId");
      assertInvalid(new StationData(v(0, 0), "S", " "), lookup, "railwayId");
    }

    @Test
    void nullPointRejected() {
      assertInvalid(new StationData(null, "S", "r1"), lookup, "geometry");
    }

    @Test
    void multipleErrorsReported() {
      List<ValidationError> errors = V.validate(new StationData(null, "", null), lookup);
      assertEquals(3, errors.size(), errors.toString());
    }
  }
}
