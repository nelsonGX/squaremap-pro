package dev.nelsongx.nav.core;

import java.util.List;
import java.util.Objects;

/**
 * Outcome of a path search: either a {@link Success} with a non-empty list of points, or a
 * {@link Failure} with a reason. Both carry the number of search nodes expanded.
 */
public sealed interface PathResult permits PathResult.Success, PathResult.Failure {

  /**
   * Number of nodes the search expanded to produce this result.
   *
   * @return non-negative node count
   */
  int nodesExpanded();

  /**
   * A found path.
   *
   * @param points path points from start to goal, feet positions; non-empty, immutable, no null
   *     elements (defensively copied)
   * @param nodesExpanded non-negative number of nodes expanded
   */
  record Success(List<GridPos> points, int nodesExpanded) implements PathResult {
    /**
     * Validates and defensively copies.
     *
     * @throws NullPointerException if {@code points} or any element is null
     * @throws IllegalArgumentException if {@code points} is empty or {@code nodesExpanded} is negative
     */
    public Success {
      Objects.requireNonNull(points, "points");
      points = List.copyOf(points);
      if (points.isEmpty()) {
        throw new IllegalArgumentException("points must not be empty");
      }
      if (nodesExpanded < 0) {
        throw new IllegalArgumentException("nodesExpanded must be >= 0: " + nodesExpanded);
      }
    }
  }

  /**
   * A failed search.
   *
   * @param reason why the search failed; non-null
   * @param nodesExpanded non-negative number of nodes expanded before giving up
   * @param detail human-readable detail; non-null
   */
  record Failure(FailureReason reason, int nodesExpanded, String detail) implements PathResult {
    /**
     * Validates.
     *
     * @throws NullPointerException if {@code reason} or {@code detail} is null
     * @throws IllegalArgumentException if {@code nodesExpanded} is negative
     */
    public Failure {
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(detail, "detail");
      if (nodesExpanded < 0) {
        throw new IllegalArgumentException("nodesExpanded must be >= 0: " + nodesExpanded);
      }
    }
  }

  /** Why a search failed. */
  enum FailureReason {
    /** The search space was exhausted without reaching the goal. */
    NO_PATH,
    /** The search hit its node/effort cap before reaching the goal. */
    CAP_EXCEEDED,
    /** The start or goal is not a walkable position (or otherwise unusable). */
    INVALID_ENDPOINT
  }
}
