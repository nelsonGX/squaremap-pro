package dev.nelsongx.map.core.feature;

import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates user-supplied {@link FeatureData}. Stateless; all geometry uses exact {@code long}
 * integer arithmetic.
 *
 * <p>THREADING: stateless and immutable, safe to call from any thread (the {@link RailwayLookup} it
 * is given must be safe for the calling thread).
 */
public final class FeatureValidator {
  public static final int MAX_NAME_LENGTH = 64;
  public static final int MAX_DESCRIPTION_LENGTH = 1000;
  public static final int MAX_COORDINATE = 30_000_000;
  public static final int MAX_VERTICES = 2000;

  private static final Pattern COLOUR = Pattern.compile("#[0-9a-fA-F]{6}");

  /**
   * Returns every problem found, or an empty list if {@code data} is valid.
   *
   * @param data user-supplied data; {@code null} yields a {@code type} error
   * @param lookup resolves a station's railway; must not be {@code null}
   */
  public List<ValidationError> validate(FeatureData data, RailwayLookup lookup) {
    Objects.requireNonNull(lookup, "lookup");
    List<ValidationError> errors = new ArrayList<>();
    if (data == null) {
      errors.add(new ValidationError("type", "feature type is required"));
      return List.copyOf(errors);
    }
    switch (data) {
      case BuildingData b -> validateBuilding(b, errors);
      case RoadData r -> validateRoad(r, errors);
      case RailwayData r -> validateRailway(r, errors);
      case StationData s -> validateStation(s, lookup, errors);
    }
    return List.copyOf(errors);
  }

  // ---- per type ------------------------------------------------------------------------------

  private static void validateBuilding(BuildingData b, List<ValidationError> errors) {
    checkName(b.name(), 0, errors);
    if (b.category() == null) {
      errors.add(new ValidationError("category", "category is required"));
    }
    if (length(b.description()) > MAX_DESCRIPTION_LENGTH) {
      errors.add(
          new ValidationError(
              "description",
              "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters"));
    }
    checkRing(b.ring(), errors);
  }

  private static void validateRoad(RoadData r, List<ValidationError> errors) {
    checkName(r.name(), 1, errors);
    if (r.roadClass() == null) {
      errors.add(new ValidationError("roadClass", "road class is required"));
    }
    checkPolyline(r.line(), errors);
  }

  private static void validateRailway(RailwayData r, List<ValidationError> errors) {
    checkName(r.name(), 1, errors);
    if (r.colour() == null || !COLOUR.matcher(r.colour()).matches()) {
      errors.add(new ValidationError("colour", "colour must be #rrggbb"));
    }
    checkPolyline(r.line(), errors);
  }

  private static void validateStation(
      StationData s, RailwayLookup lookup, List<ValidationError> errors) {
    checkName(s.name(), 1, errors);
    boolean pointOk = true;
    if (s.point() == null) {
      errors.add(new ValidationError("geometry", "station needs exactly one vertex"));
      pointOk = false;
    } else if (!inRange(s.point())) {
      errors.add(new ValidationError("geometry", outOfRangeMessage(0)));
      pointOk = false;
    }
    String railwayId = s.railwayId();
    if (railwayId == null || railwayId.isBlank()) {
      errors.add(new ValidationError("railwayId", "railwayId is required"));
      return;
    }
    Optional<RailwayData> railway = lookup.railway(railwayId);
    if (railway == null || railway.isEmpty()) {
      errors.add(new ValidationError("railwayId", "railway " + railwayId + " does not exist"));
      return;
    }
    List<Vertex> line = railway.get().line();
    if (pointOk && (line == null || !line.contains(s.point()))) {
      errors.add(
          new ValidationError("geometry", "station must be on a vertex of railway " + railwayId));
    }
  }

  // ---- fields --------------------------------------------------------------------------------

  private static void checkName(String name, int min, List<ValidationError> errors) {
    int len = length(name); // already stripped by FeatureData
    if (len < min) {
      errors.add(new ValidationError("name", "name is required"));
    } else if (len > MAX_NAME_LENGTH) {
      errors.add(
          new ValidationError("name", "name must be at most " + MAX_NAME_LENGTH + " characters"));
    }
  }

  private static int length(String s) {
    return s == null ? 0 : s.codePointCount(0, s.length());
  }

  private static boolean inRange(Vertex v) {
    return Math.abs((long) v.x()) <= MAX_COORDINATE && Math.abs((long) v.z()) <= MAX_COORDINATE;
  }

  private static String outOfRangeMessage(int index) {
    return "vertex " + index + " is outside ±" + MAX_COORDINATE;
  }

  /** Null, count, null-vertex and range checks shared by rings and polylines. */
  private static boolean checkVertices(
      List<Vertex> vertices, int min, String what, List<ValidationError> errors) {
    if (vertices == null) {
      errors.add(new ValidationError("geometry", "geometry is required"));
      return false;
    }
    if (vertices.size() < min) {
      errors.add(new ValidationError("geometry", what + " needs at least " + min + " vertices"));
      return false;
    }
    if (vertices.size() > MAX_VERTICES) {
      errors.add(
          new ValidationError(
              "geometry", what + " must have at most " + MAX_VERTICES + " vertices"));
      return false;
    }
    for (int i = 0; i < vertices.size(); i++) {
      Vertex v = vertices.get(i);
      if (v == null) {
        errors.add(new ValidationError("geometry", "vertex " + i + " is missing"));
        return false;
      }
      if (!inRange(v)) {
        errors.add(new ValidationError("geometry", outOfRangeMessage(i)));
        return false;
      }
    }
    return true;
  }

