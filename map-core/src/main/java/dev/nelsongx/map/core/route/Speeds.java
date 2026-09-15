package dev.nelsongx.map.core.route;

import dev.nelsongx.map.core.feature.RoadClass;

/**
 * Travel speeds in blocks per second. All must be finite and positive.
 *
 * <p>THREADING: immutable value.
 */
public record Speeds(
    double walk, double path, double street, double main, double highway, double rail) {

  public Speeds {
    check("walk", walk);
    check("path", path);
    check("street", street);
    check("main", main);
    check("highway", highway);
    check("rail", rail);
  }

  /** walk 4.317, path 4.317, street 5.612, main 7.0, highway 9.0, rail 8.0. */
  public static Speeds defaults() {
    return new Speeds(4.317, 4.317, 5.612, 7.0, 9.0, 8.0);
  }

  public double road(RoadClass roadClass) {
    return switch (roadClass) {
      case PATH -> path;
      case STREET -> street;
      case MAIN -> main;
      case HIGHWAY -> highway;
    };
  }

  double maxRoad() {
    return Math.max(Math.max(path, street), Math.max(main, highway));
  }

  private static void check(String name, double speed) {
    if (!(speed > 0) || !Double.isFinite(speed)) {
      throw new IllegalArgumentException(name + " speed must be finite and > 0: " + speed);
    }
  }
}
