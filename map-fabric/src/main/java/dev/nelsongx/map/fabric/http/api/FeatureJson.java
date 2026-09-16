package dev.nelsongx.map.fabric.http.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.BuildingCategory;
import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData;
import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.FeatureType;
import dev.nelsongx.map.core.feature.RoadClass;
import dev.nelsongx.map.core.feature.ValidationError;
import dev.nelsongx.map.core.feature.Vertex;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JSON encoding of Features v1 (CLAUDE.md "HTTP API"): feature objects and {@code FeatureInput}
 * bodies. Structural problems in an input are reported as {@link ValidationError}s with the same field
 * names the core validator uses ({@code body}, {@code type}, {@code name}, {@code geometry},
 * {@code category}, {@code description}, {@code roadClass}, {@code colour}, {@code railwayId},
 * {@code revision}); semantic rules are left to {@code FeatureValidator} inside the store.
 */
// THREADING: pure static functions; any thread.
public final class FeatureJson {

  private FeatureJson() {
  }

  /**
   * A parsed {@code FeatureInput}.
   *
   * @param data feature data (fields may be null; the store validates)
   * @param revision {@code revision} for PUT, else null
   * @param errors structural errors; when non-empty {@code data} may be null
   */
  public record Input(FeatureData data, Long revision, List<ValidationError> errors) {
  }

  // ---- output ----------------------------------------------------------------------------------

  /** @return the Features v1 JSON object */
  public static JsonObject toJson(Feature f) {
    JsonObject o = new JsonObject();
    FeatureData d = f.data();
    o.addProperty("id", f.id());
    o.addProperty("type", d.type().wireName());
    o.addProperty("revision", f.revision());
    o.addProperty("name", d.name() == null ? "" : d.name());
    JsonArray geometry = new JsonArray();
    for (Vertex v : d.geometry()) {
      geometry.add(xz(v));
    }
    o.add("geometry", geometry);
    JsonObject props = new JsonObject();
    switch (d) {
      case BuildingData b -> {
        props.addProperty("category", b.category() == null ? null : b.category().wireName());
        props.addProperty("description", b.description());
      }
      case RoadData r -> props.addProperty("roadClass",
          r.roadClass() == null ? null : r.roadClass().wireName());
      case RailwayData r -> props.addProperty("colour", r.colour());
      case StationData s -> props.addProperty("railwayId", s.railwayId());
    }
    o.add("props", props);
    o.add("createdBy", actor(f.createdBy()));
    o.addProperty("createdAt", f.createdAt().toString());
    o.add("updatedBy", actor(f.updatedBy()));
    o.addProperty("updatedAt", f.updatedAt().toString());
    return o;
  }

  /** @return {@code {"x":..,"z":..}} */
  public static JsonObject xz(Vertex v) {
    JsonObject o = new JsonObject();
    o.addProperty("x", v.x());
    o.addProperty("z", v.z());
    return o;
  }

  private static JsonObject actor(Actor a) {
    JsonObject o = new JsonObject();
    o.addProperty("uuid", a.uuid().toString());
    o.addProperty("name", a.name());
    return o;
  }

  // ---- input -----------------------------------------------------------------------------------

