package dev.nelsongx.map.core.store;

import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.BuildingCategory;
import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData;
import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.FeatureType;
import dev.nelsongx.map.core.feature.FeatureValidator;
import dev.nelsongx.map.core.feature.RailwayLookup;
import dev.nelsongx.map.core.feature.RoadClass;
import dev.nelsongx.map.core.feature.ValidationError;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.core.store.HistoryEntry.Action;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * SQLite-backed feature storage for all worlds of one server.
 *
 * <p>Every write validates (with a world-scoped {@link RailwayLookup}), checks the optimistic
 * revision and referential station rules, writes the feature and appends a {@code feature_history}
 * row in a single transaction. {@code feature_history} is append-only (enforced by triggers).
 *
 * <p>THREADING: all public methods are {@code synchronized} over one JDBC connection, so the store
 * may be called from any thread, but calls serialize. It does blocking I/O: call it from the mod's
 * worker executor, never from the server thread or a Javalin request thread.
 */
public final class FeatureStore implements AutoCloseable {
  public static final int SCHEMA_VERSION = 1;

  private static final String ID_PREFIX = "f_";
  private static final int ID_RANDOM_CHARS = 16;
  private static final char[] BASE62 =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

  private static final String DATA_COLUMNS =
      "type, name, geometry, category, description, road_class, colour, railway_id";

  private final Connection conn;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();
  private final FeatureValidator validator = new FeatureValidator();

  private FeatureStore(Connection conn, Clock clock) {
    this.conn = conn;
    this.clock = clock;
  }

  /** Opens (creating if needed, including parent directories) a store file with the UTC clock. */
  public static FeatureStore open(Path file) {
    return open(file, Clock.systemUTC());
  }

  /** Opens (creating if needed, including parent directories) a store file in WAL mode. */
  public static FeatureStore open(Path file, Clock clock) {
    Objects.requireNonNull(file, "file");
    Path abs = file.toAbsolutePath();
    try {
      if (abs.getParent() != null) {
        Files.createDirectories(abs.getParent());
      }
    } catch (IOException e) {
      throw new FeatureStoreException("cannot create directory for " + abs, e);
    }
    return connect("jdbc:sqlite:" + abs, clock, true);
  }

  /** Opens a private in-memory store (for tests); its data is lost on {@link #close()}. */
  public static FeatureStore openInMemory(Clock clock) {
    return connect("jdbc:sqlite::memory:", clock, false);
  }

  private static FeatureStore connect(String url, Clock clock, boolean wal) {
    Objects.requireNonNull(clock, "clock");
    Connection conn;
    try {
      // Use the driver directly rather than DriverManager, which is unreliable under mod class
      // loaders.
      conn = new org.sqlite.JDBC().connect(url, new Properties());
    } catch (SQLException e) {
      throw new FeatureStoreException("cannot open " + url, e);
    }
    try {
      try (Statement st = conn.createStatement()) {
        if (wal) {
          st.execute("PRAGMA journal_mode=WAL");
        }
        st.execute("PRAGMA busy_timeout=5000");
      }
      conn.setAutoCommit(false);
      initSchema(conn);
      conn.commit();
      return new FeatureStore(conn, clock);
    } catch (SQLException | RuntimeException e) {
      try {
        conn.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof FeatureStoreException fse) {
        throw fse;
      }
      throw new FeatureStoreException("cannot initialise " + url, e);
    }
  }

