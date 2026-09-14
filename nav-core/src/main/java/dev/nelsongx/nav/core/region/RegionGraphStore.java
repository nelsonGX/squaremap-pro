package dev.nelsongx.nav.core.region;

import dev.nelsongx.nav.core.GridPos;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * SQLite serialization of {@link RegionGraph}. The only class in {@code nav-core} that performs I/O.
 *
 * <h2>Schema (format_version 1)</h2>
 *
 * <pre>
 * meta(key TEXT PRIMARY KEY, value TEXT)          -- format_version=1, sector_size, min_y, max_y
 * sectors(sx, sz, PRIMARY KEY(sx, sz))
 * regions(sx, sz, idx, seed_x, seed_y, seed_z, node_count,
 *         min_x, min_y, min_z, max_x, max_y, max_z, PRIMARY KEY(sx, sz, idx))
 * links(from_sx, from_sz, from_idx, to_sx, to_sz, to_idx,
 *       from_x, from_y, from_z, to_x, to_y, to_z, cost REAL)
 * INDEX links_from ON links(from_sx, from_sz, from_idx)
 * </pre>
 *
 * <h2>Errors</h2>
 *
 * {@link SQLException}s and file-system failures are rethrown as {@link UncheckedIOException} (the
 * {@code SQLException} as the cause of the wrapped {@link IOException}). A file that is not a region
 * graph database, has an unknown {@code format_version}, or holds inconsistent data yields
 * {@link IllegalStateException}.
 */
// THREADING: blocking I/O — call from a worker thread, never the server thread.
public final class RegionGraphStore {

  /** The format version written by {@link #save} and the only one {@link #load} accepts. */
  public static final int FORMAT_VERSION = 1;

  private static final int BATCH = 5_000;

  private RegionGraphStore() {
  }

