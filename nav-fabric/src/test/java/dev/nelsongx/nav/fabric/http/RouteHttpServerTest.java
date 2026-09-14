package dev.nelsongx.nav.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.fabric.route.RouteOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RouteHttpServerTest {

  private static final String ALLOWED = "http://localhost:3000";

  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final List<CompletableFuture<RouteOutcome>> pending = new CopyOnWriteArrayList<>();
  private RouteHttpServer server;

  @AfterEach
  void tearDown() {
    for (CompletableFuture<RouteOutcome> f : pending) {
      f.complete(RouteOutcome.notReady("minecraft:overworld", "test teardown"));
    }
    if (server != null) {
      server.stop();
    }
  }

  private void start(long timeoutMs, int maxConcurrent, RouteFunction routes) {
    server = new RouteHttpServer(new HttpConfig(true, "127.0.0.1", 0, List.of(ALLOWED), timeoutMs,
        maxConcurrent), routes);
    server.start();
  }

  private static RouteFunction okRoutes() {
    return q -> {
      GridPos a = new GridPos(q.fromX(), 64, q.fromZ());
      GridPos b = new GridPos(q.toX(), 70, q.toZ());
      return CompletableFuture.supplyAsync(() -> RouteOutcome.ok(q.world(), a, b, List.of(a, b), 42));
    };
  }

  private HttpResponse<String> get(String pathAndQuery, String origin) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(
        URI.create("http://127.0.0.1:" + server.port() + pathAndQuery)).timeout(Duration.ofSeconds(10));
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
  void okRoute() throws Exception {
    start(5000, 4, okRoutes());
    HttpResponse<String> r = get("/route?from=1,-2&to=30,40&world=minecraft:the_nether", null);
    assertEquals(200, r.statusCode());
    assertJsonHeaders(r);
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    JsonObject o = body(r);
    assertEquals("ok", o.get("status").getAsString());
    assertEquals("minecraft:the_nether", o.get("world").getAsString());
    assertEquals(1, o.getAsJsonObject("from").get("x").getAsInt());
    assertEquals(-2, o.getAsJsonObject("from").get("z").getAsInt());
    assertEquals(2, o.getAsJsonArray("points").size());
    assertEquals(42, o.get("nodesExpanded").getAsInt());
    assertTrue(o.get("error").isJsonNull());
  }

  @Test
  void health() throws Exception {
    start(5000, 4, okRoutes());
    HttpResponse<String> r = get("/health", null);
    assertEquals(200, r.statusCode());
    assertTrue(body(r).get("ok").getAsBoolean());
  }

  @Test
  void badParamsAre400() throws Exception {
    start(5000, 4, q -> {
      throw new AssertionError("must not route invalid requests");
    });
    HttpResponse<String> r = get("/route?from=1.5,2&to=3,4&world=minecraft:the_end", null);
    assertEquals(400, r.statusCode());
    assertJsonHeaders(r);
    JsonObject o = body(r);
    assertEquals("invalid_request", o.get("status").getAsString());
    assertEquals("minecraft:the_end", o.get("world").getAsString());
    assertTrue(o.get("from").isJsonNull());
    assertTrue(o.get("to").isJsonNull());
    assertTrue(o.get("error").getAsString().contains("from"));

    HttpResponse<String> missing = get("/route?from=1,2", null);
    assertEquals(400, missing.statusCode());
  }

  @Test
  void worldNotFoundOutcomeIs404() throws Exception {
    start(5000, 4, q -> CompletableFuture.completedFuture(
        RouteOutcome.worldNotFound(q.world(), "world not found: " + q.world())));
    HttpResponse<String> r = get("/route?from=1,2&to=3,4&world=foo:bar", null);
    assertEquals(404, r.statusCode());
    assertEquals("world_not_found", body(r).get("status").getAsString());
  }

  @Test
  void unknownPathAndMethodAre404Json() throws Exception {
    start(5000, 4, okRoutes());
    HttpResponse<String> r = get("/nope", null);
    assertEquals(404, r.statusCode());
    assertJsonHeaders(r);
    assertTrue(body(r).has("error"));

    HttpResponse<String> post = client.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/route?from=1,2&to=3,4"))
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    assertEquals(404, post.statusCode());
    assertTrue(body(post).has("error"));
  }

  @Test
  void timeoutIs503() throws Exception {
    start(200, 4, q -> {
      CompletableFuture<RouteOutcome> never = new CompletableFuture<>();
      pending.add(never);
      return never;
    });
    long t0 = System.nanoTime();
    HttpResponse<String> r = get("/route?from=1,2&to=3,4", null);
    assertEquals(503, r.statusCode());
    JsonObject o = body(r);
    assertEquals("not_ready", o.get("status").getAsString());
    assertTrue(o.get("error").getAsString().contains("timed out"), o.toString());
    assertTrue(Duration.ofNanos(System.nanoTime() - t0).toMillis() >= 150);
    assertFalse(pending.get(0).isDone(), "producer future is not failed by the timeout");
  }

  @Test
  void concurrencyExhaustedIs503AndPermitReleased() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    start(10_000, 1, q -> {
      if (q.fromX() == 1) {
        CompletableFuture<RouteOutcome> hold = new CompletableFuture<>();
        pending.add(hold);
        entered.countDown();
        return hold;
      }
      return okRoutes().route(q);
    });
    CompletableFuture<HttpResponse<String>> first = client.sendAsync(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/route?from=1,0&to=3,4")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertTrue(entered.await(5, TimeUnit.SECONDS));

    HttpResponse<String> busy = get("/route?from=2,0&to=3,4", null);
    assertEquals(503, busy.statusCode());
    JsonObject o = body(busy);
    assertEquals("not_ready", o.get("status").getAsString());
    assertEquals("server busy", o.get("error").getAsString());

    GridPos p = new GridPos(1, 64, 0);
    pending.get(0).complete(RouteOutcome.ok("minecraft:overworld", p, p, List.of(p), 1));
    assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());

    HttpResponse<String> after = get("/route?from=2,0&to=3,4", null);
    assertEquals(200, after.statusCode(), "permit released after completion");
  }

  @Test
  void corsHeaderOnlyForAllowedOrigin() throws Exception {
    start(5000, 4, okRoutes());
    HttpResponse<String> allowed = get("/route?from=1,2&to=3,4", ALLOWED);
    assertEquals(200, allowed.statusCode());
    assertEquals(ALLOWED, allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null));

    HttpResponse<String> other = get("/route?from=1,2&to=3,4", "http://evil.example");
    assertEquals(200, other.statusCode());
    assertTrue(other.headers().firstValue("Access-Control-Allow-Origin").isEmpty());

    HttpResponse<String> none = get("/health", null);
    assertTrue(none.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
  }

  @Test
  void preflightAllowsGetOnlyForAllowedOrigin() throws Exception {
    start(5000, 4, okRoutes());
    HttpResponse<String> ok = client.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/route"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", ALLOWED).header("Access-Control-Request-Method", "GET").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(204, ok.statusCode());
    assertEquals("GET", ok.headers().firstValue("Access-Control-Allow-Methods").orElse(null));

    HttpResponse<String> post = client.send(HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + server.port() + "/route"))
        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
        .header("Origin", ALLOWED).header("Access-Control-Request-Method", "POST").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(404, post.statusCode());
  }
}
