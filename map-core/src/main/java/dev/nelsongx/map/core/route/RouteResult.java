package dev.nelsongx.map.core.route;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of {@link Router#route}.
 *
 * <p>THREADING: immutable value.
 */
public sealed interface RouteResult permits RouteResult.Found, RouteResult.NoPath {

  /**
   * A route. Consecutive legs share their boundary point; the first point is {@code from}, the last
   * is {@code to}. {@code distance} and {@code duration} are the sums over the legs.
   */
  record Found(List<Leg> legs, double distance, double duration) implements RouteResult {
    public Found {
      legs = List.copyOf(legs);
    }
  }

  /** No route within the walking limits and allowed modes. */
  record NoPath(String reason) implements RouteResult {
    public NoPath {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
