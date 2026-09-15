package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.fabric.StoreHolder;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Auth state of one running Minecraft server: the in-memory {@link TokenStore}, the SQLite
 * {@link SessionStore} (opened asynchronously) and the {@link PermissionChecker}.
 */
// THREADING: constructed on the server thread (SERVER_STARTED). tokens()/permissions()/sessions() —
// any thread, non-blocking. openSessions() and close() do blocking SQLite I/O and run on the
// MapExecutor only (see StoreHolder).
public final class AuthServices {

  /** Session database location relative to the world save root. */
  public static final String SESSIONS_DB = "data/squaremap-pro/sessions.sqlite";

  private final TokenStore tokens;
  private final PermissionChecker permissions;
  private final Clock clock;
  private final StoreHolder<SessionStore> sessions = new StoreHolder<>("session store");

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
    return sessions.get();
  }

  /**
   * Opens the session store. WORKER THREAD ONLY (blocking I/O). Failures are logged.
   *
   * @param file database file
   * @param ttl session lifetime
   */
  public void openSessions(Path file, Duration ttl) {
    sessions.open(() -> SessionStore.open(file, ttl, clock));
  }

  /** Closes the session store; later opens are ignored. WORKER THREAD ONLY (blocking I/O). */
  public void close() {
    sessions.close();
  }
}
