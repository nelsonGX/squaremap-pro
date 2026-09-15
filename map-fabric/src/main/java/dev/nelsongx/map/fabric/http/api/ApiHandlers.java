package dev.nelsongx.map.fabric.http.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.ValidationError;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.core.route.Mode;
import dev.nelsongx.map.core.route.Router;
import dev.nelsongx.map.core.route.RouterOptions;
import dev.nelsongx.map.core.store.FeatureStore;
import dev.nelsongx.map.core.store.Result;
import dev.nelsongx.map.fabric.auth.CachedPermissionChecker;
import dev.nelsongx.map.fabric.auth.Session;
import dev.nelsongx.map.fabric.auth.SessionStore;
import dev.nelsongx.map.fabric.http.HttpConfig;
import dev.nelsongx.map.fabric.world.WorldDirectory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Request logic of the HTTP API (CLAUDE.md "Auth", "Features v1", "Route schema v2"). Handlers take
 * already-extracted request values and return a future {@link Reply}; {@code MapHttpServer} wires
 * them to Javalin. No Javalin, Minecraft or squaremap types.
 */
// THREADING: methods are called on Jetty request threads and do only in-memory work there
// (token redeem, JSON parsing, world lookup). SQLite access (sessions, features), network builds and
// routing run on ApiServices.executor(); permission checks go through PermissionChecker (which hops
// to the server thread itself). Returned futures complete on worker or permission threads.
public final class ApiHandlers {

  /** Session cookie name. */
  public static final String SESSION_COOKIE = "smp_session";
  /** CSRF header required on every state-changing request. */
  public static final String CSRF_HEADER = "X-Requested-With";
  /** Required CSRF header value. */
  public static final String CSRF_VALUE = "squaremap-pro";

  private static final Pattern REVISION = Pattern.compile("[1-9][0-9]{0,17}");

  private final ApiServices services;
  private final HttpConfig config;
  private final CachedPermissionChecker permissions;
  private final NetworkCache networks;

  /**
   * @param config HTTP config (cookie flags, session TTL, speeds)
   * @param services backends
   */
  public ApiHandlers(HttpConfig config, ApiServices services) {
    this.config = Objects.requireNonNull(config, "config");
    this.services = Objects.requireNonNull(services, "services");
    this.permissions = new CachedPermissionChecker(services.permissions(), services.clock(),
        CachedPermissionChecker.DEFAULT_TTL);
    this.networks = new NetworkCache(services.executor());
  }

  // ---- worlds ----------------------------------------------------------------------------------

  /** {@code GET /api/worlds}. */
  public Reply worlds() {
    JsonArray arr = new JsonArray();
    for (WorldDirectory.World w : services.worlds().worlds()) {
      JsonObject o = new JsonObject();
      o.addProperty("id", w.id());
      o.addProperty("name", w.name());
      arr.add(o);
    }
    return Reply.json(200, arr);
  }

  // ---- auth ------------------------------------------------------------------------------------

  /** {@code GET /api/auth/redeem?token=}. */
  public CompletableFuture<Reply> redeem(String token) {
    Optional<Actor> actor = services.tokens().redeem(token);
    if (actor.isEmpty()) {
      return done(noStore(Reply.redirect("/?authError=expired")));
    }
    SessionStore sessions = services.sessions().get();
    if (sessions == null) {
      return done(noStore(Reply.redirect("/?authError=unavailable")));
    }
    return permissions.canEditUncached(actor.get())
        .thenCompose(allowed -> {
          if (!allowed) {
            return done(Reply.redirect("/?authError=forbidden"));
          }
          return work(() -> sessions.create(actor.get()))
              .thenApply(session -> Reply.redirect("/?edit=1")
                  .withHeader("Set-Cookie", sessionCookie(session.id(), ttlSeconds())));
        })
        .exceptionally(e -> Reply.redirect("/?authError=unavailable"))
        .thenApply(ApiHandlers::noStore);
  }

  /** {@code GET /api/auth/me}. */
  public CompletableFuture<Reply> me(String cookie) {
    JsonObject loggedOut = new JsonObject();
    loggedOut.addProperty("loggedIn", false);
    if (cookie == null || cookie.isEmpty()) {
      return done(Reply.json(200, loggedOut));
    }
    SessionStore sessions = requireSessions();
    return work(() -> sessions.find(cookie)).thenCompose(found -> {
      if (found.isEmpty()) {
        return done(Reply.json(200, loggedOut).withHeader("Set-Cookie", clearCookie()));
      }
      Session s = found.get();
      return permissions.canEdit(s.actor())
          .exceptionally(e -> false)
          .thenApply(canEdit -> {
            JsonObject o = new JsonObject();
            o.addProperty("loggedIn", true);
            o.addProperty("uuid", s.actor().uuid().toString());
            o.addProperty("name", s.actor().name());
            o.addProperty("canEdit", canEdit);
            return Reply.json(200, o);
          });
    });
  }