  /**
   * Writes {@code graph} to {@code file}, creating or replacing it. Data is written in a single
   * transaction to a sibling temporary file, which then replaces {@code file}, so an existing file is
   * never left half-written.
   *
   * <p>THREADING: blocking I/O — call from a worker thread, never the server thread.
   *
   * @param graph graph to write
   * @param file destination database file; its parent directory must exist
   * @throws UncheckedIOException on SQLite or file-system failure
   */
  public static void save(RegionGraph graph, Path file) {
    Objects.requireNonNull(graph, "graph");
    Objects.requireNonNull(file, "file");
    Path abs = file.toAbsolutePath();
    Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
    try {
      Files.deleteIfExists(tmp);
      writeDatabase(graph, tmp);
      try {
        Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      deleteQuietly(tmp);
      throw new UncheckedIOException("failed to save region graph to " + abs, e);
    } catch (RuntimeException e) {
      deleteQuietly(tmp);
      throw e;
    }
  }

  private static void writeDatabase(RegionGraph graph, Path path) throws IOException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
      c.setAutoCommit(false);
      try {
        try (Statement st = c.createStatement()) {
          st.executeUpdate("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)");
          st.executeUpdate("CREATE TABLE sectors(sx INTEGER NOT NULL, sz INTEGER NOT NULL,"
              + " PRIMARY KEY(sx, sz))");
          st.executeUpdate("CREATE TABLE regions(sx INTEGER NOT NULL, sz INTEGER NOT NULL,"
              + " idx INTEGER NOT NULL, seed_x INTEGER NOT NULL, seed_y INTEGER NOT NULL,"
              + " seed_z INTEGER NOT NULL, node_count INTEGER NOT NULL,"
              + " min_x INTEGER NOT NULL, min_y INTEGER NOT NULL, min_z INTEGER NOT NULL,"
              + " max_x INTEGER NOT NULL, max_y INTEGER NOT NULL, max_z INTEGER NOT NULL,"
              + " PRIMARY KEY(sx, sz, idx))");
          st.executeUpdate("CREATE TABLE links(from_sx INTEGER NOT NULL, from_sz INTEGER NOT NULL,"
              + " from_idx INTEGER NOT NULL, to_sx INTEGER NOT NULL, to_sz INTEGER NOT NULL,"
              + " to_idx INTEGER NOT NULL, from_x INTEGER NOT NULL, from_y INTEGER NOT NULL,"
              + " from_z INTEGER NOT NULL, to_x INTEGER NOT NULL, to_y INTEGER NOT NULL,"
              + " to_z INTEGER NOT NULL, cost REAL NOT NULL)");
          st.executeUpdate("CREATE INDEX links_from ON links(from_sx, from_sz, from_idx)");
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO meta(key, value) VALUES (?, ?)")) {
          String[][] meta = {
              {"format_version", Integer.toString(FORMAT_VERSION)},
              {"sector_size", Integer.toString(graph.sectorSize())},
              {"min_y", Integer.toString(graph.minY())},
              {"max_y", Integer.toString(graph.maxY())}};
          for (String[] kv : meta) {
            ps.setString(1, kv[0]);
            ps.setString(2, kv[1]);
            ps.addBatch();
          }
          ps.executeBatch();
        }
        try (PreparedStatement sectors = c.prepareStatement(
                "INSERT INTO sectors(sx, sz) VALUES (?, ?)");
            PreparedStatement regions = c.prepareStatement(
                "INSERT INTO regions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            PreparedStatement links = c.prepareStatement(
                "INSERT INTO links VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
          int pendingSectors = 0;
          int pendingRegions = 0;
          int pendingLinks = 0;
          for (SectorPos s : graph.sectors()) {
            sectors.setInt(1, s.sx());
            sectors.setInt(2, s.sz());
            sectors.addBatch();
            if (++pendingSectors == BATCH) {
              sectors.executeBatch();
              pendingSectors = 0;
            }
            for (Region r : graph.regionsIn(s)) {
              int i = 1;
              regions.setInt(i++, r.id().sx());
              regions.setInt(i++, r.id().sz());
              regions.setInt(i++, r.id().index());
              regions.setInt(i++, r.seed().x());
              regions.setInt(i++, r.seed().y());
              regions.setInt(i++, r.seed().z());
              regions.setInt(i++, r.nodeCount());
              regions.setInt(i++, r.minX());
              regions.setInt(i++, r.minY());
              regions.setInt(i++, r.minZ());
              regions.setInt(i++, r.maxX());
              regions.setInt(i++, r.maxY());
              regions.setInt(i, r.maxZ());
              regions.addBatch();
              if (++pendingRegions == BATCH) {
                regions.executeBatch();
                pendingRegions = 0;
              }
              for (RegionLink l : graph.linksFrom(r.id())) {
                int j = 1;
                links.setInt(j++, l.from().sx());
                links.setInt(j++, l.from().sz());
                links.setInt(j++, l.from().index());
                links.setInt(j++, l.to().sx());
                links.setInt(j++, l.to().sz());
                links.setInt(j++, l.to().index());
                links.setInt(j++, l.fromPos().x());
                links.setInt(j++, l.fromPos().y());
                links.setInt(j++, l.fromPos().z());
                links.setInt(j++, l.toPos().x());
                links.setInt(j++, l.toPos().y());
                links.setInt(j++, l.toPos().z());
                links.setDouble(j, l.cost());
                links.addBatch();
                if (++pendingLinks == BATCH) {
                  links.executeBatch();
                  pendingLinks = 0;
                }
              }
            }
          }
          sectors.executeBatch();
          regions.executeBatch();
          links.executeBatch();
        }
        c.commit();
      } catch (SQLException | RuntimeException e) {
        try {
          c.rollback();
        } catch (SQLException suppressed) {
          e.addSuppressed(suppressed);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw new IOException("SQLite error writing " + path + ": " + e.getMessage(), e);
    }
  }

  /**
   * Reads a graph previously written by {@link #save}. The database is opened read-only.
   *
   * <p>THREADING: blocking I/O — call from a worker thread, never the server thread.
   *
   * @param file database file
   * @return the loaded graph, equal to the one saved
   * @throws UncheckedIOException if the file does not exist or on SQLite failure
   * @throws IllegalStateException if the file is not a region graph database, its
   *     {@code format_version} is not {@value #FORMAT_VERSION}, or its content is inconsistent
   */
  public static RegionGraph load(Path file) {
    Objects.requireNonNull(file, "file");
    Path abs = file.toAbsolutePath();
    if (!Files.isRegularFile(abs)) {
      throw new UncheckedIOException(new NoSuchFileException(abs.toString()));
    }
    Properties props = new Properties();
    props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + abs, props)) {
      Map<String, String> meta = readMeta(c, abs);
      String version = meta.get("format_version");
      if (!Integer.toString(FORMAT_VERSION).equals(version)) {
        throw new IllegalStateException("unsupported region graph format_version " + version
            + " in " + abs + " (expected " + FORMAT_VERSION + ")");
      }
      int sectorSize = metaInt(meta, "sector_size", abs);
      int minY = metaInt(meta, "min_y", abs);
      int maxY = metaInt(meta, "max_y", abs);

      List<SectorPos> sectors = new ArrayList<>();
      List<Region> regions = new ArrayList<>();
      List<RegionLink> links = new ArrayList<>();
      try (Statement st = c.createStatement()) {
        try (ResultSet rs = st.executeQuery("SELECT sx, sz FROM sectors")) {
          while (rs.next()) {
            sectors.add(new SectorPos(rs.getInt(1), rs.getInt(2)));
          }
        }
        try (ResultSet rs = st.executeQuery("SELECT sx, sz, idx, seed_x, seed_y, seed_z, node_count,"
            + " min_x, min_y, min_z, max_x, max_y, max_z FROM regions")) {
          while (rs.next()) {
            regions.add(new Region(new RegionId(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                new GridPos(rs.getInt(4), rs.getInt(5), rs.getInt(6)), rs.getInt(7),
                rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11), rs.getInt(12),
                rs.getInt(13)));
          }
        }
        try (ResultSet rs = st.executeQuery("SELECT from_sx, from_sz, from_idx, to_sx, to_sz, to_idx,"
            + " from_x, from_y, from_z, to_x, to_y, to_z, cost FROM links")) {
          while (rs.next()) {
            links.add(new RegionLink(new RegionId(rs.getInt(1), rs.getInt(2), rs.getInt(3)),
                new RegionId(rs.getInt(4), rs.getInt(5), rs.getInt(6)),
                new GridPos(rs.getInt(7), rs.getInt(8), rs.getInt(9)),
                new GridPos(rs.getInt(10), rs.getInt(11), rs.getInt(12)), rs.getDouble(13)));
          }
        }
      }
      return new RegionGraph(sectorSize, minY, maxY, sectors, regions, links);
    } catch (SQLException e) {
      throw new UncheckedIOException(
          new IOException("SQLite error reading " + abs + ": " + e.getMessage(), e));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("inconsistent region graph data in " + abs + ": "
          + e.getMessage(), e);
    }
  }

  private static Map<String, String> readMeta(Connection c, Path file) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'");
        ResultSet rs = ps.executeQuery()) {
      if (!rs.next()) {
        throw new IllegalStateException(file + " is not a region graph database (no meta table)");
      }
    }
    Map<String, String> meta = new HashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT key, value FROM meta")) {
      while (rs.next()) {
        meta.put(rs.getString(1), rs.getString(2));
      }
    }
    return meta;
  }

  private static int metaInt(Map<String, String> meta, String key, Path file) {
    String v = meta.get(key);
    if (v == null) {
      throw new IllegalStateException("missing meta '" + key + "' in " + file);
    }
    try {
      return Integer.parseInt(v);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("invalid meta '" + key + "'=" + v + " in " + file, e);
    }
  }

  private static void deleteQuietly(Path p) {
    try {
      Files.deleteIfExists(p);
    } catch (IOException ignored) {
      // best effort cleanup of the temporary file
    }
  }
}
