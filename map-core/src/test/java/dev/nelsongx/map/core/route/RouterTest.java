package dev.nelsongx.map.core.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.BuildingCategory;
import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData;
import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.RoadClass;
import dev.nelsongx.map.core.feature.Vertex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RouterTest {
  private static final Actor ACTOR = new Actor(new UUID(0, 1), "Steve");
  private static final Instant T = Instant.parse("2026-09-15T10:00:00Z");
  private static final double EPS = 1e-9;

  private final List<Feature> features = new ArrayList<>();
  private RouterOptions options = RouterOptions.defaults();

  // ---- helpers -------------------------------------------------------------------------------

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

  private String add(FeatureData data) {
    String id = String.format("f_%06d", features.size());
    features.add(new Feature(id, "minecraft:overworld", 1, data, ACTOR, T, ACTOR, T));
    return id;
  }

  private String road(String name, RoadClass cls, int... xz) {
    return add(new RoadData(vs(xz), name, cls));
  }

  private String railway(String name, int... xz) {
    return add(new RailwayData(vs(xz), name, "#ff0000"));
  }

  private void station(String name, String railwayId, int x, int z) {
    add(new StationData(v(x, z), name, railwayId));
  }

  private RouteResult.Found found(Vertex from, Vertex to) {
    RouteResult r = Router.route(Network.build(features), from, to, options);
    RouteResult.Found f =
        assertInstanceOf(RouteResult.Found.class, r, () -> "expected Found but got " + r);
    assertWellFormed(f, from, to);
    return f;
  }

  private static void assertWellFormed(RouteResult.Found f, Vertex from, Vertex to) {
    List<Leg> legs = f.legs();
    assertFalse(legs.isEmpty());
    assertEquals(from, legs.get(0).points().get(0));
    List<Vertex> lastPoints = legs.get(legs.size() - 1).points();
    assertEquals(to, lastPoints.get(lastPoints.size() - 1));
    double distance = 0;
    double duration = 0;
    for (int i = 0; i < legs.size(); i++) {
      Leg leg = legs.get(i);
      assertTrue(leg.points().size() >= 2, leg::toString);
      if (leg.points().size() > 2) {
        for (int p = 1; p < leg.points().size(); p++) {
          assertFalse(leg.points().get(p).equals(leg.points().get(p - 1)), leg::toString);
        }
      }
      assertTrue(leg.distance() > 0 || legs.size() == 1, leg::toString);
      distance += leg.distance();
      duration += leg.duration();
      switch (leg.mode()) {
        case WALK -> {
          assertNull(leg.name());
          assertNull(leg.fromStation());
          assertNull(leg.toStation());
        }
        case ROAD -> {
          assertNotNull(leg.name());
          assertNull(leg.fromStation());
        }
        case RAIL -> {
          assertNotNull(leg.name());
          assertNotNull(leg.fromStation(), leg::toString);
          assertNotNull(leg.toStation(), leg::toString);
        }
      }
      if (i > 0) {
        Leg prev = legs.get(i - 1);
        assertEquals(prev.points().get(prev.points().size() - 1), leg.points().get(0));
        assertFalse(prev.mode() == Mode.WALK && leg.mode() == Mode.WALK, "unmerged walk legs");
        assertFalse(
            prev.mode() == Mode.ROAD && leg.mode() == Mode.ROAD && prev.name().equals(leg.name()),
            "unmerged road legs");
      }
    }
    assertEquals(distance, f.distance(), 1e-6);
    assertEquals(duration, f.duration(), 1e-6);
  }

  private static List<Mode> modes(RouteResult.Found f) {
    return f.legs().stream().map(Leg::mode).toList();
  }

  // ---- tests ---------------------------------------------------------------------------------

  @Nested
  class Basics {
    @Test
    void modeWireNames() {
      for (Mode m : Mode.values()) {
        assertEquals(Optional.of(m), Mode.fromWire(m.wireName()));
      }
      assertEquals("walk", Mode.WALK.wireName());
      assertEquals(Optional.empty(), Mode.fromWire("bus"));
    }

    @Test
    void defaults() {
      Speeds s = Speeds.defaults();
      assertEquals(4.317, s.walk());
      assertEquals(4.317, s.road(RoadClass.PATH));
      assertEquals(5.612, s.road(RoadClass.STREET));
      assertEquals(7.0, s.road(RoadClass.MAIN));
      assertEquals(9.0, s.road(RoadClass.HIGHWAY));
      assertEquals(8.0, s.rail());
      RouterOptions o = RouterOptions.defaults();
      assertEquals(EnumSet.allOf(Mode.class), o.modes());
      assertEquals(256, o.maxAccessWalk());
      assertEquals(4, o.accessCandidates());
      assertEquals(32, o.transferWalk());
      assertEquals(32, o.stationRoadLink());
      assertEquals(Double.POSITIVE_INFINITY, o.maxDirectWalk());
      assertThrows(UnsupportedOperationException.class, () -> o.modes().add(Mode.WALK));
    }

    @Test
    void invalidOptionsRejected() {
      assertThrows(IllegalArgumentException.class, () -> new Speeds(0, 1, 1, 1, 1, 1));
      assertThrows(
          IllegalArgumentException.class, () -> new Speeds(1, 1, 1, 1, Double.NaN, 1));
      assertThrows(
          IllegalArgumentException.class, () -> RouterOptions.defaults().withMaxAccessWalk(-1));
      assertThrows(
          IllegalArgumentException.class, () -> RouterOptions.defaults().withAccessCandidates(-1));
    }

    @Test
    void emptyNetworkIsDirectWalk() {
      RouteResult.Found f = found(v(0, 0), v(30, 40));
      assertEquals(1, f.legs().size());
      Leg leg = f.legs().get(0);
      assertEquals(Mode.WALK, leg.mode());
      assertEquals(List.of(v(0, 0), v(30, 40)), leg.points());
      assertEquals(50, f.distance(), EPS);
      assertEquals(50 / 4.317, f.duration(), EPS);
    }

    @Test
    void sameStartAndGoal() {
      road("A", RoadClass.HIGHWAY, 0, 0, 100, 0);
      RouteResult.Found f = found(v(5, 5), v(5, 5));
      assertEquals(
          List.of(new Leg(Mode.WALK, null, List.of(v(5, 5), v(5, 5)), 0, 0, null, null)),
          f.legs());
      assertEquals(0, f.duration());
    }

    @Test
    void buildingsIgnored() {
      add(new BuildingData(vs(0, 0, 10, 0, 0, 10), "", BuildingCategory.OTHER, ""));
      Network n = Network.build(features);
      assertEquals(0, n.roadSegmentCount());
      assertEquals(0, n.stationCount());
    }

    @Test
    void networkCounts() {
      String red = railway("Red", 0, 0, 100, 0);
      road("A", RoadClass.MAIN, 0, 0, 10, 0, 10, 10);
      road("B", RoadClass.MAIN, 10, 10, 20, 10);
      station("S", red, 100, 0);
      station("Off", red, 50, 0); // not on a vertex: ignored
      Network n = Network.build(features);
      assertEquals(4, n.roadNodeCount());
      assertEquals(3, n.roadSegmentCount());
      assertEquals(1, n.railwayCount());
      assertEquals(1, n.stationCount());
    }
  }

  @Nested
  class Roads {
    @Test
    void directWalkWinsWhenShort() {
      road("A1", RoadClass.HIGHWAY, 0, 0, 1000, 0);
      RouteResult.Found f = found(v(100, 10), v(130, 10));
      assertEquals(List.of(Mode.WALK), modes(f));
    }

    @Test
    void roadPreferredWhenFasterOverLongTrip() {
      road("A1", RoadClass.HIGHWAY, 0, 0, 2000, 0);
      RouteResult.Found f = found(v(0, 20), v(2000, 20));
      assertEquals(List.of(Mode.WALK, Mode.ROAD, Mode.WALK), modes(f));
      assertEquals(List.of(v(0, 20), v(0, 0)), f.legs().get(0).points());
      Leg road = f.legs().get(1);
      assertEquals("A1", road.name());
      assertEquals(List.of(v(0, 0), v(2000, 0)), road.points());
      assertEquals(2000, road.distance(), EPS);
      assertEquals(2000 / 9.0, road.duration(), EPS);
      assertEquals(2040, f.distance(), EPS);
      assertEquals(40 / 4.317 + 2000 / 9.0, f.duration(), 1e-6);
    }

    @Test
    void accessAndEgressProjectOntoSameSegment() {
      road("A1", RoadClass.HIGHWAY, 0, 0, 2000, 0);
      RouteResult.Found f = found(v(500, 10), v(1500, -10));
      assertEquals(List.of(Mode.WALK, Mode.ROAD, Mode.WALK), modes(f));
      assertEquals(List.of(v(500, 10), v(500, 0)), f.legs().get(0).points());
      assertEquals(List.of(v(500, 0), v(1500, 0)), f.legs().get(1).points());
      assertEquals(List.of(v(1500, 0), v(1500, -10)), f.legs().get(2).points());
    }

    @Test
    void projectedPointsAreRoundedToIntegerBlocks() {
      road("Slant", RoadClass.HIGHWAY, 0, 0, 3000, 7);
      RouteResult.Found f = found(v(1000, 40), v(2500, -40));
      assertEquals(List.of(Mode.WALK, Mode.ROAD, Mode.WALK), modes(f));
      Vertex entry = f.legs().get(1).points().get(0);
      assertEquals(1000, entry.x(), 1);
      assertEquals(2, entry.z(), 1);
    }

    @Test
    void roadExcludedFallsBackToWalk() {
      road("A1", RoadClass.HIGHWAY, 0, 0, 2000, 0);
      options = options.withModes(EnumSet.of(Mode.WALK, Mode.RAIL));
      assertEquals(List.of(Mode.WALK), modes(found(v(0, 20), v(2000, 20))));
    }

    @Test
    void emptyModesStillWalk() {
      road("A1", RoadClass.HIGHWAY, 0, 0, 2000, 0);
      options = options.withModes(EnumSet.noneOf(Mode.class));
      assertEquals(List.of(Mode.WALK), modes(found(v(0, 20), v(2000, 20))));
    }

    @Test
    void crossingWithoutSharedVertexDoesNotConnect() {
      road("A", RoadClass.HIGHWAY, -500, 0, 500, 0);
      road("B", RoadClass.HIGHWAY, 0, -500, 0, 500);
      RouteResult.Found f = found(v(-500, 0), v(0, 500));
      assertEquals(List.of(Mode.WALK), modes(f));

      options = options.withMaxDirectWalk(100);
      RouteResult r = Router.route(Network.build(features), v(-500, 0), v(0, 500), options);
      assertInstanceOf(RouteResult.NoPath.class, r);
    }

    @Test
    void sharedVertexIsJunction() {
      road("A", RoadClass.HIGHWAY, -500, 0, 0, 0, 500, 0);
      road("B", RoadClass.HIGHWAY, 0, -500, 0, 0, 0, 500);
      RouteResult.Found f = found(v(-500, 0), v(0, 500));
      assertEquals(List.of(Mode.ROAD, Mode.ROAD), modes(f));
      assertEquals("A", f.legs().get(0).name());
      assertEquals(List.of(v(-500, 0), v(0, 0)), f.legs().get(0).points());
      assertEquals("B", f.legs().get(1).name());
      assertEquals(List.of(v(0, 0), v(0, 500)), f.legs().get(1).points());
      assertEquals(1000 / 9.0, f.duration(), 1e-9);
    }

    @Test
    void sameNamedRoadsMergeAndNameChangeSplits() {
      road("Main", RoadClass.MAIN, 0, 0, 500, 0);
      road("Main", RoadClass.HIGHWAY, 500, 0, 1000, 0);
      road("Side", RoadClass.HIGHWAY, 1000, 0, 1000, 500);
      RouteResult.Found f = found(v(0, 0), v(1000, 500));
      assertEquals(List.of(Mode.ROAD, Mode.ROAD), modes(f));
      Leg main = f.legs().get(0);
      assertEquals("Main", main.name());
      assertEquals(List.of(v(0, 0), v(500, 0), v(1000, 0)), main.points());
      assertEquals(1000, main.distance(), EPS);
      assertEquals(500 / 7.0 + 500 / 9.0, main.duration(), 1e-9);
      assertEquals("Side", f.legs().get(1).name());
    }

    @Test
    void slowerClassAvoidedWhenAlternativeFaster() {
      // Two parallel routes from (0,0) to (1000,0): a direct path and a highway detour.
      road("Trail", RoadClass.PATH, 0, 0, 1000, 0);
      road("Bypass", RoadClass.HIGHWAY, 0, 0, 0, 100, 1000, 100, 1000, 0);
      options = options.withMaxDirectWalk(0);
      RouteResult.Found f = found(v(0, 0), v(1000, 0));
      assertEquals(List.of(Mode.ROAD), modes(f));
      assertEquals("Bypass", f.legs().get(0).name());
    }

    @Test
    void featureOrderDoesNotChangeResult() {
      road("A", RoadClass.STREET, 0, 0, 100, 0, 100, 100);
      road("B", RoadClass.STREET, 0, 0, 0, 100, 100, 100);
      options = options.withMaxDirectWalk(0);
      RouteResult first = Router.route(Network.build(features), v(0, 0), v(100, 100), options);
      List<Feature> reversed = new ArrayList<>(features);
      Collections.reverse(reversed);
      RouteResult second = Router.route(Network.build(reversed), v(0, 0), v(100, 100), options);
      assertEquals(first, second);
    }
  }

  @Nested
  class Rail {
    @Test
    void railBoardedOnlyAtStations() {
      String red = railway("Red", 0, 0, 3000, 0);
      station("East", red, 3000, 0);
      // A road junction on the railway vertex is not an access point either.
      road("Link", RoadClass.HIGHWAY, 0, 10, 0, 0);
      RouteResult.Found f = found(v(0, 5), v(3000, 5));
      assertFalse(modes(f).contains(Mode.RAIL), f.toString());
    }

    @Test
    void railUsedViaStations() {
      String red = railway("Red", 0, 0, 3000, 0);
      station("West", red, 0, 0);
      station("East", red, 3000, 0);
      RouteResult.Found f = found(v(0, 5), v(3000, 5));
      assertEquals(List.of(Mode.WALK, Mode.RAIL, Mode.WALK), modes(f));
      Leg rail = f.legs().get(1);
      assertEquals("Red", rail.name());
      assertEquals("West", rail.fromStation());
      assertEquals("East", rail.toStation());
      assertEquals(List.of(v(0, 0), v(3000, 0)), rail.points());
      assertEquals(3000 / 8.0, rail.duration(), EPS);
    }

    @Test
    void railExcludedFallsBackToWalk() {
      String red = railway("Red", 0, 0, 3000, 0);
      station("West", red, 0, 0);
      station("East", red, 3000, 0);
      options = options.withModes(EnumSet.of(Mode.ROAD));
      assertEquals(List.of(Mode.WALK), modes(found(v(0, 5), v(3000, 5))));
    }

    @Test
    void rideThroughIntermediateStationIsOneLeg() {
      String red = railway("Red", 0, 0, 1000, 0, 2000, 0, 3000, 0);
      station("West", red, 0, 0);
      station("Mid", red, 1000, 0);
      station("Centre", red, 2000, 0);
      station("East", red, 3000, 0);
      RouteResult.Found f = found(v(0, 0), v(3000, 0));
      assertEquals(List.of(Mode.RAIL), modes(f));
      assertEquals("West", f.legs().get(0).fromStation());
      assertEquals("East", f.legs().get(0).toStation());
      assertEquals(List.of(v(0, 0), v(1000, 0), v(2000, 0), v(3000, 0)), f.legs().get(0).points());
    }

    @Test
    void transferAtSharedStationVertex() {
      String red = railway("Red", 0, 0, 2000, 0);
      String blue = railway("Blue", 2000, 0, 2000, 2000);
      station("West", red, 0, 0);
      station("Junction", red, 2000, 0); // Blue also has a vertex here: interchange
      station("North", blue, 2000, 2000);
      RouteResult.Found f = found(v(0, 0), v(2000, 2000));
      assertEquals(List.of(Mode.RAIL, Mode.RAIL), modes(f));
      Leg first = f.legs().get(0);
      Leg second = f.legs().get(1);
      assertEquals("Red", first.name());
      assertEquals("West", first.fromStation());
      assertEquals("Junction", first.toStation());
      assertEquals("Blue", second.name());
      assertEquals("Junction", second.fromStation());
      assertEquals("North", second.toStation());
      assertEquals(4000 / 8.0, f.duration(), EPS);
    }

    @Test
    void walkTransferBetweenNearbyStations() {
      String red = railway("Red", 0, 0, 2000, 0);
      String blue = railway("Blue", 2020, 0, 2020, 2000);
      station("West", red, 0, 0);
      station("Red Junction", red, 2000, 0);
      station("Blue Junction", blue, 2020, 0);
      station("North", blue, 2020, 2000);
      RouteResult.Found f = found(v(0, 0), v(2020, 2000));
      assertEquals(List.of(Mode.RAIL, Mode.WALK, Mode.RAIL), modes(f));
      assertEquals(List.of(v(2000, 0), v(2020, 0)), f.legs().get(1).points());
      assertEquals("Blue Junction", f.legs().get(2).fromStation());

      options = options.withTransferWalk(10);
      assertFalse(modes(found(v(0, 0), v(2020, 2000))).contains(Mode.RAIL));
    }

    @Test
    void stationLinkedToNearestRoadVertex() {
      road("R", RoadClass.HIGHWAY, 0, 0, 3000, 0);
      String line = railway("North Line", 3000, 20, 3000, 3000);
      station("S1", line, 3000, 20);
      station("S2", line, 3000, 3000);
      RouteResult.Found f = found(v(0, 0), v(3000, 3000));
      assertEquals(List.of(Mode.ROAD, Mode.WALK, Mode.RAIL), modes(f));
      assertEquals(List.of(v(3000, 0), v(3000, 20)), f.legs().get(1).points());

      options = options.withStationRoadLink(10);
      assertFalse(modes(found(v(0, 0), v(3000, 3000))).contains(Mode.RAIL));
    }
  }

  @Nested
  class Search {
    @Test
    void aStarMatchesDijkstraOnRandomNetworks() {
      int railRoutes = 0;
      int roadRoutes = 0;
      int noPath = 0;
      for (long seed = 1; seed <= 5; seed++) {
        Random rnd = new Random(seed);
        features.clear();
        buildRandomNetwork(rnd);
        Network net = Network.build(features);
        for (RouterOptions o :
            List.of(
                RouterOptions.defaults(),
                RouterOptions.defaults().withMaxDirectWalk(300).withMaxAccessWalk(80))) {
          for (int q = 0; q < 60; q++) {
            Vertex from = v(rnd.nextInt(2200) - 100, rnd.nextInt(2200) - 100);
            Vertex to = v(rnd.nextInt(2200) - 100, rnd.nextInt(2200) - 100);
            RouteResult astar = Router.route(net, from, to, o, true);
            RouteResult dijkstra = Router.route(net, from, to, o, false);
            assertEquals(astar.getClass(), dijkstra.getClass(), "seed " + seed + " query " + q);
            if (astar instanceof RouteResult.Found fa) {
              RouteResult.Found fd = (RouteResult.Found) dijkstra;
              assertEquals(fd.duration(), fa.duration(), 1e-6, "seed " + seed + " query " + q);
              assertWellFormed(fa, from, to);
              assertWellFormed(fd, from, to);
              railRoutes += modes(fa).contains(Mode.RAIL) ? 1 : 0;
              roadRoutes += modes(fa).contains(Mode.ROAD) ? 1 : 0;
            } else {
              noPath++;
            }
          }
        }
      }
      // The random workload must actually exercise every kind of outcome.
      assertTrue(railRoutes > 0, "rail routes: " + railRoutes);
      assertTrue(roadRoutes > 0, "road routes: " + roadRoutes);
      assertTrue(noPath > 0, "no-path results: " + noPath);
    }

    private void buildRandomNetwork(Random rnd) {
      RoadClass[] classes = RoadClass.values();
      for (int i = 0; i < 150; i++) {
        List<Vertex> line = randomLatticeWalk(rnd, 2 + rnd.nextInt(5));
        add(new RoadData(line, "Road " + rnd.nextInt(10), classes[rnd.nextInt(classes.length)]));
      }
      for (int i = 0; i < 8; i++) {
        List<Vertex> line = randomLatticeWalk(rnd, 3 + rnd.nextInt(6));
        String id = add(new RailwayData(line, "Line " + i, "#00ff00"));
        for (Vertex p : line) {
          if (rnd.nextBoolean()) {
            add(new StationData(p, "Station " + i + "@" + p.x() + "," + p.z(), id));
          }
        }
      }
    }

    private List<Vertex> randomLatticeWalk(Random rnd, int n) {
      int x = rnd.nextInt(51);
      int z = rnd.nextInt(51);
      List<Vertex> line = new ArrayList<>();
      line.add(v(x * 40, z * 40));
      while (line.size() < n) {
        int step = (1 + rnd.nextInt(3)) * (rnd.nextBoolean() ? 1 : -1);
        if (rnd.nextBoolean()) {
          x = Math.max(0, Math.min(50, x + step));
        } else {
          z = Math.max(0, Math.min(50, z + step));
        }
        Vertex next = v(x * 40, z * 40);
        if (!next.equals(line.get(line.size() - 1))) {
          line.add(next);
        }
      }
      return line;
    }

    @Test
    void twentyThousandSegmentsRouteQuickly() {
      int n = 101; // 101 x 101 lattice, spacing 20: 100 rows + 101 columns of 100 segments
      for (int i = 0; i < n; i++) {
        int[] row = new int[n * 2];
        int[] col = new int[n * 2];
        for (int j = 0; j < n; j++) {
          row[2 * j] = j * 20;
          row[2 * j + 1] = i * 20;
          col[2 * j] = i * 20;
          col[2 * j + 1] = j * 20;
        }
        road("Row " + i, RoadClass.HIGHWAY, row);
        road("Col " + i, RoadClass.HIGHWAY, col);
      }
      Network net = Network.build(features);
      assertTrue(net.roadSegmentCount() >= 20_000, "segments: " + net.roadSegmentCount());

      RouterOptions noDirect = RouterOptions.defaults().withMaxDirectWalk(0);
      Router.route(net, v(3, 3), v(1997, 1997), noDirect); // warm-up
      for (boolean heuristic : new boolean[] {true, false}) {
        long t0 = System.nanoTime();
        RouteResult r = Router.route(net, v(-10, -10), v(2010, 1990), noDirect, heuristic);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        RouteResult.Found f = assertInstanceOf(RouteResult.Found.class, r);
        assertWellFormed(f, v(-10, -10), v(2010, 1990));
        assertTrue(ms < 2000, "routing took " + ms + " ms (heuristic=" + heuristic + ")");
      }
    }
  }
}
