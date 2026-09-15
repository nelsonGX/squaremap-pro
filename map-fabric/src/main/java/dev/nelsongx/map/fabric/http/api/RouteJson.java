package dev.nelsongx.map.fabric.http.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.core.route.Leg;
import dev.nelsongx.map.core.route.Mode;
import dev.nelsongx.map.core.route.RouteResult;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Route schema v2 (CLAUDE.md): request parsing and response encoding. */
// THREADING: pure static functions; any thread.
public final class RouteJson {

  /** Response {@code schemaVersion}. */
  public static final int SCHEMA_VERSION = 2;

  private static final Pattern XZ = Pattern.compile("(-?[0-9]{1,10}),(-?[0-9]{1,10})");

  private RouteJson() {
  }

  /**
   * @param raw {@code x,z} with integer block coordinates, no spaces
   * @return the vertex, or empty if malformed or out of int range
   */
  public static Optional<Vertex> parseXz(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    Matcher m = XZ.matcher(raw);
    if (!m.matches()) {
      return Optional.empty();
    }
    try {
      return Optional.of(new Vertex(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  /**
   * Parses {@code modes}. Absent = all modes. {@code walk} is always allowed (accepted and ignored).
   *
   * @param raw comma-separated modes, or null
   * @return allowed modes, or empty if any entry is unknown
   */
  public static Optional<Set<Mode>> parseModes(String raw) {
    if (raw == null) {
      return Optional.of(EnumSet.allOf(Mode.class));
    }
    Set<Mode> modes = EnumSet.of(Mode.WALK);
    if (raw.isEmpty()) {
      return Optional.of(modes);
    }
    for (String part : raw.split(",", -1)) {
      Optional<Mode> m = Mode.fromWire(part);
      if (m.isEmpty()) {
        return Optional.empty();
      }
      modes.add(m.get());
    }
    return Optional.of(modes);
  }

  /** @return a non-ok response ({@code legs: []}, distance and duration 0) */
  public static JsonObject failure(String status, String world, Vertex from, Vertex to,
      String error) {
    JsonObject o = base(status, world, from, to);
    o.add("legs", new JsonArray());
    o.addProperty("distance", 0);
    o.addProperty("duration", 0);
    o.addProperty("error", error);
    return o;
  }

  /** @return the response for a router result */
  public static JsonObject result(String world, Vertex from, Vertex to, RouteResult result) {
    return switch (result) {
      case RouteResult.NoPath np -> failure("no_path", world, from, to, np.reason());
      case RouteResult.Found found -> {
        JsonObject o = base("ok", world, from, to);
        JsonArray legs = new JsonArray();
        for (Leg leg : found.legs()) {
          JsonObject l = new JsonObject();
          l.addProperty("mode", leg.mode().wireName());
          l.addProperty("name", leg.name());
          JsonArray points = new JsonArray();
          for (Vertex v : leg.points()) {
            points.add(FeatureJson.xz(v));
          }
          l.add("points", points);
          l.addProperty("distance", round1(leg.distance()));
          l.addProperty("duration", round1(leg.duration()));
          boolean rail = leg.mode() == Mode.RAIL;
          l.addProperty("fromStation", rail ? nonNull(leg.fromStation()) : null);
          l.addProperty("toStation", rail ? nonNull(leg.toStation()) : null);
          legs.add(l);
        }
        o.add("legs", legs);
        o.addProperty("distance", round1(found.distance()));
        o.addProperty("duration", round1(found.duration()));
        o.add("error", JsonNull.INSTANCE);
        yield o;
      }
    };
  }

  private static String nonNull(String s) {
    return s == null ? "" : s;
  }

  private static JsonObject base(String status, String world, Vertex from, Vertex to) {
    JsonObject o = new JsonObject();
    o.addProperty("schemaVersion", SCHEMA_VERSION);
    o.addProperty("status", status);
    o.addProperty("world", world == null ? "" : world);
    o.add("from", from == null ? JsonNull.INSTANCE : FeatureJson.xz(from));
    o.add("to", to == null ? JsonNull.INSTANCE : FeatureJson.xz(to));
    return o;
  }

  /** @return {@code v} rounded to one decimal, never negative */
  static double round1(double v) {
    return Math.max(0, Math.round(v * 10) / 10.0);
  }
}
