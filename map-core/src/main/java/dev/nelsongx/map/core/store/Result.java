package dev.nelsongx.map.core.store;

import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.ValidationError;
import java.util.List;
import java.util.Objects;

/**
 * Outcome of a {@link FeatureStore} write.
 *
 * <p>THREADING: immutable value, safe to share between any threads.
 */
public sealed interface Result
    permits Result.Ok, Result.Invalid, Result.NotFound, Result.Conflict, Result.Rejected {

  /** The written feature; for a delete, the feature as it was just before deletion. */
  record Ok(Feature feature) implements Result {
    public Ok {
      Objects.requireNonNull(feature, "feature");
    }
  }

  /** Validation failed (HTTP 400). Nothing was written. */
  record Invalid(List<ValidationError> errors) implements Result {
    public Invalid {
      errors = List.copyOf(errors);
    }
  }

  /** No feature with that id in that world (HTTP 404). */
  record NotFound() implements Result {}

  /** The expected revision is stale (HTTP 409); {@code current} is the stored feature. */
  record Conflict(Feature current) implements Result {
    public Conflict {
      Objects.requireNonNull(current, "current");
    }
  }

  /** The operation is refused by a referential rule (HTTP 422), e.g. a railway with stations. */
  record Rejected(String reason) implements Result {
    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
