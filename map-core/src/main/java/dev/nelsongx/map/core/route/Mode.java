package dev.nelsongx.map.core.route;

import java.util.Optional;

/** Travel mode of a route leg, with its route schema v2 wire name. */
public enum Mode {
  WALK("walk"),
  ROAD("road"),
  RAIL("rail");

  private final String wireName;

  Mode(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }

  /** Exact (case-sensitive) wire-name lookup; empty for {@code null} or unknown names. */
  public static Optional<Mode> fromWire(String wireName) {
    for (Mode value : values()) {
      if (value.wireName.equals(wireName)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
