package dev.nelsongx.nav.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.fabric.route.RouteOutcome;
import dev.nelsongx.nav.fabric.route.RouteOutcome.Status;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteJsonTest {

  private static final Set<String> KEYS = Set.of("schemaVersion", "status", "world", "from", "to",
      "points", "distance", "nodesExpanded", "error");
  private static final String W = "minecraft:overworld";

  private static JsonObject parse(RouteOutcome o) {
    String json = RouteJson.write(o);
    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
    assertEquals(KEYS, obj.keySet(), json);
    assertEquals(1, obj.get("schemaVersion").getAsInt());
    assertNumber(obj.get("schemaVersion"));
    assertString(obj.get("status"));
    assertString(obj.get("world"));
    assertTrue(obj.get("points").isJsonArray());
    assertNumber(obj.get("distance"));
    assertNumber(obj.get("nodesExpanded"));
    return obj;
  }

  private static void assertNumber(JsonElement e) {
    assertTrue(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber(), String.valueOf(e));
  }

  private static void assertString(JsonElement e) {
    assertTrue(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString(), String.valueOf(e));
  }

  private static void assertPos(JsonElement e, int x, int y, int z) {
    JsonObject p = e.getAsJsonObject();
    assertEquals(Set.of("x", "y", "z"), p.keySet());
    for (String k : p.keySet()) {
      assertNumber(p.get(k));
    }
    assertEquals(x, p.get("x").getAsInt());
    assertEquals(y, p.get("y").getAsInt());
    assertEquals(z, p.get("z").getAsInt());
  }

  @Test
  void okPayload() {
    GridPos a = new GridPos(0, 64, 0);
    GridPos b = new GridPos(3, 68, 0);
    JsonObject o = parse(RouteOutcome.ok(W, a, b, List.of(a, b), 7));
    assertEquals("ok", o.get("status").getAsString());
    assertPos(o.get("from"), 0, 64, 0);
    assertPos(o.get("to"), 3, 68, 0);
    JsonArray pts = o.getAsJsonArray("points");
    assertEquals(2, pts.size());
    assertPos(pts.get(1), 3, 68, 0);
    assertEquals(5.0, o.get("distance").getAsDouble(), 1e-9);
    assertEquals(7, o.get("nodesExpanded").getAsInt());
    assertTrue(o.get("error").isJsonNull());
  }

  @Test
  void everyFailureStatusHasSchemaShape() {
    GridPos a = new GridPos(1, 2, 3);
    for (Status s : Status.values()) {
      if (s == Status.OK) {
        continue;
      }
      JsonObject o = parse(RouteOutcome.failure(s, "minecraft:the_end", a, null, 11, "why " + s));
      assertEquals(s.wireName(), o.get("status").getAsString());
      assertEquals("minecraft:the_end", o.get("world").getAsString());
      assertPos(o.get("from"), 1, 2, 3);
      assertTrue(o.get("to").isJsonNull(), "explicit null to");
      assertEquals(0, o.getAsJsonArray("points").size());
      assertEquals(0.0, o.get("distance").getAsDouble());
      assertEquals(11, o.get("nodesExpanded").getAsInt());
      assertString(o.get("error"));
      assertEquals("why " + s, o.get("error").getAsString());
    }
  }

  @Test
  void nullEndpointsAreExplicitNulls() {
    String json = RouteJson.write(RouteOutcome.invalidRequest(W, "bad"));
    assertTrue(json.contains("\"from\":null"), json);
    assertTrue(json.contains("\"to\":null"), json);
    JsonObject o = parse(RouteOutcome.notReady(W, "x"));
    assertTrue(o.get("from").isJsonNull());
    assertTrue(o.get("to").isJsonNull());
  }

  @Test
  void okErrorIsExplicitNull() {
    GridPos a = new GridPos(0, 0, 0);
    String json = RouteJson.write(RouteOutcome.ok(W, a, a, List.of(a), 0));
    assertTrue(json.contains("\"error\":null"), json);
  }

  @Test
  void httpStatusMapping() {
    assertEquals(200, RouteJson.httpStatus(Status.OK));
    assertEquals(200, RouteJson.httpStatus(Status.NO_PATH));
    assertEquals(200, RouteJson.httpStatus(Status.CAP_EXCEEDED));
    assertEquals(400, RouteJson.httpStatus(Status.INVALID_REQUEST));
    assertEquals(404, RouteJson.httpStatus(Status.WORLD_NOT_FOUND));
    assertEquals(503, RouteJson.httpStatus(Status.NOT_READY));
  }

  @Test
  void goldenClaudeMdExample() {
    String example = """
        {
          "schemaVersion": 1,
          "status": "ok",
          "world": "minecraft:overworld",
          "from":  { "x": 12, "y": 64, "z": -40 },
          "to":    { "x": 310, "y": 71, "z": 95 },
          "points": [ { "x": 12, "y": 64, "z": -40 }, { "x": 150, "y": 66, "z": 10 }, { "x": 310, "y": 71, "z": 95 } ],
          "distance": 342.7,
          "nodesExpanded": 18422,
          "error": null
        }
        """;
    GridPos from = new GridPos(12, 64, -40);
    GridPos mid = new GridPos(150, 66, 10);
    GridPos to = new GridPos(310, 71, 95);
    // The example's distance is illustrative; build the record directly with that value.
    RouteOutcome o = new RouteOutcome(Status.OK, W, from, to, List.of(from, mid, to), 342.7, 18422,
        null);
    JsonObject expected = normalize(JsonParser.parseString(example).getAsJsonObject());
    JsonObject actual = normalize(JsonParser.parseString(RouteJson.write(o)).getAsJsonObject());
    assertEquals(expected, actual);
    assertEquals(expected.keySet(), actual.keySet());
  }

  /** Gson compares numbers by their parsed value representation; normalize to doubles. */
  private static JsonObject normalize(JsonObject in) {
    JsonObject out = new JsonObject();
    for (String k : in.keySet()) {
      out.add(k, normalize(in.get(k)));
    }
    return out;
  }

  private static JsonElement normalize(JsonElement e) {
    if (e.isJsonObject()) {
      return normalize(e.getAsJsonObject());
    }
    if (e.isJsonArray()) {
      JsonArray a = new JsonArray();
      e.getAsJsonArray().forEach(x -> a.add(normalize(x)));
      return a;
    }
    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
      return new JsonPrimitive(e.getAsDouble());
    }
    return e;
  }
}