  /** {@code POST /api/auth/logout}. */
  public CompletableFuture<Reply> logout(String csrf, String cookie) {
    if (!CSRF_VALUE.equals(csrf)) {
      return done(Reply.error(403, "forbidden"));
    }
    Reply ok = Reply.empty(204).withHeader("Set-Cookie", clearCookie());
    if (cookie == null || cookie.isEmpty()) {
      return done(ok);
    }
    SessionStore sessions = requireSessions();
    return work(() -> {
      sessions.delete(cookie);
      return ok;
    });
  }

  // ---- features --------------------------------------------------------------------------------

  /** {@code GET /api/worlds/{world}/features}. */
  public CompletableFuture<Reply> listFeatures(String world) {
    Optional<Reply> missing = worldMissing(world);
    if (missing.isPresent()) {
      return done(missing.get());
    }
    FeatureStore store = requireFeatures();
    return work(() -> {
      JsonArray arr = new JsonArray();
      for (Feature f : store.list(world)) {
        arr.add(FeatureJson.toJson(f));
      }
      JsonObject o = new JsonObject();
      o.addProperty("schemaVersion", 1);
      o.add("features", arr);
      return Reply.json(200, o);
    });
  }

  /** {@code POST /api/worlds/{world}/features}. */
  public CompletableFuture<Reply> createFeature(String world, String csrf, String cookie,
      String body) {
    return authorizedWrite(world, csrf, cookie, (store, actor) -> {
      FeatureJson.Input input = FeatureJson.parseInput(body, false);
      if (!input.errors().isEmpty()) {
        return validation(input.errors());
      }
      return storeReply(world, store.create(world, input.data(), actor), 201);
    });
  }

  /** {@code PUT /api/worlds/{world}/features/{id}}. */
  public CompletableFuture<Reply> updateFeature(String world, String id, String csrf,
      String cookie, String body) {
    return authorizedWrite(world, csrf, cookie, (store, actor) -> {
      FeatureJson.Input input = FeatureJson.parseInput(body, true);
      if (!input.errors().isEmpty()) {
        return validation(input.errors());
      }
      return storeReply(world, store.update(world, id, input.revision(), input.data(), actor), 200);
    });
  }

  /** {@code DELETE /api/worlds/{world}/features/{id}?revision=}. */
  public CompletableFuture<Reply> deleteFeature(String world, String id, String revision,
      String csrf, String cookie) {
    return authorizedWrite(world, csrf, cookie, (store, actor) -> {
      if (revision == null || !REVISION.matcher(revision).matches()) {
        return validation(List.of(
            new ValidationError("revision", "revision query parameter must be a positive integer")));
      }
      return storeReply(world, store.delete(world, id, Long.parseLong(revision), actor), 204);
    });
  }

  @FunctionalInterface
  private interface Write {
    Reply run(FeatureStore store, Actor actor);
  }

  /**
   * Common write pipeline: world exists → CSRF header (403) → session cookie present (401) → stores
   * ready (503) → [worker] session valid (401) → permission re-check, cached ≤ 30 s (403) → [worker]
   * body validation (400) and store operation.
   */
  private CompletableFuture<Reply> authorizedWrite(String world, String csrf, String cookie,
      Write write) {
    Optional<Reply> missing = worldMissing(world);
    if (missing.isPresent()) {
      return done(missing.get());
    }
    if (!CSRF_VALUE.equals(csrf)) {
      return done(Reply.error(403, "forbidden"));
    }
    if (cookie == null || cookie.isEmpty()) {
      return done(Reply.error(401, "unauthorized"));
    }
    SessionStore sessions = requireSessions();
    FeatureStore store = requireFeatures();
    return work(() -> sessions.find(cookie)).thenCompose(session -> {
      if (session.isEmpty()) {
        return done(Reply.error(401, "unauthorized").withHeader("Set-Cookie", clearCookie()));
      }
      Actor actor = session.get().actor();
      return permissions.canEdit(actor).thenCompose(allowed -> allowed
          ? work(() -> write.run(store, actor))
          : done(Reply.error(403, "forbidden")));
    });
  }

