package dev.nelsongx.map.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import dev.nelsongx.map.core.feature.ValidationError;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.core.store.HistoryEntry.Action;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureStoreTest {
  private static final String OVERWORLD = "minecraft:overworld";
  private static final String NETHER = "minecraft:the_nether";
  private static final Instant T0 = Instant.parse("2026-09-15T10:00:00.123456789Z");

  private final Actor steve = new Actor(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Steve");
  private final Actor alex = new Actor(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Alex");

  private TestClock clock;
  private FeatureStore store;

  /** Settable clock; per-test instance, not shared. */
  static final class TestClock extends Clock {
    private Instant now;

    TestClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @BeforeEach
  void setUp() {
    clock = new TestClock(T0);
    store = FeatureStore.openInMemory(clock);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

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

  private static BuildingData building() {
    return new BuildingData(
        vs(0, 0, 10, 0, 10, 4, 4, 4, 4, 10, 0, 10), "Town Hall", BuildingCategory.PUBLIC, "Old");
  }

  private static RoadData road() {
    return new RoadData(vs(-30_000_000, 5, 0, 5, 30_000_000, -7), "Main Street", RoadClass.MAIN);
  }

  private static RailwayData redLine() {
    return new RailwayData(vs(0, 0, 50, 0, 50, 50), "Red Line", "#Ff0000");
  }

  private static Feature ok(Result result) {
    return assertInstanceOf(Result.Ok.class, result, () -> "expected Ok but got " + result)
        .feature();
  }

  private static List<ValidationError> invalid(Result result) {
    return assertInstanceOf(Result.Invalid.class, result, () -> "expected Invalid but got " + result)
        .errors();
  }

  private static void assertField(List<ValidationError> errors, String field) {
    assertTrue(errors.stream().anyMatch(e -> e.field().equals(field)), errors::toString);
  }

  private static Connection raw(Path file) throws SQLException {
    return new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
  }

  // ---- tests ---------------------------------------------------------------------------------

  @Nested
  class Crud {
    @Test
    void createAssignsIdRevisionActorsAndTimestamps() {
      Feature f = ok(store.create(OVERWORLD, building(), steve));
      assertTrue(f.id().matches("f_[0-9A-Za-z]{16}"), f.id());
      assertEquals(OVERWORLD, f.worldId());
      assertEquals(1, f.revision());
      assertEquals(building(), f.data());
      assertEquals(steve, f.createdBy());
      assertEquals(steve, f.updatedBy());
      assertEquals(T0, f.createdAt());
      assertEquals(T0, f.updatedAt());
      assertEquals(f, store.get(OVERWORLD, f.id()).orElseThrow());
    }

    @Test
    void everyTypeRoundTripsExactly() {
      Feature b = ok(store.create(OVERWORLD, building(), steve));
      Feature unnamed =
          ok(store.create(
              OVERWORLD,
              new BuildingData(vs(0, 0, 3, 0, 0, 3), null, BuildingCategory.OTHER, null),
              steve));
      Feature r = ok(store.create(OVERWORLD, road(), steve));
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      Feature s =
          ok(store.create(OVERWORLD, new StationData(v(50, 0), "Central", rail.id()), steve));
      assertEquals(List.of(b, unnamed, r, rail, s), store.list(OVERWORLD));
      assertEquals("", unnamed.data().name());
    }

    @Test
    void namesAreStoredStripped() {
      Feature f =
          ok(store.create(OVERWORLD, new RoadData(vs(0, 0, 1, 0), "  Lane  ", RoadClass.PATH), steve));
      assertEquals("Lane", store.get(OVERWORLD, f.id()).orElseThrow().data().name());
    }

    @Test
    void idsAreUnique() {
      Set<String> ids = new HashSet<>();
      for (int i = 0; i < 200; i++) {
        ids.add(ok(store.create(OVERWORLD, road(), steve)).id());
      }
      assertEquals(200, ids.size());
    }

    @Test
    void updateBumpsRevisionAndKeepsCreator() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      clock.advance(Duration.ofMinutes(5));
      RoadData changed = new RoadData(vs(0, 0, 9, 9), "High Street", RoadClass.HIGHWAY);
      Feature u = ok(store.update(OVERWORLD, f.id(), 1, changed, alex));
      assertEquals(2, u.revision());
      assertEquals(changed, u.data());
      assertEquals(steve, u.createdBy());
      assertEquals(T0, u.createdAt());
      assertEquals(alex, u.updatedBy());
      assertEquals(T0.plus(Duration.ofMinutes(5)), u.updatedAt());
      assertEquals(u, store.get(OVERWORLD, f.id()).orElseThrow());

      Feature u2 = ok(store.update(OVERWORLD, f.id(), 2, road(), steve));
      assertEquals(3, u2.revision());
    }

    @Test
    void deleteReturnsDeletedFeature() {
      Feature f = ok(store.create(OVERWORLD, building(), steve));
      assertEquals(f, ok(store.delete(OVERWORLD, f.id(), 1, alex)));
      assertTrue(store.get(OVERWORLD, f.id()).isEmpty());
      assertEquals(List.of(), store.list(OVERWORLD));
    }

    @Test
    void invalidCreateWritesNothing() {
      List<ValidationError> errors =
          invalid(store.create(OVERWORLD, new RoadData(vs(0, 0), "", null), steve));
      assertField(errors, "geometry");
      assertField(errors, "name");
      assertField(errors, "roadClass");
      assertEquals(List.of(), store.list(OVERWORLD));
    }

    @Test
    void nullDataIsInvalidNotNpe() {
      assertField(invalid(store.create(OVERWORLD, null, steve)), "type");
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      assertField(invalid(store.update(OVERWORLD, f.id(), 1, null, steve)), "type");
    }

    @Test
    void invalidUpdateLeavesFeatureUnchanged() {
      Feature f = ok(store.create(OVERWORLD, building(), steve));
      BuildingData bowTie =
          new BuildingData(vs(0, 0, 10, 10, 10, 0, 0, 10), "X", BuildingCategory.OTHER, "");
      assertField(invalid(store.update(OVERWORLD, f.id(), 1, bowTie, alex)), "geometry");
      assertEquals(f, store.get(OVERWORLD, f.id()).orElseThrow());
      assertEquals(1, store.history(OVERWORLD, f.id()).size());
    }

    @Test
    void typeCannotChange() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      List<ValidationError> errors = invalid(store.update(OVERWORLD, f.id(), 1, redLine(), steve));
      assertEquals(1, errors.size());
      assertEquals("type", errors.get(0).field());
      assertEquals(f, store.get(OVERWORLD, f.id()).orElseThrow());
    }
  }

  @Nested
  class NotFoundAndConflict {
    @Test
    void missingIdIsNotFound() {
      assertInstanceOf(Result.NotFound.class, store.update(OVERWORLD, "f_nope", 1, road(), steve));
      assertInstanceOf(Result.NotFound.class, store.delete(OVERWORLD, "f_nope", 1, steve));
      assertTrue(store.get(OVERWORLD, "f_nope").isEmpty());
    }

    @Test
    void deletedIsNotFound() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      ok(store.delete(OVERWORLD, f.id(), 1, steve));
      assertInstanceOf(Result.NotFound.class, store.update(OVERWORLD, f.id(), 1, road(), steve));
      assertInstanceOf(Result.NotFound.class, store.delete(OVERWORLD, f.id(), 1, steve));
    }

    @Test
    void staleUpdateIsConflictWithCurrent() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      Feature u = ok(store.update(OVERWORLD, f.id(), 1, road(), alex));
      Result r =
          store.update(
              OVERWORLD, f.id(), 1, new RoadData(vs(1, 1, 2, 2), "Other", RoadClass.PATH), steve);
      assertEquals(new Result.Conflict(u), r);
      assertEquals(u, store.get(OVERWORLD, f.id()).orElseThrow());
    }

    @Test
    void futureRevisionIsConflict() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      assertEquals(new Result.Conflict(f), store.update(OVERWORLD, f.id(), 2, road(), steve));
    }

    @Test
    void staleDeleteIsConflict() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      Feature u = ok(store.update(OVERWORLD, f.id(), 1, road(), alex));
      assertEquals(new Result.Conflict(u), store.delete(OVERWORLD, f.id(), 1, steve));
      assertTrue(store.get(OVERWORLD, f.id()).isPresent());
    }

    @Test
    void conflictCheckedBeforeValidation() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      assertInstanceOf(
          Result.Conflict.class,
          store.update(OVERWORLD, f.id(), 7, new RoadData(vs(0, 0), "", null), steve));
    }
  }

  @Nested
  class Stations {
    @Test
    void stationNeedsExistingRailway() {
      assertField(
          invalid(store.create(OVERWORLD, new StationData(v(0, 0), "S", "f_missing"), steve)),
          "railwayId");
    }

    @Test
    void stationCannotReferenceNonRailway() {
      Feature r = ok(store.create(OVERWORLD, new RoadData(vs(0, 0, 50, 0), "R", RoadClass.MAIN), steve));
      assertField(
          invalid(store.create(OVERWORLD, new StationData(v(0, 0), "S", r.id()), steve)),
          "railwayId");
    }

    @Test
    void stationMustBeOnRailwayVertex() {
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      assertField(
          invalid(store.create(OVERWORLD, new StationData(v(25, 0), "Mid", rail.id()), steve)),
          "geometry");
      ok(store.create(OVERWORLD, new StationData(v(50, 50), "End", rail.id()), steve));
    }

    @Test
    void deletingRailwayWithStationsRejected() {
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      Feature s =
          ok(store.create(OVERWORLD, new StationData(v(0, 0), "Central", rail.id()), steve));
      Result r = store.delete(OVERWORLD, rail.id(), 1, steve);
      Result.Rejected rejected = assertInstanceOf(Result.Rejected.class, r);
      assertTrue(rejected.reason().contains("Central"), rejected.reason());
      assertEquals(rail, store.get(OVERWORLD, rail.id()).orElseThrow());
      assertEquals(1, store.history(OVERWORLD, rail.id()).size());

      ok(store.delete(OVERWORLD, s.id(), 1, steve));
      ok(store.delete(OVERWORLD, rail.id(), 1, steve));
    }

    @Test
    void railwayEditDroppingStationVertexRejected() {
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      ok(store.create(OVERWORLD, new StationData(v(50, 0), "Central", rail.id()), steve));
      RailwayData moved = new RailwayData(vs(0, 0, 49, 0, 50, 50), "Red Line", "#ff0000");
      List<ValidationError> errors = invalid(store.update(OVERWORLD, rail.id(), 1, moved, alex));
      assertEquals(1, errors.size());
      assertEquals("geometry", errors.get(0).field());
      assertTrue(errors.get(0).message().contains("Central"), errors.toString());
      assertEquals(rail, store.get(OVERWORLD, rail.id()).orElseThrow());
    }

    @Test
    void railwayEditKeepingStationVertexAllowed() {
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      ok(store.create(OVERWORLD, new StationData(v(50, 0), "Central", rail.id()), steve));
      RailwayData extended =
          new RailwayData(vs(-20, 0, 0, 0, 50, 0, 50, 50, 80, 80), "Red Line Ext", "#00ff00");
      assertEquals(extended, ok(store.update(OVERWORLD, rail.id(), 1, extended, alex)).data());
    }

    @Test
    void stationMovedToVertexOfAnotherRailway() {
      Feature red = ok(store.create(OVERWORLD, redLine(), steve));
      Feature blue =
          ok(store.create(
              OVERWORLD, new RailwayData(vs(100, 100, 200, 100), "Blue Line", "#0000ff"), steve));
      Feature s = ok(store.create(OVERWORLD, new StationData(v(0, 0), "Hub", red.id()), steve));

      // Pointing at blue but keeping a red-only point is invalid.
      assertField(
          invalid(store.update(OVERWORLD, s.id(), 1, new StationData(v(0, 0), "Hub", blue.id()), steve)),
          "geometry");

      StationData onBlue = new StationData(v(200, 100), "Hub", blue.id());
      assertEquals(onBlue, ok(store.update(OVERWORLD, s.id(), 1, onBlue, steve)).data());

      // Red no longer has stations: it can be deleted; blue cannot.
      ok(store.delete(OVERWORLD, red.id(), 1, steve));
      assertInstanceOf(Result.Rejected.class, store.delete(OVERWORLD, blue.id(), 1, steve));
    }

    @Test
    void railwayLookupIsWorldScoped() {
      Feature rail = ok(store.create(OVERWORLD, redLine(), steve));
      assertField(
          invalid(store.create(NETHER, new StationData(v(0, 0), "S", rail.id()), steve)),
          "railwayId");
    }
  }

  @Nested
  class History {
    @Test
    void recordsCreateUpdateDeleteInOrder() {
      Feature f = ok(store.create(OVERWORLD, building(), steve));
      clock.advance(Duration.ofSeconds(1));
      BuildingData changed =
          new BuildingData(vs(0, 0, 8, 0, 8, 8, 0, 8), "Shop", BuildingCategory.COMMERCIAL, "New");
      ok(store.update(OVERWORLD, f.id(), 1, changed, alex));
      clock.advance(Duration.ofSeconds(1));
      ok(store.delete(OVERWORLD, f.id(), 2, steve));

      assertEquals(
          List.of(
              new HistoryEntry(OVERWORLD, f.id(), 1, Action.CREATE, steve, T0, building()),
              new HistoryEntry(
                  OVERWORLD, f.id(), 2, Action.UPDATE, alex, T0.plusSeconds(1), changed),
              new HistoryEntry(
                  OVERWORLD, f.id(), 3, Action.DELETE, steve, T0.plusSeconds(2), changed)),
          store.history(OVERWORLD, f.id()));
    }

    @Test
    void failedWritesAddNoHistory() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      store.update(OVERWORLD, f.id(), 9, road(), steve); // conflict
      store.update(OVERWORLD, f.id(), 1, redLine(), steve); // type change
      store.delete(OVERWORLD, f.id(), 9, steve); // conflict
      assertEquals(1, store.history(OVERWORLD, f.id()).size());
    }

    @Test
    void historyIsWorldScoped() {
      Feature f = ok(store.create(OVERWORLD, road(), steve));
      assertEquals(List.of(), store.history(NETHER, f.id()));
    }

    @Test
    void historyTableIsAppendOnly(@TempDir Path dir) throws SQLException {
      Path file = dir.resolve("map.sqlite");
      try (FeatureStore s = FeatureStore.open(file, clock)) {
        ok(s.create(OVERWORLD, road(), steve));
      }
      try (Connection c = raw(file);
          Statement st = c.createStatement()) {
        assertThrows(SQLException.class, () -> st.executeUpdate("DELETE FROM feature_history"));
        assertThrows(
            SQLException.class, () -> st.executeUpdate("UPDATE feature_history SET actor_name = 'x'"));
      }
    }
  }

  @Nested
  class WorldIsolation {
    @Test
    void featuresAreScopedByWorld() {
      Feature o = ok(store.create(OVERWORLD, road(), steve));
      Feature n = ok(store.create(NETHER, building(), alex));
      assertEquals(List.of(o), store.list(OVERWORLD));
      assertEquals(List.of(n), store.list(NETHER));
      assertEquals(List.of(), store.list("minecraft:the_end"));
      assertTrue(store.get(NETHER, o.id()).isEmpty());
      assertInstanceOf(Result.NotFound.class, store.update(NETHER, o.id(), 1, road(), steve));
      assertInstanceOf(Result.NotFound.class, store.delete(NETHER, o.id(), 1, steve));
      assertEquals(o, store.get(OVERWORLD, o.id()).orElseThrow());
    }

    @Test
    void railwayWithStationInOtherWorldIsNotBlocked() {
      // Stations can only reference railways in their own world, so a railway's stations are all
      // in its world; verify delete checks do not leak across worlds with a same-shaped setup.
      Feature railO = ok(store.create(OVERWORLD, redLine(), steve));
      Feature railN = ok(store.create(NETHER, redLine(), steve));
      ok(store.create(NETHER, new StationData(v(0, 0), "S", railN.id()), steve));
      ok(store.delete(OVERWORLD, railO.id(), 1, steve));
      assertInstanceOf(Result.Rejected.class, store.delete(NETHER, railN.id(), 1, steve));
    }
  }

  @Nested
  class Persistence {
    @Test
    void reopenYieldsIdenticalData(@TempDir Path dir) {
      Path file = dir.resolve("world/data/squaremap-pro/map.sqlite");
      List<Feature> before;
      List<HistoryEntry> historyBefore;
      String railId;
      try (FeatureStore s = FeatureStore.open(file, clock)) {
        ok(s.create(OVERWORLD, building(), steve));
        ok(s.create(OVERWORLD, road(), alex));
        Feature rail = ok(s.create(OVERWORLD, redLine(), steve));
        railId = rail.id();
        ok(s.create(OVERWORLD, new StationData(v(50, 50), "Harbour", rail.id()), steve));
        clock.advance(Duration.ofNanos(1));
        ok(s.update(OVERWORLD, rail.id(), 1, new RailwayData(vs(0, 0, 50, 0, 50, 50, 60, 60), "Red", "#aabbcc"), alex));
        ok(s.create(NETHER, road(), steve));
        before = s.list(OVERWORLD);
        historyBefore = s.history(OVERWORLD, railId);
      }
      assertTrue(Files.isRegularFile(file));
      try (FeatureStore s = FeatureStore.open(file, clock)) {
        assertEquals(before, s.list(OVERWORLD));
        assertEquals(1, s.list(NETHER).size());
        assertEquals(historyBefore, s.history(OVERWORLD, railId));
        assertEquals(2, historyBefore.size());
        // Still writable after reopen, and rules still apply.
        assertInstanceOf(Result.Rejected.class, s.delete(OVERWORLD, railId, 2, steve));
      }
    }

    @Test
    void schemaVersionAndWalMode(@TempDir Path dir) throws SQLException {
      Path file = dir.resolve("map.sqlite");
      FeatureStore.open(file, clock).close();
      FeatureStore.open(file, clock).close(); // reopen does not re-insert
      try (Connection c = raw(file);
          Statement st = c.createStatement()) {
        try (ResultSet rs = st.executeQuery("SELECT version FROM schema_version")) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1));
          assertTrue(!rs.next(), "exactly one schema_version row");
        }
        try (ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
          assertTrue(rs.next());
          assertEquals("wal", rs.getString(1));
        }
      }
    }

    @Test
    void unsupportedSchemaVersionRefused(@TempDir Path dir) throws SQLException {
      Path file = dir.resolve("map.sqlite");
      FeatureStore.open(file, clock).close();
      try (Connection c = raw(file);
          Statement st = c.createStatement()) {
        st.executeUpdate("UPDATE schema_version SET version = 2");
      }
      FeatureStoreException e =
          assertThrows(FeatureStoreException.class, () -> FeatureStore.open(file, clock));
      assertTrue(e.getMessage().contains("schema_version"), e.getMessage());
    }

    @Test
    void closedStoreThrows() {
      FeatureStore s = FeatureStore.openInMemory(clock);
      s.close();
      assertThrows(FeatureStoreException.class, () -> s.list(OVERWORLD));
    }

    @Test
    void separateInMemoryStoresAreIndependent() {
      try (FeatureStore other = FeatureStore.openInMemory(clock)) {
        ok(store.create(OVERWORLD, road(), steve));
        assertEquals(List.of(), other.list(OVERWORLD));
      }
    }
  }

  @Test
  void geometryCodecRoundTrip() {
    List<Vertex> g = vs(Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1, 30_000_000, -30_000_000);
    assertEquals(g, GeometryCodec.decode(GeometryCodec.encode(g)));
    assertThrows(FeatureStoreException.class, () -> GeometryCodec.decode("1,2;x"));
    assertThrows(FeatureStoreException.class, () -> GeometryCodec.decode(""));
    assertNotEquals("", GeometryCodec.encode(List.of(v(0, 0))));
  }
}
