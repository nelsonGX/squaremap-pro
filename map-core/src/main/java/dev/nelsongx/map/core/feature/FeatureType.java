package dev.nelsongx.map.core.feature;

import java.util.Optional;

/** Feature kind, with its Feature API v1 wire name. */
public enum FeatureType {
  BUILDING("building"),
  ROAD("road"),
  RAILWAY("railway"),
  STATION("station");

  private final String wireName;

  FeatureType(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Exact (case-sensitive) wire-name lookup; empty for {@code null} or unknown names. */
  public static Optional<FeatureType> fromWire(String wireName) {
    for (FeatureType value : values()) {
      if (value.wireName.equals(wireName)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
