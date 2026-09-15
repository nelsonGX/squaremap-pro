package dev.nelsongx.map.core.feature;

import java.util.Optional;

/** Road class, with its Feature API v1 wire name. */
public enum RoadClass {
  HIGHWAY("highway"),
  MAIN("main"),
  STREET("street"),
  PATH("path");

  private final String wireName;

  RoadClass(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Exact (case-sensitive) wire-name lookup; empty for {@code null} or unknown names. */
  public static Optional<RoadClass> fromWire(String wireName) {
    for (RoadClass value : values()) {
      if (value.wireName.equals(wireName)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
