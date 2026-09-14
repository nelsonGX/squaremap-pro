package dev.nelsongx.nav.fabric.route;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Result of one route query, shaped after the route JSON schema v1 in {@code CLAUDE.md}. Minecraft-free.
 *
 * <p>Invariants: {@code points} is non-empty iff {@code status == OK}; {@code error} is null iff
 * {@code status == OK}; {@code distance} is 0 unless OK; OK requires non-null {@code from}/{@code to}.
 *
 * @param status outcome status
 * @param world world id in {@code namespace:path} form
 * @param from requested start after Y resolution, or null if unresolvable
 * @param to requested goal after Y resolution, or null if unresolvable
 * @param points simplified polyline (immutable), empty unless OK
 * @param distance 3D Euclidean length along {@code points}, 0 unless OK
 * @param nodesExpanded search effort, 0 if no search ran
 * @param error human-readable detail, null iff OK
 */
// THREADING: immutable value object; created on nav workers (or the caller's thread for fast
// failures) and read on any thread.
public record RouteOutcome(Status status, String world, GridPos from, GridPos to,
    List<GridPos> points, double distance, int nodesExpanded, String error) {

  /** Status values; {@link #wireName()} matches the schema's {@code status} strings exactly. */
  public enum Status {
    OK, NO_PATH, CAP_EXCEEDED, INVALID_REQUEST, WORLD_NOT_FOUND, NOT_READY;

    /** @return the JSON {@code status} value, e.g. {@code no_path} */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  /** Validates invariants and copies {@code points}. */
  public RouteOutcome {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(world, "world");
    Objects.requireNonNull(points, "points");
    points = List.copyOf(points);
    if (nodesExpanded < 0) {
      throw new IllegalArgumentException("nodesExpanded must be >= 0: " + nodesExpanded);
    }
    if (status == Status.OK) {
      if (points.isEmpty()) {
        throw new IllegalArgumentException("OK outcome needs points");
      }
      if (error != null) {
        throw new IllegalArgumentException("OK outcome must not carry an error");
      }
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      if (!(distance >= 0) || Double.isInfinite(distance)) {
        throw new IllegalArgumentException("distance must be finite and >= 0: " + distance);
      }
    } else {
      if (!points.isEmpty()) {
        throw new IllegalArgumentException(status + " outcome must not carry points");
      }
      if (error == null) {
        throw new IllegalArgumentException(status + " outcome needs an error");
      }
      if (distance != 0) {
        throw new IllegalArgumentException(status + " outcome must have distance 0");
      }
    }
  }

  /**
   * A successful route; distance is computed from the points.
   *
   * @param world world id
   * @param from resolved start
   * @param to resolved goal
   * @param points polyline, non-empty
   * @param nodesExpanded search effort
   * @return OK outcome
   */
  public static RouteOutcome ok(String world, GridPos from, GridPos to, List<GridPos> points,
      int nodesExpanded) {
    return new RouteOutcome(Status.OK, world, from, to, points, length(points), nodesExpanded, null);
  }

  /**
   * A non-OK outcome.
   *
   * @param status any status but OK
   * @param world world id
   * @param from resolved start or null
   * @param to resolved goal or null
   * @param nodesExpanded search effort (0 if none ran)
   * @param error human-readable detail, non-null
   * @return failure outcome
   */
  public static RouteOutcome failure(Status status, String world, GridPos from, GridPos to,
      int nodesExpanded, String error) {
    if (status == Status.OK) {
      throw new IllegalArgumentException("use ok() for OK outcomes");
    }
    return new RouteOutcome(status, world, from, to, List.of(), 0, nodesExpanded, error);
  }

  /** @return {@code INVALID_REQUEST} outcome with no endpoints and no search */
  public static RouteOutcome invalidRequest(String world, String error) {
    return failure(Status.INVALID_REQUEST, world, null, null, 0, error);
  }

  /** @return {@code WORLD_NOT_FOUND} outcome */
  public static RouteOutcome worldNotFound(String world, String error) {
    return failure(Status.WORLD_NOT_FOUND, world, null, null, 0, error);
  }

  /** @return {@code NOT_READY} outcome */
  public static RouteOutcome notReady(String world, String error) {
    return failure(Status.NOT_READY, world, null, null, 0, error);
  }

  /**
   * Maps a nav-core {@link PathResult} to an outcome. Pure.
   *
   * <p>Success → OK; NO_PATH → NO_PATH; CAP_EXCEEDED → CAP_EXCEEDED; INVALID_ENDPOINT →
   * INVALID_REQUEST. Failure detail becomes the error (a default text is used if blank).
   *
   * @param world world id
   * @param from resolved start
   * @param to resolved goal
   * @param result search result
   * @return the outcome
   */
  public static RouteOutcome fromPathResult(String world, GridPos from, GridPos to,
      PathResult result) {
    Objects.requireNonNull(result, "result");
    if (result instanceof PathResult.Success s) {
      return ok(world, from, to, s.points(), s.nodesExpanded());
    }
    PathResult.Failure f = (PathResult.Failure) result;
    Status status = switch (f.reason()) {
      case NO_PATH -> Status.NO_PATH;
      case CAP_EXCEEDED -> Status.CAP_EXCEEDED;
      case INVALID_ENDPOINT -> Status.INVALID_REQUEST;
    };
    String error = f.detail().isBlank() ? defaultError(status) : f.detail();
    return failure(status, world, from, to, f.nodesExpanded(), error);
  }

  private static String defaultError(Status status) {
    return switch (status) {
      case NO_PATH -> "no path found";
      case CAP_EXCEEDED -> "search limit reached";
      case INVALID_REQUEST -> "invalid endpoint";
      default -> status.wireName();
    };
  }

  /**
   * 3D Euclidean length along a polyline. Pure.
   *
   * @param points polyline
   * @return total length in blocks (0 for fewer than two points)
   */
  public static double length(List<GridPos> points) {
    double total = 0;
    for (int i = 1; i < points.size(); i++) {
      GridPos a = points.get(i - 1);
      GridPos b = points.get(i);
      double dx = (double) b.x() - a.x();
      double dy = (double) b.y() - a.y();
      double dz = (double) b.z() - a.z();
      total += Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
    return total;
  }
}