  private static void initSchema(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
      Integer version = null;
      try (ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
        if (rs.next()) {
          int v = rs.getInt(1);
          version = rs.wasNull() ? null : v;
        }
      }
      if (version != null) {
        if (version != SCHEMA_VERSION) {
          throw new FeatureStoreException(
              "unsupported schema_version " + version + " (expected " + SCHEMA_VERSION + ")");
        }
        return;
      }
      String dataColumnDefs =
          "type TEXT NOT NULL, name TEXT NOT NULL, geometry TEXT NOT NULL, category TEXT,"
              + " description TEXT, road_class TEXT, colour TEXT, railway_id TEXT";
      st.execute(
          "CREATE TABLE features ("
              + "id TEXT PRIMARY KEY, world_id TEXT NOT NULL, revision INTEGER NOT NULL, "
              + dataColumnDefs
              + ", created_by_uuid TEXT NOT NULL, created_by_name TEXT NOT NULL,"
              + " created_at TEXT NOT NULL, updated_by_uuid TEXT NOT NULL,"
              + " updated_by_name TEXT NOT NULL, updated_at TEXT NOT NULL)");
      st.execute("CREATE INDEX features_world ON features (world_id)");
      st.execute(
          "CREATE INDEX features_railway ON features (world_id, railway_id)"
              + " WHERE railway_id IS NOT NULL");
      st.execute(
          "CREATE TABLE feature_history ("
              + "seq INTEGER PRIMARY KEY AUTOINCREMENT, world_id TEXT NOT NULL,"
              + " feature_id TEXT NOT NULL, revision INTEGER NOT NULL, action TEXT NOT NULL,"
              + " actor_uuid TEXT NOT NULL, actor_name TEXT NOT NULL, at TEXT NOT NULL, "
              + dataColumnDefs
              + ", UNIQUE (feature_id, revision))");
      st.execute("CREATE INDEX feature_history_feature ON feature_history (world_id, feature_id)");
      st.execute(
          "CREATE TRIGGER feature_history_no_update BEFORE UPDATE ON feature_history"
              + " BEGIN SELECT RAISE(ABORT, 'feature_history is append-only'); END");
      st.execute(
          "CREATE TRIGGER feature_history_no_delete BEFORE DELETE ON feature_history"
              + " BEGIN SELECT RAISE(ABORT, 'feature_history is append-only'); END");
      st.execute("INSERT INTO schema_version (version) VALUES (" + SCHEMA_VERSION + ")");
    }
  }

  // ---- reads ---------------------------------------------------------------------------------

  /** All features of a world, in creation order. */
  public synchronized List<Feature> list(String worldId) {
    Objects.requireNonNull(worldId, "worldId");
    return tx(
        () -> {
          List<Feature> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement("SELECT * FROM features WHERE world_id = ? ORDER BY rowid")) {
            ps.setString(1, worldId);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(readFeature(rs));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  public synchronized Optional<Feature> get(String worldId, String id) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(id, "id");
    return tx(() -> select(worldId, id));
  }

  /** History of one feature in the order it happened (still available after deletion). */
  public synchronized List<HistoryEntry> history(String worldId, String id) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(id, "id");
    return tx(
        () -> {
          List<HistoryEntry> out = new ArrayList<>();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "SELECT * FROM feature_history WHERE world_id = ? AND feature_id = ?"
                      + " ORDER BY seq")) {
            ps.setString(1, worldId);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) {
              while (rs.next()) {
                String action = rs.getString("action");
                out.add(
                    new HistoryEntry(
                        rs.getString("world_id"),
                        rs.getString("feature_id"),
                        rs.getLong("revision"),
                        Action.fromWire(action)
                            .orElseThrow(() -> corrupt("unknown history action " + action)),
                        new Actor(
                            UUID.fromString(rs.getString("actor_uuid")),
                            rs.getString("actor_name")),
                        Instant.parse(rs.getString("at")),
                        readData(rs)));
              }
            }
          }
          return List.copyOf(out);
        });
  }

  // ---- writes --------------------------------------------------------------------------------

  public synchronized Result create(String worldId, FeatureData data, Actor actor) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(actor, "actor");
    return tx(
        () -> {
          List<ValidationError> errors = validator.validate(data, lookup(worldId));
          if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
          }
          String id = newId();
          Instant now = clock.instant();
          Feature feature = new Feature(id, worldId, 1, data, actor, now, actor, now);
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO features (id, world_id, revision, "
                      + DATA_COLUMNS
                      + ", created_by_uuid, created_by_name, created_at,"
                      + " updated_by_uuid, updated_by_name, updated_at)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, worldId);
            ps.setLong(3, 1);
            bindData(ps, 4, data);
            bindActorAndTime(ps, 12, actor, now);
            bindActorAndTime(ps, 15, actor, now);
            ps.executeUpdate();
          }
          appendHistory(worldId, id, 1, Action.CREATE, actor, now, data);
          return new Result.Ok(feature);
        });
  }

  public synchronized Result update(
      String worldId, String id, long expectedRevision, FeatureData data, Actor actor) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(actor, "actor");
    return tx(
        () -> {
          Optional<Feature> found = select(worldId, id);
          if (found.isEmpty()) {
            return new Result.NotFound();
          }
          Feature current = found.get();
          if (current.revision() != expectedRevision) {
            return new Result.Conflict(current);
          }
          if (data != null && data.type() != current.type()) {
            return new Result.Invalid(
                List.of(
                    new ValidationError(
                        "type",
                        "cannot change type from "
                            + current.type().wireName()
                            + " to "
                            + data.type().wireName())));
          }
          List<ValidationError> errors = validator.validate(data, lookup(worldId));
          if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
          }
          if (data instanceof RailwayData railway) {
            List<ValidationError> stationErrors = stationsOffLine(worldId, id, railway.line());
            if (!stationErrors.isEmpty()) {
              return new Result.Invalid(stationErrors);
            }
          }
          long revision = current.revision() + 1;
          Instant now = clock.instant();
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "UPDATE features SET revision = ?, type = ?, name = ?, geometry = ?,"
                      + " category = ?, description = ?, road_class = ?, colour = ?,"
                      + " railway_id = ?, updated_by_uuid = ?, updated_by_name = ?, updated_at = ?"
                      + " WHERE world_id = ? AND id = ? AND revision = ?")) {
            ps.setLong(1, revision);
            bindData(ps, 2, data);
            bindActorAndTime(ps, 10, actor, now);
            ps.setString(13, worldId);
            ps.setString(14, id);
            ps.setLong(15, current.revision());
            if (ps.executeUpdate() != 1) {
              throw new FeatureStoreException("concurrent modification of " + id);
            }
          }
          appendHistory(worldId, id, revision, Action.UPDATE, actor, now, data);
          return new Result.Ok(
              new Feature(
                  id,
                  worldId,
                  revision,
                  data,
                  current.createdBy(),
                  current.createdAt(),
                  actor,
                  now));
        });
  }

  public synchronized Result delete(
      String worldId, String id, long expectedRevision, Actor actor) {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(actor, "actor");
    return tx(
        () -> {
          Optional<Feature> found = select(worldId, id);
          if (found.isEmpty()) {
            return new Result.NotFound();
          }
          Feature current = found.get();
          if (current.revision() != expectedRevision) {
            return new Result.Conflict(current);
          }
          if (current.type() == FeatureType.RAILWAY) {
            List<Feature> stations = stationsOf(worldId, id);
            if (!stations.isEmpty()) {
              return new Result.Rejected(
                  "railway still has "
                      + stations.size()
                      + " station(s): "
                      + String.join(", ", stations.stream().map(s -> s.data().name()).toList()));
            }
          }
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "DELETE FROM features WHERE world_id = ? AND id = ? AND revision = ?")) {
            ps.setString(1, worldId);
            ps.setString(2, id);
            ps.setLong(3, current.revision());
            if (ps.executeUpdate() != 1) {
              throw new FeatureStoreException("concurrent modification of " + id);
            }
          }
          appendHistory(
              worldId,
              id,
              current.revision() + 1,
              Action.DELETE,
              actor,
              clock.instant(),
              current.data());
          return new Result.Ok(current);
        });
  }

  @Override
  public synchronized void close() {
    try {
      conn.close();
    } catch (SQLException e) {
      throw new FeatureStoreException("cannot close store", e);
    }
  }

  // ---- internals (caller holds the monitor) --------------------------------------------------

  @FunctionalInterface
  private interface SqlWork<T> {
    T run() throws SQLException;
  }

  private <T> T tx(SqlWork<T> work) {
    try {
      T result = work.run();
      conn.commit();
      return result;
    } catch (SQLException | RuntimeException e) {
      try {
        conn.rollback();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new FeatureStoreException("database error", e);
    }
  }

  private RailwayLookup lookup(String worldId) {
    return railwayId -> {
      try {
        return select(worldId, railwayId)
            .map(Feature::data)
            .filter(RailwayData.class::isInstance)
            .map(RailwayData.class::cast);
      } catch (SQLException e) {
        throw new FeatureStoreException("database error", e);
      }
    };
  }

  private Optional<Feature> select(String worldId, String id) throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT * FROM features WHERE world_id = ? AND id = ?")) {
      ps.setString(1, worldId);
      ps.setString(2, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(readFeature(rs)) : Optional.empty();
      }
    }
  }

  private List<Feature> stationsOf(String worldId, String railwayId) throws SQLException {
    List<Feature> out = new ArrayList<>();
    try (PreparedStatement ps =
        conn.prepareStatement(
            "SELECT * FROM features WHERE world_id = ? AND railway_id = ? AND type = ?"
                + " ORDER BY rowid")) {
      ps.setString(1, worldId);
      ps.setString(2, railwayId);
      ps.setString(3, FeatureType.STATION.wireName());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(readFeature(rs));
        }
      }
    }
    return out;
  }

  private List<ValidationError> stationsOffLine(String worldId, String railwayId, List<Vertex> line)
      throws SQLException {
    List<ValidationError> errors = new ArrayList<>();
    for (Feature station : stationsOf(worldId, railwayId)) {
      Vertex p = ((StationData) station.data()).point();
      if (!line.contains(p)) {
        errors.add(
            new ValidationError(
                "geometry",
                "station "
                    + station.data().name()
                    + " ("
                    + station.id()
                    + ") at "
                    + p.x()
                    + ","
                    + p.z()
                    + " must remain a vertex of the railway"));
      }
    }
    return errors;
  }

  private void appendHistory(
      String worldId,
      String id,
      long revision,
      Action action,
      Actor actor,
      Instant at,
      FeatureData data)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO feature_history (world_id, feature_id, revision, action, actor_uuid,"
                + " actor_name, at, "
                + DATA_COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      ps.setString(1, worldId);
      ps.setString(2, id);
      ps.setLong(3, revision);
      ps.setString(4, action.wireName());
      bindActorAndTime(ps, 5, actor, at);
      bindData(ps, 8, data);
      ps.executeUpdate();
    }
  }

  private String newId() throws SQLException {
    while (true) {
      char[] chars = new char[ID_RANDOM_CHARS];
      for (int i = 0; i < chars.length; i++) {
        chars[i] = BASE62[random.nextInt(BASE62.length)];
      }
      String id = ID_PREFIX + new String(chars);
      try (PreparedStatement ps =
          conn.prepareStatement(
              "SELECT 1 FROM features WHERE id = ?"
                  + " UNION ALL SELECT 1 FROM feature_history WHERE feature_id = ? LIMIT 1")) {
        ps.setString(1, id);
        ps.setString(2, id);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return id;
          }
        }
      }
    }
  }

  /** Binds the 8 {@link #DATA_COLUMNS} starting at {@code start}. */
  private static void bindData(PreparedStatement ps, int start, FeatureData data)
      throws SQLException {
    String category = null;
    String description = null;
    String roadClass = null;
    String colour = null;
    String railwayId = null;
    switch (data) {
      case BuildingData b -> {
        category = b.category().wireName();
        description = b.description();
      }
      case RoadData r -> roadClass = r.roadClass().wireName();
      case RailwayData r -> colour = r.colour();
      case StationData s -> railwayId = s.railwayId();
    }
    ps.setString(start, data.type().wireName());
    ps.setString(start + 1, data.name());
    ps.setString(start + 2, GeometryCodec.encode(data.geometry()));
    ps.setString(start + 3, category);
    ps.setString(start + 4, description);
    ps.setString(start + 5, roadClass);
    ps.setString(start + 6, colour);
    ps.setString(start + 7, railwayId);
  }

  /** Binds actor uuid, actor name and timestamp (3 parameters) starting at {@code start}. */
  private static void bindActorAndTime(PreparedStatement ps, int start, Actor actor, Instant at)
      throws SQLException {
    ps.setString(start, actor.uuid().toString());
    ps.setString(start + 1, actor.name());
    ps.setString(start + 2, at.toString());
  }

  private static Feature readFeature(ResultSet rs) throws SQLException {
    return new Feature(
        rs.getString("id"),
        rs.getString("world_id"),
        rs.getLong("revision"),
        readData(rs),
        new Actor(UUID.fromString(rs.getString("created_by_uuid")), rs.getString("created_by_name")),
        Instant.parse(rs.getString("created_at")),
        new Actor(UUID.fromString(rs.getString("updated_by_uuid")), rs.getString("updated_by_name")),
        Instant.parse(rs.getString("updated_at")));
  }

  private static FeatureData readData(ResultSet rs) throws SQLException {
    String typeName = rs.getString("type");
    FeatureType type =
        FeatureType.fromWire(typeName).orElseThrow(() -> corrupt("unknown feature type " + typeName));
    String name = rs.getString("name");
    List<Vertex> geometry = GeometryCodec.decode(rs.getString("geometry"));
    return switch (type) {
      case BUILDING -> {
        String category = rs.getString("category");
        yield new BuildingData(
            geometry,
            name,
            BuildingCategory.fromWire(category).orElseThrow(() -> corrupt("unknown category " + category)),
            rs.getString("description"));
      }
      case ROAD -> {
        String roadClass = rs.getString("road_class");
        yield new RoadData(
            geometry,
            name,
            RoadClass.fromWire(roadClass).orElseThrow(() -> corrupt("unknown road class " + roadClass)));
      }
      case RAILWAY -> new RailwayData(geometry, name, rs.getString("colour"));
      case STATION -> {
        if (geometry.size() != 1) {
          throw corrupt("station geometry with " + geometry.size() + " vertices");
        }
        yield new StationData(geometry.get(0), name, rs.getString("railway_id"));
      }
    };
  }

  private static FeatureStoreException corrupt(String what) {
    return new FeatureStoreException("corrupt database: " + what);
  }
}
