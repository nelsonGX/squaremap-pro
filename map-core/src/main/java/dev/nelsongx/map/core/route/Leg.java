package dev.nelsongx.map.core.route;

import dev.nelsongx.map.core.feature.Vertex;
import java.util.List;
import java.util.Objects;

/**
 * One leg of a route (route schema v2 {@code legs[]}).
 *
 * <p>THREADING: immutable value.
 *
 * @param name road or railway name; {@code null} for walk legs
 * @param points at least 2 integer block vertices, no consecutive duplicates except a 2-point leg
 *     shorter than one block after rounding
 * @param distance 2D blocks
 * @param duration seconds
 * @param fromStation boarding station name for rail legs, else {@code null}
 * @param toStation alighting station name for rail legs, else {@code null}
 */
public record Leg(
    Mode mode,
    String name,
    List<Vertex> points,
    double distance,
    double duration,
    String fromStation,
    String toStation) {

  public Leg {
    Objects.requireNonNull(mode, "mode");
    points = List.copyOf(points);
    if (points.size() < 2) {
      throw new IllegalArgumentException("a leg needs at least 2 points");
    }
  }
}
