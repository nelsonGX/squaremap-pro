package dev.nelsongx.map.core.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The user-editable part of a feature (Feature API v1 {@code type}, {@code name}, {@code geometry},
 * {@code props}).
 *
 * <p>Instances are immutable but deliberately <em>not</em> validated on construction: they may hold
 * {@code null}s or invalid geometry decoded from user input, which {@link FeatureValidator} reports
 * as {@link ValidationError}s. Names and descriptions are {@link String#strip() stripped} on
 * construction; a {@code null} building name or description becomes {@code ""}.
 *
 * <p>THREADING: immutable value, safe to share between any threads.
 */
public sealed interface FeatureData
    permits FeatureData.BuildingData,
        FeatureData.RoadData,
        FeatureData.RailwayData,
        FeatureData.StationData {

  FeatureType type();

  /** Stripped name; {@code ""} for an unnamed building, may be {@code null} for other types. */
  String name();

  /** Geometry as a vertex list: open ring, polyline, or a one-element list for a station. */
  List<Vertex> geometry();

  /** A closed polygon given as an open ring (first vertex not repeated at the end). */
  record BuildingData(List<Vertex> ring, String name, BuildingCategory category, String description)
      implements FeatureData {
    public BuildingData {
      ring = copy(ring);
      name = name == null ? "" : name.strip();
      description = description == null ? "" : description.strip();
    }

    @Override
    public FeatureType type() {
      return FeatureType.BUILDING;
    }

    @Override
    public List<Vertex> geometry() {
      return ring;
    }
  }

  record RoadData(List<Vertex> line, String name, RoadClass roadClass) implements FeatureData {
    public RoadData {
      line = copy(line);
      name = strip(name);
    }

    @Override
    public FeatureType type() {
      return FeatureType.ROAD;
    }

    @Override
    public List<Vertex> geometry() {
      return line;
    }
  }

  record RailwayData(List<Vertex> line, String name, String colour) implements FeatureData {
    public RailwayData {
      line = copy(line);
      name = strip(name);
    }

    @Override
    public FeatureType type() {
      return FeatureType.RAILWAY;
    }

    @Override
    public List<Vertex> geometry() {
      return line;
    }
  }

  record StationData(Vertex point, String name, String railwayId) implements FeatureData {
    public StationData {
      name = strip(name);
    }

    @Override
    public FeatureType type() {
      return FeatureType.STATION;
    }

    @Override
    public List<Vertex> geometry() {
      return point == null ? List.of() : List.of(point);
    }
  }

  private static String strip(String s) {
    return s == null ? null : s.strip();
  }

  /**
   * Immutable defensive copy. {@link List#copyOf} is used when possible; a list containing
   * {@code null} elements (invalid user input, reported by the validator) is copied without NPE.
   */
  private static List<Vertex> copy(List<Vertex> list) {
    if (list == null) {
      return null;
    }
    for (Vertex v : list) {
      if (v == null) {
        return Collections.unmodifiableList(new ArrayList<>(list));
      }
    }
    return List.copyOf(list);
  }
}
