package dev.nelsongx.map.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapHttpServerTest {

  private static final String ALLOWED = "http://localhost:3000";

  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private MapHttpServer server;

  @BeforeEach
  void setUp() {
    server = new MapHttpServer(new HttpConfig(true, "127.0.0.1", 0, List.of(ALLOWED), 5000, 4));
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private HttpResponse<String> get(String path, String origin) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
    if (origin != null) {
      b.header("Origin", origin);
    }
    return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private static JsonObject body(HttpResponse<String> r) {
    return JsonParser.parseString(r.body()).getAsJsonObject();
  }

  private static void assertJsonHeaders(HttpResponse<String> r) {
    String ct = r.headers().firstValue("Content-Type").orElse("");
    assertTrue(ct.toLowerCase().startsWith("application/json"), ct);
    assertTrue(ct.toLowerCase().contains("utf-8"), ct);
  }

  @Test
  void health() throws Exception {
    HttpResponse<String> r = get("/api/health", null);
    assertEquals(200, r.statusCode());
    assertJsonHeaders(r);
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    assertTrue(body(r).get("ok").getAsBoolean());
  }

  @Test
  void unknownPathAndMethodAre404Json() throws Exception {
    HttpResponse<String> r = get("/api/route-v1?from=1,2&to=3,4", null);
    assertEquals(404, r.statusCode());
    assertJsonHeaders(r);
    assertTrue(body(r).has("error"));

    HttpResponse<String> post = client.send(HttpRequest.newBuilder(uri("/api/health"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, post.statusCode());
    assertTrue(body(post).has("error"));
  }

  @Test
  void corsHeaderOnlyForAllowedOrigin() throws Exception {
    HttpResponse<String> allowed = get("/api/health", ALLOWED);
    assertEquals(ALLOWED, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    assertEquals("true",
        allowed.headers().firstValue("Access-Control-Allow-Credentials").orElse(null));

    HttpResponse<String> other = get("/api/health", "http://evil.example");
    assertEquals(200, other.statusCode());
    assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

    HttpResponse<String> none = get("/api/health", null);
    assertTrue(none.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  }

  @Test
  void preflightOnlyForAllowedOriginAndApiMethods() throws Exception {
    HttpResponse<String> ok = client.send(HttpRequest.newBuilder(uri("/api/health"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", ALLOWED).header("Access-Control-Request-Method", "GET").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(204, ok.statusCode());
    assertEquals("GET, POST, PUT, DELETE",
        ok.headers().firstValue("Access-Control-Allow-Methods").orElse(null));
    assertTrue(ok.headers().firstValue("Access-Control-Allow-Headers").orElse("")
        .contains("X-Requested-With"));

    HttpResponse<String> patch = client.send(HttpRequest.newBuilder(uri("/api/health"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", ALLOWED).header("Access-Control-Request-Method", "PATCH").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(404, patch.statusCode());

    HttpResponse<String> evil = client.send(HttpRequest.newBuilder(uri("/api/health"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", "http://evil.example").header("Access-Control-Request-Method", "POST")
        .build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, evil.statusCode());
  }

  @Test
  void errorJsonIsEscaped() {
    assertEquals("{\"error\":\"a \\\"b\\\"\"}", MapHttpServer.error("a \"b\""));
  }
}
