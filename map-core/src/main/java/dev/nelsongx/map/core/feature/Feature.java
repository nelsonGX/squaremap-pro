package dev.nelsongx.map.core.feature;

import java.time.Instant;
import java.util.Objects;

/**
 * A stored feature: store-assigned identity and metadata plus the user-editable {@link FeatureData}.
 * Constructed by the store, so every component is required.
 *
 * <p>THREADING: immutable value, safe to share between any threads.
 */
public record Feature(
    String id,
    String worldId,
    long revision,
    FeatureData data,
    Actor createdBy,
    Instant createdAt,
    Actor updatedBy,
    Instant updatedAt) {

  public Feature {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedBy, "updatedBy");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  public FeatureType type() {
    return data.type();
  }
}