  private static void checkPolyline(List<Vertex> line, List<ValidationError> errors) {
    if (!checkVertices(line, 2, "line", errors)) {
      return;
    }
    for (int i = 1; i < line.size(); i++) {
      if (line.get(i).equals(line.get(i - 1))) {
        errors.add(
            new ValidationError(
                "geometry", "vertices " + (i - 1) + " and " + i + " are identical"));
        return;
      }
    }
  }

  private static void checkRing(List<Vertex> ring, List<ValidationError> errors) {
    if (!checkVertices(ring, 3, "building", errors)) {
      return;
    }
    int n = ring.size();
    if (ring.get(0).equals(ring.get(n - 1))) {
      errors.add(
          new ValidationError(
              "geometry", "ring must be open (last vertex must not repeat the first)"));
      return;
    }
    for (int i = 1; i < n; i++) {
      if (ring.get(i).equals(ring.get(i - 1))) {
        errors.add(
            new ValidationError(
                "geometry", "zero-length edge between vertices " + (i - 1) + " and " + i));
        return;
      }
    }
    Set<Vertex> distinct = new HashSet<>(ring);
    if (distinct.size() < 3) {
      errors.add(new ValidationError("geometry", "building needs at least 3 distinct vertices"));
      return;
    }
    String selfIntersection = findSelfIntersection(ring);
    if (selfIntersection != null) {
      errors.add(new ValidationError("geometry", selfIntersection));
      return;
    }
    if (twiceSignedArea(ring) == 0) {
      errors.add(new ValidationError("geometry", "building has zero area"));
    }
  }

  // ---- exact integer geometry ----------------------------------------------------------------

  /**
   * Checks a ring with no zero-length edges for simplicity. Edge {@code i} runs from vertex
   * {@code i} to {@code (i + 1) % n}. Adjacent edges may only share their common vertex (no
   * collinear overlap / fold-back); non-adjacent edges must not touch at all.
   *
   * @return a message describing the first problem, or {@code null} if the ring is simple
   */
  static String findSelfIntersection(List<Vertex> ring) {
    int n = ring.size();
    for (int i = 0; i < n; i++) {
      Vertex a1 = ring.get(i);
      Vertex a2 = ring.get((i + 1) % n);
      for (int j = i + 1; j < n; j++) {
        Vertex b1 = ring.get(j);
        Vertex b2 = ring.get((j + 1) % n);
        boolean adjacent = j == i + 1 || (i == 0 && j == n - 1);
        if (adjacent) {
          // Shared vertex s; the other endpoints p (of edge i) and q (of edge j).
          Vertex s = j == i + 1 ? a2 : a1;
          Vertex p = j == i + 1 ? a1 : a2;
          Vertex q = j == i + 1 ? b2 : b1;
          if (cross(s, p, q) == 0 && dot(s, p, q) > 0) {
            return "edges " + i + " and " + j + " overlap";
          }
        } else if (segmentsIntersect(a1, a2, b1, b2)) {
          return "edges " + i + " and " + j + " intersect";
        }
      }
    }
    return null;
  }

  /** Closed-segment intersection test, touching and collinear overlap included. */
  static boolean segmentsIntersect(Vertex p1, Vertex p2, Vertex p3, Vertex p4) {
    int d1 = Long.signum(cross(p3, p4, p1));
    int d2 = Long.signum(cross(p3, p4, p2));
    int d3 = Long.signum(cross(p1, p2, p3));
    int d4 = Long.signum(cross(p1, p2, p4));
    if (d1 * d2 < 0 && d3 * d4 < 0) {
      return true;
    }
    return (d1 == 0 && onSegment(p3, p4, p1))
        || (d2 == 0 && onSegment(p3, p4, p2))
        || (d3 == 0 && onSegment(p1, p2, p3))
        || (d4 == 0 && onSegment(p1, p2, p4));
  }

  /** (b - o) x (c - o). Coordinates are within ±3e7, so each product fits easily in a long. */
  static long cross(Vertex o, Vertex b, Vertex c) {
    return ((long) b.x() - o.x()) * ((long) c.z() - o.z())
        - ((long) b.z() - o.z()) * ((long) c.x() - o.x());
  }

  private static long dot(Vertex o, Vertex b, Vertex c) {
    return ((long) b.x() - o.x()) * ((long) c.x() - o.x())
        + ((long) b.z() - o.z()) * ((long) c.z() - o.z());
  }

  /** For r collinear with segment p-q: whether r lies within its bounding box. */
  private static boolean onSegment(Vertex p, Vertex q, Vertex r) {
    return Math.min(p.x(), q.x()) <= r.x()
        && r.x() <= Math.max(p.x(), q.x())
        && Math.min(p.z(), q.z()) <= r.z()
        && r.z() <= Math.max(p.z(), q.z());
  }

  /**
   * Shoelace formula, doubled, as a fan from vertex 0. Each term is exact (|delta| ≤ 6e7, so
   * |cross| ≤ 7.2e15). The true total is bounded by twice the bounding-box area (≤ 7.2e15), and
   * {@code long} addition is exact modulo 2^64, so the result is exact even if a partial sum wraps.
   */
  static long twiceSignedArea(List<Vertex> ring) {
    Vertex o = ring.get(0);
    long sum = 0;
    for (int i = 1; i + 1 < ring.size(); i++) {
      sum += cross(o, ring.get(i), ring.get(i + 1));
    }
    return sum;
  }
}
