package dev.nelsongx.map.core.feature;

import java.util.Objects;
import java.util.UUID;

/** The player who created or last updated a feature. THREADING: immutable value. */
public record Actor(UUID uuid, String name) {
  public Actor {
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(name, "name");
  }
}
