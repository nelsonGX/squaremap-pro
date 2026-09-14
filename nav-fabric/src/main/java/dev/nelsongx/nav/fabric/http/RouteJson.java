package dev.nelsongx.nav.fabric.http;

import com.google.gson.stream.JsonWriter;
import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.fabric.route.RouteOutcome;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;

/**
 * Serializes {@link RouteOutcome} to the route JSON schema v1 ({@code CLAUDE.md}) with Gson's
 * streaming {@link JsonWriter} (Gson ships with Minecraft). Pure and Minecraft-free.
 */
// THREADING: stateless static functions; any thread.
public final class RouteJson {

  /** Schema version written to every payload. */
  public static final int SCHEMA_VERSION = 1;
  /** Content-Type of every JSON response. */
  public static final String CONTENT_TYPE = "application/json; charset=utf-8";

  private RouteJson() {
  }

  /**
   * Writes the schema v1 payload. Null {@code from}/{@code to}/{@code error} are written as explicit
   * JSON nulls.
   *
   * @param outcome the outcome
   * @return JSON text
   */
  public static String write(RouteOutcome outcome) {
    StringWriter sw = new StringWriter();
    try (JsonWriter w = new JsonWriter(sw)) {
      w.setSerializeNulls(true);
      w.beginObject();
      w.name("schemaVersion").value(SCHEMA_VERSION);
      w.name("status").value(outcome.status().wireName());
      w.name("world").value(outcome.world());
      w.name("from");
      pos(w, outcome.from());
      w.name("to");
      pos(w, outcome.to());
      w.name("points").beginArray();
      if (outcome.status() == RouteOutcome.Status.OK) {
        for (GridPos p : outcome.points()) {
          pos(w, p);
        }
      }
      w.endArray();
      w.name("distance").value(outcome.status() == RouteOutcome.Status.OK ? outcome.distance() : 0);
      w.name("nodesExpanded").value(outcome.nodesExpanded());
      w.name("error");
      if (outcome.error() == null) {
        w.nullValue();
      } else {
        w.value(outcome.error());
      }
      w.endObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e); // StringWriter never throws
    }
    return sw.toString();
  }

  private static void pos(JsonWriter w, GridPos p) throws IOException {
    if (p == null) {
      w.nullValue();
      return;
    }
    w.beginObject();
    w.name("x").value(p.x());
    w.name("y").value(p.y());
    w.name("z").value(p.z());
    w.endObject();
  }

  /**
   * HTTP status for an outcome status (schema v1).
   *
   * @param status outcome status
   * @return 200 for ok/no_path/cap_exceeded, 400 invalid_request, 404 world_not_found, 503 not_ready
   */
  public static int httpStatus(RouteOutcome.Status status) {
    return switch (status) {
      case OK, NO_PATH, CAP_EXCEEDED -> 200;
      case INVALID_REQUEST -> 400;
      case WORLD_NOT_FOUND -> 404;
      case NOT_READY -> 503;
    };
  }

  /** @return {@code {"ok":true}} */
  public static String health() {
    return "{\"ok\":true}";
  }

  /**
   * Body for non-route errors (unknown path, method, internal error).
   *
   * @param message detail
   * @return {@code {"error":"..."}}
   */
  public static String error(String message) {
    StringWriter sw = new StringWriter();
    try (JsonWriter w = new JsonWriter(sw)) {
      w.beginObject().name("error").value(message).endObject();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sw.toString();
  }
}
