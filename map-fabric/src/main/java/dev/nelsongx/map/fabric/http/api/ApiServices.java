package dev.nelsongx.map.fabric.http.api;

import dev.nelsongx.map.core.store.FeatureStore;
import dev.nelsongx.map.fabric.auth.PermissionChecker;
import dev.nelsongx.map.fabric.auth.SessionStore;
import dev.nelsongx.map.fabric.auth.TokenStore;
import dev.nelsongx.map.fabric.world.WorldDirectory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Everything the HTTP API needs, as Minecraft-free interfaces, so handlers can be tested with
 * in-memory stores and fakes.
 *
 * @param executor worker executor for SQLite I/O, network builds and routing
 * @param tokens login tokens
 * @param sessions session store supplier (null until opened)
 * @param features feature store supplier (null until opened)
 * @param permissions permission checker (may hop to the server thread internally)
 * @param worlds world directory
 * @param tilesDir squaremap tiles directory supplier (null when squaremap is absent)
 * @param webLoader class loader holding the bundled web export
 * @param webPrefix resource prefix of the web export, e.g. {@code web/}
 * @param clock clock for the permission cache
 */
// THREADING: immutable holder; every component is safe to call from Jetty request threads.
public record ApiServices(
    Executor executor,
    TokenStore tokens,
    Supplier<SessionStore> sessions,
    Supplier<FeatureStore> features,
    PermissionChecker permissions,
    WorldDirectory worlds,
    Supplier<Path> tilesDir,
    ClassLoader webLoader,
    String webPrefix,
    Clock clock) {

  /** Validates. */
  public ApiServices {
    Objects.requireNonNull(executor, "executor");
    Objects.requireNonNull(tokens, "tokens");
    Objects.requireNonNull(sessions, "sessions");
    Objects.requireNonNull(features, "features");
    Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(worlds, "worlds");
    Objects.requireNonNull(tilesDir, "tilesDir");
    Objects.requireNonNull(webLoader, "webLoader");
    Objects.requireNonNull(webPrefix, "webPrefix");
    Objects.requireNonNull(clock, "clock");
  }

  /** Default classpath prefix of the bundled web export (PLAN task 10). */
  public static final String WEB_PREFIX = "web/";

  /** @return services with nothing ready (health check only; everything else 503/404) */
  public static ApiServices unavailable() {
    Clock clock = Clock.systemUTC();
    return new ApiServices(Runnable::run, new TokenStore(clock), () -> null, () -> null,
        actor -> java.util.concurrent.CompletableFuture.completedFuture(false), List::of,
        () -> null, ApiServices.class.getClassLoader(), WEB_PREFIX, clock);
  }
}
