package dev.nelsongx.nav.fabric.http;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and validates {@code GET /route} query parameters. Pure and Minecraft-free.
 *
 * <p>{@code from}/{@code to}: exactly {@code <int>,<int>} (optional leading {@code -}, digits only, no
 * whitespace, no floats, no extra parts), each within ±{@value #MAX_COORD}. {@code world}: optional,
 * {@code [a-z0-9_.-]+:[a-z0-9_./-]+}, default {@value #DEFAULT_WORLD}.
 */
// THREADING: stateless static functions; any thread.
public final class RouteRequestParser {

  /** World used when the {@code world} parameter is absent. */
  public static final String DEFAULT_WORLD = "minecraft:overworld";
  /** Largest accepted absolute block coordinate. */
  public static final int MAX_COORD = 30_000_000;

  private static final Pattern WORLD = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
  private static final Pattern POINT = Pattern.compile("(-?[0-9]+),(-?[0-9]+)");

  private RouteRequestParser() {
  }

  /** Parse result. */
  public sealed interface Result permits Ok, Invalid {
  }

  /**
   * Valid request.
   *
   * @param query the query
   */
  public record Ok(RouteQuery query) implements Result {
  }

  /**
   * Invalid request.
   *
   * @param world the requested world if it was valid, else {@link #DEFAULT_WORLD}
   * @param error human-readable reason
   */
  public record Invalid(String world, String error) implements Result {
  }

  /**
   * Parses the raw query parameter values.
   *
   * @param from raw {@code from} value or null if absent
   * @param to raw {@code to} value or null if absent
   * @param world raw {@code world} value or null if absent
   * @return {@link Ok} or {@link Invalid}
   */
  public static Result parse(String from, String to, String world) {
    String w = DEFAULT_WORLD;
    if (world != null) {
      if (!WORLD.matcher(world).matches()) {
        return new Invalid(DEFAULT_WORLD, "invalid 'world' value \"" + world
            + "\": expected namespace:path, e.g. minecraft:overworld");
      }
      w = world;
    }
    Object a = point("from", from);
    if (a instanceof String err) {
      return new Invalid(w, err);
    }
    Object b = point("to", to);
    if (b instanceof String err) {
      return new Invalid(w, err);
    }
    int[] p = (int[]) a;
    int[] q = (int[]) b;
    return new Ok(new RouteQuery(w, p[0], p[1], q[0], q[1]));
  }

  /** @return {@code int[]{x, z}} or an error string */
  private static Object point(String name, String raw) {
    if (raw == null) {
      return "missing '" + name + "' parameter: expected " + name + "=<x>,<z> (integer blocks)";
    }
    Matcher m = POINT.matcher(raw);
    if (!m.matches()) {
      return "invalid '" + name + "' value \"" + raw
          + "\": expected two integers <x>,<z> with no spaces, e.g. " + name + "=12,-40";
    }
    Integer x = coord(m.group(1));
    Integer z = coord(m.group(2));
    if (x == null || z == null) {
      return "'" + name + "' value \"" + raw + "\" out of range: coordinates must be within ±"
          + MAX_COORD;
    }
    return new int[] {x, z};
  }

  /** @return the value, or null if outside ±MAX_COORD (including overflow) */
  private static Integer coord(String digits) {
    String unsigned = digits.startsWith("-") ? digits.substring(1) : digits;
    String stripped = unsigned.replaceFirst("^0+(?=.)", "");
    if (stripped.length() > 9) {
      return null;
    }
    long v = Long.parseLong(digits.startsWith("-") ? "-" + stripped : stripped);
    return Math.abs(v) <= MAX_COORD ? (int) v : null;
  }
}