  private Reply storeReply(String world, Result result, int okStatus) {
    return switch (result) {
      case Result.Ok ok -> {
        networks.invalidate(world);
        yield okStatus == 204 ? Reply.empty(204) : Reply.json(okStatus, FeatureJson.toJson(ok.feature()));
      }
      case Result.Invalid invalid -> validation(invalid.errors());
      case Result.NotFound nf -> Reply.error(404, "not_found");
      case Result.Conflict conflict -> {
        JsonObject o = new JsonObject();
        o.addProperty("error", "conflict");
        o.add("current", FeatureJson.toJson(conflict.current()));
        yield Reply.json(409, o);
      }
      case Result.Rejected rejected -> {
        JsonObject o = new JsonObject();
        o.addProperty("error", "railway_has_stations");
        o.addProperty("message", rejected.reason());
        yield Reply.json(422, o);
      }
    };
  }

  private static Reply validation(List<ValidationError> errors) {
    return Reply.json(400, FeatureJson.validationError(errors));
  }

  // ---- route -----------------------------------------------------------------------------------

  /** {@code GET /api/route?world=&from=&to=[&modes=]}. */
  public CompletableFuture<Reply> route(String world, String fromRaw, String toRaw,
      String modesRaw) {
    Vertex from = RouteJson.parseXz(fromRaw).orElse(null);
    Vertex to = RouteJson.parseXz(toRaw).orElse(null);
    String problem = null;
    if (world == null || world.isEmpty()) {
      problem = "world is required";
    } else if (from == null) {
      problem = "from must be x,z with integer block coordinates";
    } else if (to == null) {
      problem = "to must be x,z with integer block coordinates";
    }
    Optional<Set<Mode>> modes = RouteJson.parseModes(modesRaw);
    if (problem == null && modes.isEmpty()) {
      problem = "modes must be a comma-separated list of walk, road, rail";
    }
    if (problem != null) {
      return done(Reply.json(400, RouteJson.failure("invalid_request", world, from, to, problem)));
    }
    if (services.worlds().find(world).isEmpty()) {
      return done(Reply.json(404, RouteJson.failure("world_not_found", world, from, to,
          "unknown world " + world)));
    }
    FeatureStore store = requireFeatures();
    RouterOptions options = RouterOptions.defaults().withSpeeds(config.speeds())
        .withModes(modes.get()).withMaxDirectWalk(config.maxDirectWalk());
    return networks.get(store, world)
        .thenApplyAsync(network -> Reply.json(200,
            RouteJson.result(world, from, to, Router.route(network, from, to, options))),
            services.executor());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private Optional<Reply> worldMissing(String world) {
    if (world == null || services.worlds().find(world).isEmpty()) {
      return Optional.of(Reply.error(404, "world_not_found"));
    }
    return Optional.empty();
  }

  private SessionStore requireSessions() {
    SessionStore s = services.sessions().get();
    if (s == null) {
      throw new Reply.Exit(Reply.error(503, "not_ready"));
    }
    return s;
  }

  private FeatureStore requireFeatures() {
    FeatureStore s = services.features().get();
    if (s == null) {
      throw new Reply.Exit(Reply.error(503, "not_ready"));
    }
    return s;
  }

  private <T> CompletableFuture<T> work(Supplier<T> task) {
    try {
      return CompletableFuture.supplyAsync(task, services.executor());
    } catch (RejectedExecutionException e) {
      return CompletableFuture.failedFuture(e);
    }
  }

  private static CompletableFuture<Reply> done(Reply reply) {
    return CompletableFuture.completedFuture(reply);
  }

  private static Reply noStore(Reply r) {
    return r.withHeader("Referrer-Policy", "no-referrer");
  }

  private long ttlSeconds() {
    return config.sessionTtl().toSeconds();
  }

  /** @return the {@code Set-Cookie} value for a new session */
  String sessionCookie(String id, long maxAgeSeconds) {
    return SESSION_COOKIE + "=" + id + "; Path=/; Max-Age=" + maxAgeSeconds
        + "; HttpOnly; SameSite=Lax" + (config.cookieSecure() ? "; Secure" : "");
  }

  /** @return the {@code Set-Cookie} value that deletes the session cookie */
  String clearCookie() {
    return SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax"
        + (config.cookieSecure() ? "; Secure" : "");
  }
}
