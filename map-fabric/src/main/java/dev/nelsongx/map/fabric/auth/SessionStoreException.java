package dev.nelsongx.map.fabric.auth;

/** A {@link SessionStore} database failure. */
// THREADING: exception value; any thread.
public final class SessionStoreException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  SessionStoreException(String message) {
    super(message);
  }

  SessionStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
