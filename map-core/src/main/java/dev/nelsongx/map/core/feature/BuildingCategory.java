package dev.nelsongx.map.core.feature;

import java.util.Optional;

/** Building category, with its Feature API v1 wire name. */
public enum BuildingCategory {
  RESIDENTIAL("residential"),
  COMMERCIAL("commercial"),
  PUBLIC("public"),
  INDUSTRIAL("industrial"),
  OTHER("other");

  private final String wireName;

  BuildingCategory(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Exact (case-sensitive) wire-name lookup; empty for {@code null} or unknown names. */
  public static Optional<BuildingCategory> fromWire(String wireName) {
    for (BuildingCategory value : values()) {
      if (value.wireName.equals(wireName)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
