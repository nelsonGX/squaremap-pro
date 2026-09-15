package dev.nelsongx.map.fabric;

import dev.nelsongx.map.fabric.auth.AuthServices;
import dev.nelsongx.map.fabric.auth.FabricPermissionChecker;
import dev.nelsongx.map.fabric.auth.TokenStore;
import dev.nelsongx.map.fabric.command.MapEditCommand;
import dev.nelsongx.map.fabric.http.HttpLifecycle;
import dev.nelsongx.map.fabric.squaremap.MapLayer;
import dev.nelsongx.map.fabric.squaremap.MapLayers;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.RejectedExecutionException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entrypoint: creates the shared {@link MapLayer}, and per running server the {@link MapExecutor}
 * and {@link AuthServices}; starts/stops the HTTP server; registers {@code /mapedit}.
 */
// THREADING: onInitialize runs once on the main/loader thread and only registers callbacks. The
// lifecycle callbacks (SERVER_STARTED, SERVER_STOPPING, SERVER_STOPPED) are invoked by Fabric on the
// server thread and never block; SERVER_STARTING only stores the server reference. The session store
// is opened (after the HTTP lifecycle thread has loaded the config) and closed on the MapExecutor.
// `server`, `executor`, `auth` and `mapLayer` are volatile and read from any thread.
public final class MapFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private static volatile MinecraftServer server;
  private static volatile MapLayer mapLayer;
  private static volatile MapExecutor executor;
  private static volatile AuthServices auth;
  private static volatile HttpLifecycle http;

  /** @return the shared map layer (null before mod init). ANY THREAD. */
  public static MapLayer mapLayer() {
    return mapLayer;
  }

  /** @return the worker pool of the running server (null when no server runs). ANY THREAD. */
  public static MapExecutor executor() {
    return executor;
  }

  /** @return auth services of the running server (null when no server runs). ANY THREAD. */
  public static AuthServices auth() {
    return auth;
  }

  /** @return the HTTP lifecycle (null before mod init). ANY THREAD. */
  public static HttpLifecycle http() {
    return http;
  }

  @Override
  public void onInitialize() {
    MapLayer layer = MapLayers.create(() -> server);
    mapLayer = layer;
    // Config I/O and Javalin start/stop run on the HTTP lifecycle's own daemon thread.
    HttpLifecycle lifecycle = new HttpLifecycle(() -> FabricLoader.getInstance().getConfigDir());
    http = lifecycle;

    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
        MapEditCommand.register(dispatcher, () -> auth, lifecycle::running));

    ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
    ServerLifecycleEvents.SERVER_STARTED.register(s -> {
      MapExecutor old = executor;
      if (old != null) {
        old.shutdown();
      }
      MapExecutor ex = new MapExecutor();
      executor = ex;
      Clock clock = Clock.systemUTC();
      AuthServices services = new AuthServices(new TokenStore(clock), new FabricPermissionChecker(s),
          clock);
      auth = services;
      // MinecraftServer.getWorldPath(LevelResource) only resolves a path (no I/O).
      Path sessionsDb = s.getWorldPath(LevelResource.ROOT).resolve(AuthServices.SESSIONS_DB);
      lifecycle.onServerStarted(config -> {
        // Lifecycle thread: hand the blocking SQLite open to the worker pool.
        try {
          ex.execute(() -> services.openSessions(sessionsDb, config.sessionTtl()));
        } catch (RejectedExecutionException e) {
          LOGGER.warn("squaremap-pro session store not opened (server stopping)");
        }
      });
      LOGGER.info("squaremap-pro services started");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
      lifecycle.onServerStopping();
      try {
        layer.onServerStopping();
      } catch (RuntimeException | LinkageError e) {
        LOGGER.warn("failed to release squaremap layers", e);
      }
      AuthServices services = auth;
      auth = null;
      MapExecutor ex = executor;
      executor = null;
      if (ex != null) {
        if (services != null) {
          try {
            ex.execute(services::close); // queued before shutdown, so it still runs
          } catch (RejectedExecutionException e) {
            LOGGER.warn("squaremap-pro session store close rejected", e);
          }
        }
        ex.shutdown(); // does not wait
      }
      LOGGER.info("squaremap-pro services stopped");
    });
    ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
      if (server == s) {
        server = null;
      }
    });
    LOGGER.info("squaremap-pro initialized");
  }
}
