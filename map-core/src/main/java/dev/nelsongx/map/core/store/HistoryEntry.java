package dev.nelsongx.map.core.store;

import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.FeatureData;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One append-only row of {@code feature_history}.
 *
 * <p>{@code revision} is the feature's revision after a create/update; for a delete it is the
 * deleted revision + 1, so {@code (featureId, revision)} is unique. {@code data} is a snapshot of
 * the feature data after the action (for a delete: the data that was deleted).
 *
 * <p>THREADING: immutable value, safe to share between any threads.
 */
public record HistoryEntry(
    String worldId,
    String featureId,
    long revision,
    Action action,
    Actor actor,
    Instant at,
    FeatureData data) {

  public HistoryEntry {
    Objects.requireNonNull(worldId, "worldId");
    Objects.requireNonNull(featureId, "featureId");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(data, "data");
  }

  public enum Action {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete");

    private final String wireName;

    Action(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }

    public static Optional<Action> fromWire(String wireName) {
      for (Action value : values()) {
        if (value.wireName.equals(wireName)) {
          return Optional.of(value);
        }
      }
      return Optional.empty();
    }
  }
}
