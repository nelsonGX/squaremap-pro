package dev.nelsongx.map.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.route.Speeds;
import dev.nelsongx.map.core.store.FeatureStore;
import dev.nelsongx.map.fabric.auth.SessionStore;
import dev.nelsongx.map.fabric.auth.TokenStore;
import dev.nelsongx.map.fabric.http.api.ApiServices;
import dev.nelsongx.map.fabric.http.api.FeatureChangeListener;
import dev.nelsongx.map.fabric.player.PlayerDirectory;
import dev.nelsongx.map.fabric.player.PlayerPosition;
import dev.nelsongx.map.fabric.player.PlayerSnapshot;
import dev.nelsongx.map.fabric.world.WorldDirectory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiServerTest {

  private static final String OVERWORLD = "minecraft:overworld";
  private static final String CUSTOM = "my_mod:deep dark";
  private static final Actor STEVE = new Actor(UUID.fromString("00000000-0000-0000-0000-00000000000a"),
      "Steve");
  private static final Actor ALEX = new Actor(UUID.fromString("00000000-0000-0000-0000-00000000000b"),
      "Alex");

  @TempDir
  Path tmp;

  private final HttpClient client = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5)).build();
  private final Map<UUID, Boolean> allowed = new ConcurrentHashMap<>();
  private ExecutorService executor;
  private FeatureStore features;
  private SessionStore sessions;
  private TokenStore tokens;
  private MapHttpServer server;
  private Path tiles;
  private final java.util.concurrent.atomic.AtomicReference<PlayerSnapshot> snapshot =
      new java.util.concurrent.atomic.AtomicReference<>(PlayerSnapshot.EMPTY);
  private final PlayerDirectory players = snapshot::get;
  private final java.util.List<String> changeLog = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final FeatureChangeListener changes = new FeatureChangeListener() {
    @Override
    public void onUpsert(String worldId, dev.nelsongx.map.core.feature.Feature feature) {
      changeLog.add("upsert " + worldId + " " + feature.id() + " r" + feature.revision());
    }

    @Override
    public void onDelete(String worldId, dev.nelsongx.map.core.feature.Feature deleted) {
      changeLog.add("delete " + worldId + " " + deleted.id());
    }
  };

  @BeforeEach
  void setUp() throws Exception {
    executor = Executors.newFixedThreadPool(4);
    Clock clock = Clock.systemUTC();
    features = FeatureStore.openInMemory(clock);
    sessions = SessionStore.openInMemory(Duration.ofHours(168), clock);
    tokens = new TokenStore(clock);
    allowed.put(STEVE.uuid(), true);
    allowed.put(ALEX.uuid(), false);
    tiles = tmp.resolve("web/tiles");
    Files.createDirectories(tiles.resolve("minecraft_overworld/0"));
    Files.write(tiles.resolve("minecraft_overworld/0/0_0.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    Files.writeString(tiles.resolve("minecraft_overworld/settings.json"), "{\"zoom\":{}}");
    Files.writeString(tmp.resolve("web/secret.txt"), "SECRET");

    WorldDirectory worlds = () -> List.of(new WorldDirectory.World(OVERWORLD, "minecraft_overworld"),
        new WorldDirectory.World(CUSTOM, "my_mod_deep dark"));
    ApiServices services = new ApiServices(executor, tokens, () -> sessions, () -> features,
        actor -> CompletableFuture.completedFuture(allowed.getOrDefault(actor.uuid(), false)),
        worlds, players, () -> tiles, ApiServerTest.class.getClassLoader(), "testweb/", clock, changes);
    HttpConfig config = new HttpConfig(true, "127.0.0.1", 0, List.of(), 5000, 16, "", 168, true,
        Speeds.defaults(), 100, false);
    server = new MapHttpServer(config, services);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop();
    executor.shutdownNow();
    features.close();
    sessions.close();
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private HttpResponse<String> send(String method, String path, String body, String cookie,
      boolean csrf) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
    if (cookie != null) {
      b.header("Cookie", "smp_session=" + cookie);
    }
    if (csrf) {
      b.header("X-Requested-With", "squaremap-pro");
    }
    if (body != null) {
      b.header("Content-Type", "application/json");
    }
    b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
        : HttpRequest.BodyPublishers.ofString(body));
    return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send("GET", path, null, null, false);
  }

  private static JsonObject obj(HttpResponse<String> r) {
    return JsonParser.parseString(r.body()).getAsJsonObject();
  }

  /** Logs in through the redeem endpoint and returns the session cookie value. */
  private String login(Actor actor) throws Exception {
    HttpResponse<String> r = get("/api/auth/redeem?token=" + tokens.issue(actor));
    assertEquals(302, r.statusCode(), r.body());
    assertEquals("/?edit=1", r.headers().firstValue("Location").orElse(null));
    String setCookie = r.headers().firstValue("Set-Cookie").orElseThrow();
    return setCookie.substring("smp_session=".length(), setCookie.indexOf(';'));
  }

  private static String worldPath(String world) {
    return "/api/worlds/" + java.net.URLEncoder.encode(world, java.nio.charset.StandardCharsets.UTF_8)
        .replace("%3A", ":").replace("+", "%20") + "/features";
  }

  private static final String ROAD = "{\"type\":\"road\",\"name\":\"Main Street\","
      + "\"geometry\":[{\"x\":0,\"z\":0},{\"x\":1000,\"z\":0}],\"props\":{\"roadClass\":\"highway\"}}";

  private JsonObject create(String cookie, String world, String body) throws Exception {
    HttpResponse<String> r = send("POST", worldPath(world), body, cookie, true);
    assertEquals(201, r.statusCode(), r.body());
    return obj(r);
  }

  // ---- auth ------------------------------------------------------------------------------------

  @Test
  void redeemSetsSecureHttpOnlyLaxCookie() throws Exception {
    HttpResponse<String> r = get("/api/auth/redeem?token=" + tokens.issue(STEVE));
    assertEquals(302, r.statusCode());
    assertEquals("/?edit=1", r.headers().firstValue("Location").orElse(null));
    String c = r.headers().firstValue("Set-Cookie").orElseThrow();
    assertTrue(c.matches("smp_session=[A-Za-z0-9_-]{43}; Path=/; Max-Age=604800; HttpOnly; "
        + "SameSite=Lax; Secure"), c);
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void redeemExpiredOrReusedToken() throws Exception {
    String token = tokens.issue(STEVE);
    assertEquals(302, get("/api/auth/redeem?token=" + token).statusCode());
    HttpResponse<String> again = get("/api/auth/redeem?token=" + token);
    assertEquals(302, again.statusCode());
    assertEquals("/?authError=expired", again.headers().firstValue("Location").orElse(null));
    assertTrue(again.headers().firstValue("Set-Cookie").isEmpty());
    assertEquals("/?authError=expired",
        get("/api/auth/redeem").headers().firstValue("Location").orElse(null));
  }

  @Test
  void redeemWithoutPermission() throws Exception {
    HttpResponse<String> r = get("/api/auth/redeem?token=" + tokens.issue(ALEX));
    assertEquals(302, r.statusCode());
    assertEquals("/?authError=forbidden", r.headers().firstValue("Location").orElse(null));
    assertTrue(r.headers().firstValue("Set-Cookie").isEmpty());
  }

  @Test
  void meAndLogout() throws Exception {
    assertEquals(false, obj(get("/api/auth/me")).get("loggedIn").getAsBoolean());
    String cookie = login(STEVE);
    JsonObject me = obj(send("GET", "/api/auth/me", null, cookie, false));
    assertTrue(me.get("loggedIn").getAsBoolean());
    assertEquals(STEVE.uuid().toString(), me.get("uuid").getAsString());
    assertEquals("Steve", me.get("name").getAsString());
    assertTrue(me.get("canEdit").getAsBoolean());

    assertEquals(403, send("POST", "/api/auth/logout", null, cookie, false).statusCode(),
        "logout needs the CSRF header");
    HttpResponse<String> out = send("POST", "/api/auth/logout", null, cookie, true);
    assertEquals(204, out.statusCode());
    assertTrue(out.headers().firstValue("Set-Cookie").orElse("").contains("Max-Age=0"));
    assertEquals(false,
        obj(send("GET", "/api/auth/me", null, cookie, false)).get("loggedIn").getAsBoolean());
    assertEquals(false, obj(send("GET", "/api/auth/me", null, "garbage", false))
        .get("loggedIn").getAsBoolean());
  }

  // ---- worlds & features -----------------------------------------------------------------------

  @Test
  void worldsList() throws Exception {
    HttpResponse<String> r = get("/api/worlds");
    assertEquals(200, r.statusCode());
    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
    assertEquals(2, arr.size());
    assertEquals(OVERWORLD, arr.get(0).getAsJsonObject().get("id").getAsString());
    assertEquals("minecraft_overworld", arr.get(0).getAsJsonObject().get("name").getAsString());
  }

  @Test
  void playersAreEmptyAndPublicWithoutAServer() throws Exception {
    HttpResponse<String> r = get("/api/players");
    assertEquals(200, r.statusCode());
    JsonObject o = obj(r);
    assertEquals(0, o.getAsJsonArray("players").size());
    assertEquals(0, o.get("max").getAsInt());
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
  }

  @Test
  void playersReportPositionsAndFilterByWorld() throws Exception {
    snapshot.set(new PlayerSnapshot(List.of(
        new PlayerPosition(STEVE.uuid().toString(), "Steve", OVERWORLD, 12, 64, -40, 90),
        new PlayerPosition(ALEX.uuid().toString(), "Alex", CUSTOM, 5, 70, 6, 271)), 20));

    JsonObject all = obj(get("/api/players"));
    assertEquals(2, all.getAsJsonArray("players").size());
    assertEquals(20, all.get("max").getAsInt());

    JsonObject one = obj(get("/api/players?world=" + OVERWORLD));
    assertEquals(1, one.getAsJsonArray("players").size());
    JsonObject steve = one.getAsJsonArray("players").get(0).getAsJsonObject();
    assertEquals("Steve", steve.get("name").getAsString());
    assertEquals(STEVE.uuid().toString(), steve.get("uuid").getAsString());
    assertEquals(OVERWORLD, steve.get("world").getAsString());
    assertEquals(12, steve.get("x").getAsInt());
    assertEquals(64, steve.get("y").getAsInt());
    assertEquals(-40, steve.get("z").getAsInt());
    assertEquals(90, steve.get("yaw").getAsInt());

    assertEquals(0, obj(get("/api/players?world=minecraft:nope")).getAsJsonArray("players").size());
  }

  @Test
  void featureCrudWithColonInWorld() throws Exception {
    HttpResponse<String> empty = get("/api/worlds/minecraft:overworld/features");
    assertEquals(200, empty.statusCode(), empty.body());
    assertEquals(1, obj(empty).get("schemaVersion").getAsInt());
    assertEquals(0, obj(empty).getAsJsonArray("features").size());

    String cookie = login(STEVE);
    JsonObject road = create(cookie, OVERWORLD, ROAD);
    String id = road.get("id").getAsString();
    assertEquals("road", road.get("type").getAsString());
    assertEquals(1, road.get("revision").getAsInt());
    assertEquals("Main Street", road.get("name").getAsString());
    assertEquals("highway", road.getAsJsonObject("props").get("roadClass").getAsString());
    assertEquals(1000, road.getAsJsonArray("geometry").get(1).getAsJsonObject().get("x").getAsInt());
    assertEquals("Steve", road.getAsJsonObject("createdBy").get("name").getAsString());
    assertEquals(STEVE.uuid().toString(), road.getAsJsonObject("updatedBy").get("uuid").getAsString());
    assertTrue(road.get("createdAt").getAsString().endsWith("Z"));

    JsonArray listed = obj(get("/api/worlds/minecraft:overworld/features")).getAsJsonArray("features");
    assertEquals(1, listed.size());
    assertEquals(road, listed.get(0));
    // percent-encoded colon is the same world; other world is separate
    assertEquals(1, obj(get("/api/worlds/minecraft%3Aoverworld/features")).getAsJsonArray("features").size());
    assertEquals(0, obj(get(worldPath(CUSTOM))).getAsJsonArray("features").size());

    String update = ROAD.replace("Main Street", "High Street").replace("{\"type\"", "{\"revision\":1,\"type\"");
    HttpResponse<String> put = send("PUT", "/api/worlds/minecraft:overworld/features/" + id, update,
        cookie, true);
    assertEquals(200, put.statusCode(), put.body());
    assertEquals(2, obj(put).get("revision").getAsInt());
    assertEquals("High Street", obj(put).get("name").getAsString());

    HttpResponse<String> stale = send("PUT", "/api/worlds/minecraft:overworld/features/" + id, update,
        cookie, true);
    assertEquals(409, stale.statusCode());
    assertEquals("conflict", obj(stale).get("error").getAsString());
    assertEquals(2, obj(stale).getAsJsonObject("current").get("revision").getAsInt());

    assertEquals(List.of("upsert minecraft:overworld " + id + " r1",
        "upsert minecraft:overworld " + id + " r2"), changeLog, "listener sees successful writes only");
    assertEquals(409, send("DELETE", "/api/worlds/minecraft:overworld/features/" + id + "?revision=1",
        null, cookie, true).statusCode());
    assertEquals(204, send("DELETE", "/api/worlds/minecraft:overworld/features/" + id + "?revision=2",
        null, cookie, true).statusCode());
    assertEquals("delete minecraft:overworld " + id, changeLog.get(changeLog.size() - 1));
    assertEquals(3, changeLog.size());
    assertEquals(404, send("DELETE", "/api/worlds/minecraft:overworld/features/" + id + "?revision=3",
        null, cookie, true).statusCode());
    HttpResponse<String> missing = send("PUT", "/api/worlds/minecraft:overworld/features/f_nope",
        update, cookie, true);
    assertEquals(404, missing.statusCode());
    assertEquals("not_found", obj(missing).get("error").getAsString());
  }

  @Test
  void writeAuthErrors() throws Exception {
    String path = worldPath(OVERWORLD);
    HttpResponse<String> noSession = send("POST", path, ROAD, null, true);
    assertEquals(401, noSession.statusCode());
    assertEquals("unauthorized", obj(noSession).get("error").getAsString());
    assertEquals(401, send("POST", path, ROAD, "x".repeat(43), true).statusCode());

    String steve = login(STEVE);
    HttpResponse<String> noHeader = send("POST", path, ROAD, steve, false);
    assertEquals(403, noHeader.statusCode());
    assertEquals("forbidden", obj(noHeader).get("error").getAsString());

    // Permission revoked after login: the write re-checks.
    String alexSession = sessions.create(ALEX).id();
    HttpResponse<String> noPerm = send("POST", path, ROAD, alexSession, true);
    assertEquals(403, noPerm.statusCode());
    assertEquals("forbidden", obj(noPerm).get("error").getAsString());

    HttpResponse<String> unknownWorld = send("POST", worldPath("minecraft:nope"), ROAD, steve, true);
    assertEquals(404, unknownWorld.statusCode());
    assertEquals(404, get(worldPath("minecraft:nope")).statusCode());
  }

  @Test
  void validationErrors() throws Exception {
    String cookie = login(STEVE);
    String path = worldPath(OVERWORLD);

    HttpResponse<String> malformed = send("POST", path, "{not json", cookie, true);
    assertEquals(400, malformed.statusCode());
    JsonObject m = obj(malformed);
    assertEquals("validation", m.get("error").getAsString());
    assertEquals("body", m.getAsJsonArray("details").get(0).getAsJsonObject().get("field").getAsString());

    HttpResponse<String> badType = send("POST", path, "{\"type\":\"castle\",\"name\":\"x\"}", cookie, true);
    assertEquals(400, badType.statusCode());
    assertEquals("type", obj(badType).getAsJsonArray("details").get(0).getAsJsonObject().get("field").getAsString());

    HttpResponse<String> shortRoad = send("POST", path, "{\"type\":\"road\",\"name\":\"R\","
        + "\"geometry\":[{\"x\":0,\"z\":0}],\"props\":{\"roadClass\":\"main\"}}", cookie, true);
    assertEquals(400, shortRoad.statusCode());
    assertTrue(shortRoad.body().contains("\"field\":\"geometry\""), shortRoad.body());

    HttpResponse<String> fractional = send("POST", path, "{\"type\":\"road\",\"name\":\"R\","
        + "\"geometry\":[{\"x\":0.5,\"z\":0},{\"x\":3,\"z\":0}],\"props\":{\"roadClass\":\"main\"}}",
        cookie, true);
    assertEquals(400, fractional.statusCode());

    HttpResponse<String> badRevision = send("DELETE",
        "/api/worlds/minecraft:overworld/features/f_x?revision=abc", null, cookie, true);
    assertEquals(400, badRevision.statusCode());
    assertTrue(badRevision.body().contains("\"field\":\"revision\""));

    HttpResponse<String> tooBig = send("POST", path, "{\"name\":\"" + "a".repeat(1_100_000) + "\"}",
        cookie, true);
    assertEquals(413, tooBig.statusCode());
  }

  @Test
  void deletingRailwayWithStationsIs422() throws Exception {
    String cookie = login(STEVE);
    JsonObject rail = create(cookie, OVERWORLD, "{\"type\":\"railway\",\"name\":\"Red Line\","
        + "\"geometry\":[{\"x\":0,\"z\":0},{\"x\":100,\"z\":0}],\"props\":{\"colour\":\"#ff0000\"}}");
    String railId = rail.get("id").getAsString();
    JsonObject station = create(cookie, OVERWORLD, "{\"type\":\"station\",\"name\":\"Central\","
        + "\"geometry\":[{\"x\":100,\"z\":0}],\"props\":{\"railwayId\":\"" + railId + "\"}}");
    assertEquals(railId, station.getAsJsonObject("props").get("railwayId").getAsString());

    HttpResponse<String> r = send("DELETE",
        "/api/worlds/minecraft:overworld/features/" + railId + "?revision=1", null, cookie, true);
    assertEquals(422, r.statusCode());
    assertEquals("railway_has_stations", obj(r).get("error").getAsString());
    assertTrue(obj(r).get("message").getAsString().contains("Central"));
  }

  // ---- route -----------------------------------------------------------------------------------

  @Test
  void routeStatusesAndNetworkInvalidation() throws Exception {
    HttpResponse<String> noPath = get("/api/route?world=minecraft:overworld&from=0,0&to=1000,0");
    assertEquals(200, noPath.statusCode(), noPath.body());
    JsonObject np = obj(noPath);
    assertEquals(2, np.get("schemaVersion").getAsInt());
    assertEquals("no_path", np.get("status").getAsString());
    assertEquals(0, np.getAsJsonArray("legs").size());
    assertEquals(0, np.get("distance").getAsDouble());

    create(login(STEVE), OVERWORLD, ROAD); // invalidates the cached (empty) network

    HttpResponse<String> ok = get("/api/route?world=minecraft:overworld&from=0,0&to=1000,0&modes=road,walk");
    assertEquals(200, ok.statusCode(), ok.body());
    JsonObject o = obj(ok);
    assertEquals("ok", o.get("status").getAsString());
    assertTrue(o.get("error").isJsonNull());
    assertEquals(OVERWORLD, o.get("world").getAsString());
    assertEquals(0, o.getAsJsonObject("from").get("x").getAsInt());
    JsonArray legs = o.getAsJsonArray("legs");
    JsonObject roadLeg = null;
    for (JsonElement l : legs) {
      if (l.getAsJsonObject().get("mode").getAsString().equals("road")) {
        roadLeg = l.getAsJsonObject();
      }
    }
    assertTrue(roadLeg != null, ok.body());
    assertEquals("Main Street", roadLeg.get("name").getAsString());
    assertTrue(roadLeg.get("fromStation").isJsonNull());
    assertEquals(1000.0, o.get("distance").getAsDouble(), 0.05);
    assertNotEquals(0, o.get("duration").getAsDouble());

    // road disabled → no path again (walk-only limited to 100 blocks by config)
    assertEquals("no_path", obj(get("/api/route?world=minecraft:overworld&from=0,0&to=1000,0&modes=walk"))
        .get("status").getAsString());
  }

  @Test
  void routeInvalidAndUnknownWorld() throws Exception {
    for (String q : List.of("from=0,0&to=1,1", "world=minecraft:overworld&from=0;0&to=1,1",
        "world=minecraft:overworld&from=0,0&to=1.5,1", "world=minecraft:overworld&from=0,0",
        "world=minecraft:overworld&from=0,0&to=1,1&modes=road,boat",
        "world=minecraft:overworld&from=99999999999,0&to=1,1")) {
      HttpResponse<String> r = get("/api/route?" + q);
      assertEquals(400, r.statusCode(), q);
      assertEquals("invalid_request", obj(r).get("status").getAsString(), q);
      assertFalse(obj(r).get("error").isJsonNull());
    }
    HttpResponse<String> nf = get("/api/route?world=minecraft:nope&from=0,0&to=1,1");
    assertEquals(404, nf.statusCode());
    assertEquals("world_not_found", obj(nf).get("status").getAsString());
    assertEquals(1, obj(nf).getAsJsonObject("to").get("x").getAsInt());
  }

  // ---- static & tiles --------------------------------------------------------------------------

  @Test
  void tilesServedAndTraversalBlocked() throws Exception {
    HttpResponse<String> png = get("/tiles/minecraft_overworld/0/0_0.png");
    assertEquals(200, png.statusCode());
    assertEquals("image/png", png.headers().firstValue("Content-Type").orElse(""));
    HttpResponse<String> settings = get("/tiles/minecraft_overworld/settings.json");
    assertEquals(200, settings.statusCode());
    assertTrue(settings.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    assertEquals("no-cache", settings.headers().firstValue("Cache-Control").orElse(""));

    for (String p : List.of("/tiles/../secret.txt", "/tiles/%2e%2e/secret.txt",
        "/tiles/minecraft_overworld/..%2f..%2fsecret.txt", "/tiles/..%5csecret.txt",
        "/tiles/minecraft_overworld/%2e%2e/%2e%2e/secret.txt", "/tiles/minecraft_overworld")) {
      HttpResponse<String> r = get(p);
      assertNotEquals(200, r.statusCode(), p);
      assertFalse(r.body().contains("SECRET"), p);
    }
  }

  @Test
  void staticSiteAndFallbacks() throws Exception {
    HttpResponse<String> index = get("/");
    assertEquals(200, index.statusCode());
    assertTrue(index.body().contains("INDEX"));
    assertTrue(index.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));

    HttpResponse<String> js = get("/_next/static/chunks/app.js");
    assertEquals(200, js.statusCode());
    assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
    assertTrue(js.headers().firstValue("Cache-Control").orElse("").contains("immutable"));

    assertEquals("ABOUT", get("/about").body());
    assertEquals("NESTED", get("/about/").body());

    HttpResponse<String> missing = get("/nope/page");
    assertEquals(404, missing.statusCode());
    assertTrue(missing.body().contains("CUSTOM404"));

    HttpResponse<String> api404 = get("/api/nope");
    assertEquals(404, api404.statusCode());
    assertEquals("not_found", obj(api404).get("error").getAsString());
    assertEquals(404, send("PATCH", "/api/worlds", "{}", null, true).statusCode());
  }

  @Test
  void slowPermissionCheckTimesOutWith503() throws Exception {
    MapHttpServer slow = new MapHttpServer(
        new HttpConfig(true, "127.0.0.1", 0, List.of(), 300, 4),
        new ApiServices(executor, tokens, () -> sessions, () -> features,
            a -> new CompletableFuture<>(), // never completes
            () -> List.of(new WorldDirectory.World(OVERWORLD, "o")), PlayerDirectory.EMPTY, () -> null,
            ApiServerTest.class.getClassLoader(), "testweb/", Clock.systemUTC(), FeatureChangeListener.NONE));
    slow.start();
    try {
      String cookie = sessions.create(STEVE).id();
      HttpResponse<String> r = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + slow.port() + worldPath(OVERWORLD)))
          .header("Cookie", "smp_session=" + cookie)
          .header("X-Requested-With", "squaremap-pro")
          .POST(HttpRequest.BodyPublishers.ofString(ROAD)).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(503, r.statusCode(), r.body());
      assertEquals("timeout", obj(r).get("error").getAsString());
    } finally {
      slow.stop();
    }
  }

  @Test
  void missingBundleIsReported() throws Exception {
    MapHttpServer bare = new MapHttpServer(new HttpConfig(true, "127.0.0.1", 0, List.of(), 5000, 4),
        new ApiServices(executor, tokens, () -> null, () -> null,
            a -> CompletableFuture.completedFuture(false), List::of, PlayerDirectory.EMPTY,
            () -> null,
            ApiServerTest.class.getClassLoader(), "no-such-bundle/", Clock.systemUTC(),
            FeatureChangeListener.NONE));
    bare.start();
    try {
      HttpResponse<String> r = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + bare.port() + "/")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(200, r.statusCode());
      assertTrue(r.body().contains("web bundle missing"));
      HttpResponse<String> notReady = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + bare.port() + "/api/tiles-none")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404, notReady.statusCode());
      HttpResponse<String> tilesAbsent = client.send(HttpRequest.newBuilder(
          URI.create("http://127.0.0.1:" + bare.port() + "/tiles/minecraft_overworld/0/0_0.png")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404, tilesAbsent.statusCode());
    } finally {
      bare.stop();
    }
  }
}
