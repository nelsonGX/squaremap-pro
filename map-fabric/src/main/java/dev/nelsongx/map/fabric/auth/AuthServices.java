package dev.nelsongx.map.fabric.auth;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Auth state of one running Minecraft server: the in-memory {@link TokenStore}, the SQLite
 * {@link SessionStore} (opened asynchronously) and the {@link PermissionChecker}.
 */
// THREADING: constructed on the server thread (SERVER_STARTED). tokens()/permissions()/sessions() —
// any thread, non-blocking (sessions is volatile). openSessions() and close() do blocking SQLite I/O
// and run on the MapExecutor only; the swap of `sessions` and the `closed` flag are guarded by `this`
// (I/O happens outside the lock), so a close that runs before a late open wins and the late store is
// closed immediately.
public final class AuthServices {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  /** Session database location relative to the world save root. */
  public static final String SESSIONS_DB = "data/squaremap-pro/sessions.sqlite";

  private final TokenStore tokens;
  private final PermissionChecker permissions;
  private final Clock clock;
  private volatile SessionStore sessions;
  private boolean closed;

  /**
   * @param tokens login token store
   * @param permissions permission checker
   * @param clock clock for the session store
   */
  public AuthServices(TokenStore tokens, PermissionChecker permissions, Clock clock) {
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.permissions = Objects.requireNonNull(permissions, "permissions");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** @return the token store. ANY THREAD. */
  public TokenStore tokens() {
    return tokens;
  }

  /** @return the permission checker. ANY THREAD. */
  public PermissionChecker permissions() {
    return permissions;
  }

  /** @return the session store, or null while not (yet) open or after close. ANY THREAD. */
  public SessionStore sessions() {
    return sessions;
  }

  /**
   * Opens the session store. WORKER THREAD ONLY (blocking I/O). Failures are logged.
   *
   * @param file database file
   * @param ttl session lifetime
   */
  public void openSessions(Path file, Duration ttl) {
    synchronized (this) {
      if (closed) {
        return;
      }
    }
    SessionStore opened;
    try {
      opened = SessionStore.open(file, ttl, clock);
    } catch (RuntimeException e) {
      LOGGER.error("squaremap-pro could not open session store {}", file, e);
      return;
    }
    SessionStore previous;
    synchronized (this) {
      if (closed) {
        previous = opened;
      } else {
        previous = sessions;
        sessions = opened;
      }
    }
    closeQuietly(previous);
  }

  /** Closes the session store; later opens are ignored. WORKER THREAD ONLY (blocking I/O). */
  public void close() {
    SessionStore s;
    synchronized (this) {
      closed = true;
      s = sessions;
      sessions = null;
    }
    closeQuietly(s);
  }

  private static void closeQuietly(SessionStore s) {
    if (s == null) {
      return;
    }
    try {
      s.close();
    } catch (RuntimeException e) {
      LOGGER.warn("squaremap-pro session store did not close cleanly", e);
    }
  }
}
