package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Web editor sessions persisted in SQLite ({@code <world save>/data/squaremap-pro/sessions.sqlite}).
 *
 * <p>Session ids are 256 random bits (base64url, no padding). Only the SHA-256 of an id is stored, so
 * a leaked database file does not leak usable cookies. Sessions have a fixed lifetime from creation;
 * an expired session is deleted when looked up and by {@link #purgeExpired()}. Ids are never logged.
 */
// THREADING: every public method is synchronized over one JDBC connection and does blocking I/O —
// call only from the mod's worker executor (MapExecutor), never from the server thread or a Javalin
// request thread.
public final class SessionStore implements AutoCloseable {

  /** Database schema version. */
  public static final int SCHEMA_VERSION = 1;

  private final Connection conn;
  private final Clock clock;
  private final Duration ttl;
  private boolean closed;

  private SessionStore(Connection conn, Clock clock, Duration ttl) {
    this.conn = conn;
    this.clock = clock;
    this.ttl = ttl;
  }

  /**
   * Opens (creating the file and parent directories if needed) a store in WAL mode and purges expired
   * sessions.
   *
   * @param file database file
   * @param ttl session lifetime, &gt; 0
   * @param clock time source
   * @return the store
   * @throws SessionStoreException if the database cannot be opened
   */
  public static SessionStore open(Path file, Duration ttl, Clock clock) {
    Objects.requireNonNull(file, "file");
    Path abs = file.toAbsolutePath();
    try {
      if (abs.getParent() != null) {
        Files.createDirectories(abs.getParent());
      }
    } catch (IOException e) {
      throw new SessionStoreException("cannot create directory for " + abs, e);
    }
    return connect("jdbc:sqlite:" + abs, ttl, clock, true);
  }

  /** Opens a private in-memory store (tests); data is lost on close. */
  public static SessionStore openInMemory(Duration ttl, Clock clock) {
    return connect("jdbc:sqlite::memory:", ttl, clock, false);
  }

  private static SessionStore connect(String url, Duration ttl, Clock clock, boolean wal) {
    Objects.requireNonNull(ttl, "ttl");
    Objects.requireNonNull(clock, "clock");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be > 0: " + ttl);
    }
    Connection conn;
    try {
      // Driver used directly, as FeatureStore does: DriverManager is unreliable under mod class loaders.
      conn = new org.sqlite.JDBC().connect(url, new Properties());
    } catch (SQLException e) {
      throw new SessionStoreException("cannot open " + url, e);
    }
    try {
      try (Statement st = conn.createStatement()) {
        if (wal) {
          st.execute("PRAGMA journal_mode=WAL");
        }
        st.execute("PRAGMA busy_timeout=5000");
        st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        Integer version = null;
        try (ResultSet rs = st.executeQuery("SELECT MAX(version) FROM schema_version")) {
          if (rs.next()) {
            int v = rs.getInt(1);
            version = rs.wasNull() ? null : v;
          }
        }
        if (version == null) {
          st.execute("CREATE TABLE IF NOT EXISTS sessions ("
              + "id_hash TEXT PRIMARY KEY NOT NULL, "
              + "uuid TEXT NOT NULL, "
              + "name TEXT NOT NULL, "
              + "created_at INTEGER NOT NULL, "
              + "expires_at INTEGER NOT NULL)");
          st.execute("CREATE INDEX IF NOT EXISTS sessions_expires_at ON sessions (expires_at)");
          st.execute("INSERT INTO schema_version (version) VALUES (" + SCHEMA_VERSION + ")");
        } else if (version != SCHEMA_VERSION) {
          throw new SessionStoreException(
              "unsupported schema_version " + version + " (expected " + SCHEMA_VERSION + ")");
        }
      }
      SessionStore store = new SessionStore(conn, clock, ttl);
      store.purgeExpired();
      return store;
    } catch (SQLException | RuntimeException e) {
      try {
        conn.close();
      } catch (SQLException suppressed) {
        e.addSuppressed(suppressed);
      }
      if (e instanceof SessionStoreException sse) {
        throw sse;
      }
      throw new SessionStoreException("cannot initialise " + url, e);
    }
  }

  /** @return the session lifetime */
  public Duration ttl() {
    return ttl;
  }

  /**
   * Creates a session for {@code actor}.
   *
   * @param actor the player
   * @return the new session (its id is the cookie value)
   */
  public synchronized Session create(Actor actor) {
    Objects.requireNonNull(actor, "actor");
    ensureOpen();
    Instant now = clock.instant();
    Instant expiresAt = now.plus(ttl);
    String id = SecureIds.random256();
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO sessions (id_hash, uuid, name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")) {
      ps.setString(1, SecureIds.sha256Hex(id));
      ps.setString(2, actor.uuid().toString());
      ps.setString(3, actor.name());
      ps.setLong(4, now.toEpochMilli());
      ps.setLong(5, expiresAt.toEpochMilli());
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new SessionStoreException("cannot create session", e);
    }
    return new Session(id, actor, Instant.ofEpochMilli(expiresAt.toEpochMilli()));
  }

  /**
   * Looks up a live session. An expired session is deleted and reported as absent.
   *
   * @param id session id (may be null or malformed)
   * @return the session, or empty if unknown or expired
   */
  public synchronized Optional<Session> find(String id) {
    ensureOpen();
    if (!SecureIds.wellFormed(id)) {
      return Optional.empty();
    }
    String hash = SecureIds.sha256Hex(id);
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT uuid, name, expires_at FROM sessions WHERE id_hash = ?")) {
      ps.setString(1, hash);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochMilli(rs.getLong(3));
        if (!clock.instant().isBefore(expiresAt)) {
          deleteHash(hash);
          return Optional.empty();
        }
        UUID uuid;
        try {
          uuid = UUID.fromString(rs.getString(1));
        } catch (IllegalArgumentException e) {
          deleteHash(hash);
          return Optional.empty();
        }
        return Optional.of(new Session(id, new Actor(uuid, rs.getString(2)), expiresAt));
      }
    } catch (SQLException e) {
      throw new SessionStoreException("cannot read session", e);
    }
  }

  /**
   * Deletes a session (logout). Unknown or malformed ids are ignored.
   *
   * @param id session id
   */
  public synchronized void delete(String id) {
    ensureOpen();
    if (!SecureIds.wellFormed(id)) {
      return;
    }
    deleteHash(SecureIds.sha256Hex(id));
  }

  /** @return number of expired sessions deleted */
  public synchronized int purgeExpired() {
    ensureOpen();
    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE expires_at <= ?")) {
      ps.setLong(1, clock.instant().toEpochMilli());
      return ps.executeUpdate();
    } catch (SQLException e) {
      throw new SessionStoreException("cannot purge sessions", e);
    }
  }

  /** Closes the connection; idempotent. */
  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      conn.close();
    } catch (SQLException e) {
      throw new SessionStoreException("cannot close session store", e);
    }
  }

  private void deleteHash(String hash) {
    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE id_hash = ?")) {
      ps.setString(1, hash);
      ps.executeUpdate();
    } catch (SQLException e) {
      throw new SessionStoreException("cannot delete session", e);
    }
  }

  private void ensureOpen() {
    if (closed) {
      throw new SessionStoreException("session store is closed");
    }
  }
}