  /**
   * Parses strict JSON text (no trailing content, no lenient syntax).
   *
   * @param text body
   * @return the element, or null if the text is not a single valid JSON value
   */
  public static JsonElement parseStrict(String text) {
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement e = JsonParser.parseReader(reader);
      // parseReader maps an empty document to JsonNull
      if (text.isBlank() || reader.peek() != JsonToken.END_DOCUMENT) {
        return null;
      }
      return e;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Parses a {@code FeatureInput} (POST) or {@code FeatureInput + revision} (PUT) body.
   *
   * @param body raw body text
   * @param requireRevision whether {@code revision} is required
   * @return parsed input with structural errors
   */
  public static Input parseInput(String body, boolean requireRevision) {
    List<ValidationError> errors = new ArrayList<>();
    JsonElement root = parseStrict(body);
    if (root == null || !root.isJsonObject()) {
      errors.add(new ValidationError("body", "request body must be a JSON object"));
      return new Input(null, null, errors);
    }
    JsonObject o = root.getAsJsonObject();

    Long revision = null;
    if (requireRevision) {
      JsonElement r = o.get("revision");
      Long parsed = r == null ? null : asLong(r);
      if (parsed == null || parsed < 1) {
        errors.add(new ValidationError("revision", "revision must be a positive integer"));
      } else {
        revision = parsed;
      }
    }

    JsonElement typeEl = o.get("type");
    FeatureType type = null;
    if (typeEl == null || typeEl.isJsonNull()) {
      errors.add(new ValidationError("type", "feature type is required"));
    } else if (!isString(typeEl) || (type = FeatureType.fromWire(typeEl.getAsString()).orElse(null)) == null) {
      errors.add(new ValidationError("type", "type must be one of building, road, railway, station"));
    }

    String name = optString(o, "name", "name", errors);

    List<Vertex> geometry = null;
    JsonElement g = o.get("geometry");
    if (g != null && !g.isJsonNull()) {
      if (!g.isJsonArray()) {
        errors.add(new ValidationError("geometry", "geometry must be an array of {x, z}"));
      } else {
        geometry = new ArrayList<>();
        int i = 0;
        for (JsonElement v : g.getAsJsonArray()) {
          Vertex vertex = vertex(v);
          if (vertex == null) {
            errors.add(new ValidationError("geometry",
                "vertex " + i + " must be {x, z} with integer block coordinates"));
            geometry = null;
            break;
          }
          geometry.add(vertex);
          i++;
        }
      }
    }

    JsonObject props = new JsonObject();
    JsonElement p = o.get("props");
    if (p != null && !p.isJsonNull()) {
      if (p.isJsonObject()) {
        props = p.getAsJsonObject();
      } else {
        errors.add(new ValidationError("props", "props must be an object"));
      }
    }

    if (type == null || !errors.isEmpty()) {
      return new Input(null, revision, errors);
    }
    FeatureData data = switch (type) {
      case BUILDING -> {
        String category = optString(props, "category", "category", errors);
        BuildingCategory cat = null;
        if (category != null) {
          cat = BuildingCategory.fromWire(category).orElse(null);
          if (cat == null) {
            errors.add(new ValidationError("category",
                "category must be one of " + BuildingCategory.wireNames()));
          }
        }
        String description = optString(props, "description", "description", errors);
        yield new BuildingData(geometry, name, cat, description);
      }
      case ROAD -> {
        String rc = optString(props, "roadClass", "roadClass", errors);
        RoadClass roadClass = null;
        if (rc != null) {
          roadClass = RoadClass.fromWire(rc).orElse(null);
          if (roadClass == null) {
            errors.add(new ValidationError("roadClass",
                "roadClass must be one of highway, main, street, path"));
          }
        }
        yield new RoadData(geometry, name, roadClass);
      }
      case RAILWAY -> {
        String colour = optString(props, "colour", "colour", errors);
        yield new RailwayData(geometry, name, colour == null ? null : colour.toLowerCase(Locale.ROOT));
      }
      case STATION -> {
        String railwayId = optString(props, "railwayId", "railwayId", errors);
        Vertex point = geometry != null && geometry.size() == 1 ? geometry.get(0) : null;
        if (geometry != null && geometry.size() != 1) {
          errors.add(new ValidationError("geometry", "station needs exactly one vertex"));
        }
        yield new StationData(point, name, railwayId);
      }
    };
    return new Input(errors.isEmpty() ? data : null, revision, errors);
  }

  /** @return {@code {"error":"validation","details":[...]}} */
  public static JsonObject validationError(List<ValidationError> errors) {
    JsonObject o = new JsonObject();
    o.addProperty("error", "validation");
    JsonArray details = new JsonArray();
    for (ValidationError e : errors) {
      JsonObject d = new JsonObject();
      d.addProperty("field", e.field());
      d.addProperty("message", e.message());
      details.add(d);
    }
    o.add("details", details);
    return o;
  }

  private static String optString(JsonObject o, String key, String field,
      List<ValidationError> errors) {
    JsonElement e = o.get(key);
    if (e == null || e.isJsonNull()) {
      return null;
    }
    if (!isString(e)) {
      errors.add(new ValidationError(field, key + " must be a string"));
      return null;
    }
    return e.getAsString();
  }

  private static boolean isString(JsonElement e) {
    return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
  }

  private static Vertex vertex(JsonElement e) {
    if (e == null || !e.isJsonObject()) {
      return null;
    }
    JsonObject o = e.getAsJsonObject();
    Long x = o.has("x") ? asLong(o.get("x")) : null;
    Long z = o.has("z") ? asLong(o.get("z")) : null;
    if (x == null || z == null || x != x.intValue() || z != z.intValue()) {
      return null;
    }
    return new Vertex(x.intValue(), z.intValue());
  }

  /** @return the integral value of a JSON number (e.g. {@code 3} or {@code 3.0}), else null */
  static Long asLong(JsonElement e) {
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    try {
      BigDecimal d = new BigDecimal(e.getAsJsonPrimitive().getAsString());
      return d.stripTrailingZeros().scale() <= 0 ? d.longValueExact() : null;
    } catch (ArithmeticException | NumberFormatException ex) {
      return null;
    }
  }
}
