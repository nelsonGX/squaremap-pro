package dev.nelsongx.map.core.feature;

import java.util.Optional;

/** Building category, with its Feature API v1 wire name. */
public enum BuildingCategory {
  RESIDENTIAL("residential"),
  COMMERCIAL("commercial"),
  PUBLIC("public"),
  INDUSTRIAL("industrial"),
  GOVERNMENT("government"),
  EDUCATION("education"),
  HEALTHCARE("healthcare"),
  RELIGIOUS("religious"),
  FARM("farm"),
  STORAGE("storage"),
  LANDMARK("landmark"),
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

  /** Every wire name, in declaration order, comma-separated — for error messages. */
  public static String wireNames() {
    StringBuilder sb = new StringBuilder();
    for (BuildingCategory value : values()) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(value.wireName);
    }
    return sb.toString();
  }
}
