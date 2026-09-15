package dev.nelsongx.map.fabric.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.Actor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {

  private static final Duration TTL = Duration.ofHours(168);
  private static final Actor STEVE = new Actor(UUID.fromString("00000000-0000-0000-0000-000000000001"),
      "Steve");
  private static final Actor ALEX = new Actor(UUID.fromString("00000000-0000-0000-0000-000000000002"),
      "Alex");

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T10:00:00Z"));

  @Test
  void createAndFind() {
    try (SessionStore store = SessionStore.openInMemory(TTL, clock)) {
      Session s = store.create(STEVE);
      assertEquals(43, s.id().length());
      assertTrue(s.id().matches("[A-Za-z0-9_-]{43}"));
      assertEquals(STEVE, s.actor());
      assertEquals(clock.instant().plus(TTL), s.expiresAt());
      assertEquals(Optional.of(s), store.find(s.id()));
      assertFalse(s.toString().contains(s.id()), "toString must not leak the id");

      Session other = store.create(STEVE);
      assertNotEquals(s.id(), other.id(), "several sessions per player allowed");
      assertEquals(Optional.of(other), store.find(other.id()));
      assertEquals(Optional.of(s), store.find(s.id()));
    }
  }

  @Test
  void unknownAndMalformedIdsAreAbsent() {
    try (SessionStore store = SessionStore.openInMemory(TTL, clock)) {
      store.create(STEVE);
      assertEquals(Optional.empty(), store.find(null));
      assertEquals(Optional.empty(), store.find(""));
      assertEquals(Optional.empty(), store.find("x' OR '1'='1"));
      assertEquals(Optional.empty(), store.find(SecureIds.random256()));
    }
  }

  @Test
  void expiredSessionIsAbsentAndDeleted() {
    try (SessionStore store = SessionStore.openInMemory(Duration.ofHours(1), clock)) {
      Session s = store.create(STEVE);
      clock.advance(Duration.ofHours(1).minusMillis(1));
      assertTrue(store.find(s.id()).isPresent());
      clock.advance(Duration.ofMillis(1));
      assertEquals(Optional.empty(), store.find(s.id()));
      assertEquals(0, store.purgeExpired(), "already deleted by find");
    }
  }

  @Test
  void purgeExpiredDeletesOnlyExpired() {
    try (SessionStore store = SessionStore.openInMemory(Duration.ofHours(2), clock)) {
      Session old1 = store.create(STEVE);
      Session old2 = store.create(ALEX);
      clock.advance(Duration.ofHours(1));
      Session young = store.create(ALEX);
      clock.advance(Duration.ofHours(1));
      assertEquals(2, store.purgeExpired());
      assertEquals(Optional.empty(), store.find(old1.id()));
      assertEquals(Optional.empty(), store.find(old2.id()));
      assertEquals(Optional.of(young), store.find(young.id()));
    }
  }

  @Test
  void deleteRemovesOnlyThatSession() {
    try (SessionStore store = SessionStore.openInMemory(TTL, clock)) {
      Session a = store.create(STEVE);
      Session b = store.create(STEVE);
      store.delete(a.id());
      store.delete(a.id()); // idempotent
      store.delete("garbage");
      store.delete(null);
      assertEquals(Optional.empty(), store.find(a.id()));
      assertEquals(Optional.of(b), store.find(b.id()));
    }
  }

  @Test
  void persistsAcrossReopen(@TempDir Path dir) {
    Path file = dir.resolve("data/squaremap-pro/sessions.sqlite");
    Session s;
    Session gone;
    try (SessionStore store = SessionStore.open(file, TTL, clock)) {
      s = store.create(new Actor(STEVE.uuid(), "Stève ✓"));
      gone = store.create(ALEX);
      store.delete(gone.id());
    }
    assertTrue(Files.exists(file), "parent directories created");
    try (SessionStore store = SessionStore.open(file, TTL, clock)) {
      assertEquals(Optional.of(s), store.find(s.id()));
      assertEquals(Optional.empty(), store.find(gone.id()));
    }
    clock.advance(TTL);
    try (SessionStore store = SessionStore.open(file, TTL, clock)) {
      assertEquals(Optional.empty(), store.find(s.id()), "expired while closed");
    }
  }

  @Test
  void openPurgesExpired(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("sessions.sqlite");
    try (SessionStore store = SessionStore.open(file, Duration.ofHours(1), clock)) {
      store.create(STEVE);
    }
    clock.advance(Duration.ofHours(2));
    SessionStore.open(file, Duration.ofHours(1), clock).close();
    assertEquals(List.of("0"), rows(file, "SELECT COUNT(*) FROM sessions"));
  }

  @Test
  void onlyHashOfIdIsStored(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("sessions.sqlite");
    Session s;
    try (SessionStore store = SessionStore.open(file, TTL, clock)) {
      s = store.create(STEVE);
    }
    String expectedHash = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(s.id().getBytes(StandardCharsets.UTF_8)));
    assertEquals(List.of(expectedHash), rows(file, "SELECT id_hash FROM sessions"));
    assertEquals(List.of(STEVE.uuid() + "|Steve"), rows(file, "SELECT uuid || '|' || name FROM sessions"));

    // The raw id appears nowhere in the database files (main file and WAL).
    for (Path p : List.of(file, dir.resolve("sessions.sqlite-wal"))) {
      if (Files.exists(p)) {
        String content = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains(s.id()), "raw session id found in " + p.getFileName());
      }
    }
  }

  @Test
  void closedStoreRejectsCalls() {
    SessionStore store = SessionStore.openInMemory(TTL, clock);
    store.close();
    store.close(); // idempotent
    assertThrows(SessionStoreException.class, () -> store.create(STEVE));
    assertThrows(SessionStoreException.class, () -> store.find(SecureIds.random256()));
  }

  @Test
  void invalidTtlRejected() {
    assertThrows(IllegalArgumentException.class, () -> SessionStore.openInMemory(Duration.ZERO, clock));
  }

  private static List<String> rows(Path file, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = new org.sqlite.JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(),
        new Properties());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }
}
