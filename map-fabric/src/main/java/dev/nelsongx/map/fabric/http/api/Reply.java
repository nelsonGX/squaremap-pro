package dev.nelsongx.map.fabric.http.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An HTTP response computed by an API handler, written to the Javalin context afterwards.
 *
 * @param status HTTP status
 * @param json JSON body, or null for no body
 * @param headers extra headers as name/value pairs ({@code Set-Cookie} may repeat)
 */
// THREADING: immutable value; created on worker/request threads, written on whichever thread
// completes the request future.
public record Reply(int status, JsonElement json, List<String[]> headers) {

  /** Validates and copies. */
  public Reply {
    headers = List.copyOf(headers);
  }

  /** @return a JSON reply */
  public static Reply json(int status, JsonElement json) {
    return new Reply(status, Objects.requireNonNull(json, "json"), List.of());
  }

  /** @return a body-less reply */
  public static Reply empty(int status) {
    return new Reply(status, null, List.of());
  }

  /** @return {@code {"error": code}} with the given status */
  public static Reply error(int status, String code) {
    JsonObject o = new JsonObject();
    o.addProperty("error", code);
    return json(status, o);
  }

  /** @return a 302 redirect */
  public static Reply redirect(String location) {
    return empty(302).withHeader("Location", location);
  }

  /** @return a copy with one more header */
  public Reply withHeader(String name, String value) {
    List<String[]> h = new ArrayList<>(headers);
    h.add(new String[] {name, value});
    return new Reply(status, json, h);
  }

  /** Thrown inside handler futures to short-circuit with a reply. */
  public static final class Exit extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Reply reply;

    /** @param reply the reply to send */
    public Exit(Reply reply) {
      super(null, null, false, false);
      this.reply = reply;
    }

    /** @return the reply */
    public Reply reply() {
      return reply;
    }
  }
}
