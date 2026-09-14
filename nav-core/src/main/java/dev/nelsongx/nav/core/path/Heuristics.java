package dev.nelsongx.nav.core.path;

/**
 * Search heuristics consistent with {@link MovementModel} costs.
 */
// THREADING: stateless pure functions; safe from any thread.
public final class Heuristics {

  private static final double SQRT2_MINUS_1 = Math.sqrt(2.0) - 1.0;

  private Heuristics() {
  }

  /**
   * Octile distance: {@code max(|dx|,|dz|) + (√2 − 1)·min(|dx|,|dz|)}.
   *
   * <p><b>Admissible and consistent</b> for {@link MovementModel}: every legal move changes x and z
   * by at most 1 each and costs at least its horizontal base (1 cardinal, √2 diagonal), because the
   * step-up and drop surcharges are non-negative. Octile distance is exactly the cheapest cost of
   * covering {@code (dx, dz)} with such unit moves on an obstacle-free, flat 8-connected grid, so no
   * path can be cheaper. A single move changes the octile value by at most that move's horizontal
   * base cost, which gives consistency ({@code h(n) <= c(n,m) + h(m)}), so A* never needs to reopen
   * closed nodes. Y is ignored.
   *
   * @param dx x difference
   * @param dz z difference
   * @return octile distance (non-negative)
   */
  public static double octile(int dx, int dz) {
    long ax = Math.abs((long) dx);
    long az = Math.abs((long) dz);
    long max = Math.max(ax, az);
    long min = Math.min(ax, az);
    return max + SQRT2_MINUS_1 * min;
  }
}
