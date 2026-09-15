package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.time.Instant;
import java.util.Objects;

/**
 * A web editor session.
 *
 * @param id secret session id (cookie value); never logged, only its SHA-256 is persisted
 * @param actor the player the session belongs to
 * @param expiresAt absolute expiry
 */
// THREADING: immutable value; any thread.
public record Session(String id, Actor actor, Instant expiresAt) {

  /** Validates. */
  public Session {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  @Override
  public String toString() {
    return "Session[actor=" + actor + ", expiresAt=" + expiresAt + "]"; // id deliberately omitted
  }
}
