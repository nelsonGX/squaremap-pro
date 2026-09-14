package dev.nelsongx.nav.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.fixture.FixtureWorld;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionGraphStoreTest {

  @TempDir
  Path dir;

  private static RegionGraph acceptanceGraph() {
    FixtureWorld w = FixtureWorld.parse(RegionTestSupport.ACCEPTANCE);
    return RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(2, 2), 4);
  }

  @Test
  void acceptanceSaveLoadRoundTrip() throws Exception {
    RegionGraph original = acceptanceGraph();
    Path file = dir.resolve("regions.db");
    RegionGraphStore.save(original, file);
    RegionGraph loaded = RegionGraphStore.load(file);

    assertEquals(original, loaded);
    assertEquals(original.hashCode(), loaded.hashCode());
    assertEquals(original.regionCount(), loaded.regionCount());
    assertEquals(original.linkCount(), loaded.linkCount());
    assertEquals(original.sectors(), loaded.sectors());
    assertEquals(original.sectorSize(), loaded.sectorSize());
    assertEquals(original.minY(), loaded.minY());
    assertEquals(original.maxY(), loaded.maxY());
    for (SectorPos s : original.sectors()) {
      assertEquals(original.regionsIn(s), loaded.regionsIn(s));
      for (Region r : original.regionsIn(s)) {
        assertEquals(original.linksFrom(r.id()), loaded.linksFrom(r.id()));
        assertEquals(original.linksTo(r.id()), loaded.linksTo(r.id()));
      }
    }
    assertTrue(original.regionCount() > 0 && original.linkCount() > 0);
    assertFalse(Files.exists(dir.resolve("regions.db.tmp")));

    // Sanity on the stored tables.
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Statement st = c.createStatement()) {
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM links")) {
        rs.next();
        assertEquals(original.linkCount(), rs.getInt(1));
      }
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sectors")) {
        rs.next();
        assertEquals(9, rs.getInt(1));
      }
    }
    System.out.println("acceptance fixture: " + original);
  }

  @Test
  void saveOverwritesExistingFile() {
    Path file = dir.resolve("g.db");
    RegionGraph first = acceptanceGraph();
    RegionGraphStore.save(first, file);
    FixtureWorld w = FixtureWorld.parse(
        RegionTestSupport.toFixture(RegionTestSupport.randomCells(new Random(7), 8, 8, 4)));
    RegionGraph second = RegionGraphBuilder.build(w, new SectorPos(0, 0), new SectorPos(3, 3), 2);
    RegionGraphStore.save(second, file);
    assertEquals(second, RegionGraphStore.load(file));
  }

  @Test
  void emptyGraphRoundTrips() {
    Path file = dir.resolve("empty.db");
    RegionGraph e = RegionGraph.empty(16, -64, 320);
    RegionGraphStore.save(e, file);
    assertEquals(e, RegionGraphStore.load(file));
  }

  @Test
  void unknownFormatVersionIsRejected() throws Exception {
    Path file = dir.resolve("v2.db");
    RegionGraphStore.save(acceptanceGraph(), file);
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        Statement st = c.createStatement()) {
      st.executeUpdate("UPDATE meta SET value = '2' WHERE key = 'format_version'");
    }
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> RegionGraphStore.load(file));
    assertTrue(ex.getMessage().contains("format_version"), ex.getMessage());
  }

  @Test
  void missingOrForeignFilesFail() throws Exception {
    assertThrows(UncheckedIOException.class, () -> RegionGraphStore.load(dir.resolve("nope.db")));
    Path foreign = dir.resolve("foreign.db");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + foreign.toAbsolutePath());
        Statement st = c.createStatement()) {
      st.executeUpdate("CREATE TABLE other(x)");
    }
    assertThrows(IllegalStateException.class, () -> RegionGraphStore.load(foreign));
  }
}
