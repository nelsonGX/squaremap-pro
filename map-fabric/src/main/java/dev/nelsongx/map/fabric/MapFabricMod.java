package dev.nelsongx.map.fabric;

import dev.nelsongx.map.core.store.FeatureStore;
import dev.nelsongx.map.fabric.auth.AuthServices;
import dev.nelsongx.map.fabric.http.api.ApiServices;
import dev.nelsongx.map.fabric.http.api.FeatureChangeListener;
import dev.nelsongx.map.fabric.squaremap.FeatureMirror;
import dev.nelsongx.map.fabric.player.LevelPlayerDirectory;
import dev.nelsongx.map.fabric.world.WorldDirectory;
import dev.nelsongx.map.fabric.world.LevelWorldDirectory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
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
// lifecycle and tick callbacks (SERVER_STARTED, SERVER_STOPPING, SERVER_STOPPED, END_SERVER_TICK) are
// invoked by Fabric on the server thread and never block; SERVER_STARTING only stores the server
// reference. The world list is snapshotted on the server thread. The session and feature stores are
// opened (after the HTTP lifecycle thread has loaded the config) and closed on the MapExecutor; HTTP
// handlers see only the Minecraft-free ApiServices built on the lifecycle thread. All static fields
// are volatile and read from any thread.
public final class MapFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private static volatile MinecraftServer server;
  private static volatile MapLayer mapLayer;
  private static volatile MapExecutor executor;
  private static volatile AuthServices auth;
  private static volatile HttpLifecycle http;
  private static volatile StoreHolder<FeatureStore> features;
  private static volatile LevelWorldDirectory worlds;
  private static volatile LevelPlayerDirectory players;

  /** Feature database location relative to the world save root. */
  public static final String FEATURES_DB = "data/squaremap-pro/map.sqlite";

  /** How often online player positions are re-snapshotted, in server ticks. */
  private static final int PLAYER_REFRESH_TICKS = 10;

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
    FeatureMirror mirror = new FeatureMirror(layer);
    layer.onWorldRegistered(mirror::renderAll);
    // Config I/O and Javalin start/stop run on the HTTP lifecycle's own daemon thread.
    HttpLifecycle lifecycle = new HttpLifecycle(() -> FabricLoader.getInstance().getConfigDir());
    http = lifecycle;

    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
        MapEditCommand.register(dispatcher, () -> auth, lifecycle::running));

    ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
    // Levels added/removed at runtime (dimension mods): re-snapshot every 100 ticks on the server
    // thread (ServerTickEvents.EndTick#onEndTick(MinecraftServer)); publishes only on change.
    ServerTickEvents.END_SERVER_TICK.register(s -> {
      // Player positions drive a live layer on the web map: re-snapshot every 10 ticks (0.5 s).
      LevelPlayerDirectory playerDir = players;
      if (playerDir != null && s.getTickCount() % PLAYER_REFRESH_TICKS == 0) {
        playerDir.refresh(s);
      }
      LevelWorldDirectory dir = worlds;
      if (dir != null && s.getTickCount() % 100 == 0) {
        dir.refresh(s);
        try {
          layer.tick(); // re-registers squaremap layers lost to a squaremap reload
        } catch (RuntimeException | LinkageError e) {
          LOGGER.warn("squaremap layer check failed", e);
        }
      }
    });
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
      StoreHolder<FeatureStore> featureStore = new StoreHolder<>("feature store");
      features = featureStore;
      LevelWorldDirectory worldDir = new LevelWorldDirectory(layer::available);
      worldDir.refresh(s);
      worlds = worldDir;
      LevelPlayerDirectory playerDir = new LevelPlayerDirectory(layer::hiddenOnMap);
      playerDir.refresh(s);
      players = playerDir;
      // MinecraftServer.getWorldPath(LevelResource) only resolves a path (no I/O).
      Path worldRoot = s.getWorldPath(LevelResource.ROOT);
      Path sessionsDb = worldRoot.resolve(AuthServices.SESSIONS_DB);
      Path mapDb = worldRoot.resolve(FEATURES_DB);
      lifecycle.onServerStarted(config -> {
        // Lifecycle thread: hand the blocking SQLite opens to the worker pool.
        try {
          ex.execute(() -> services.openSessions(sessionsDb, config.sessionTtl()));
          ex.execute(() -> {
            featureStore.open(() -> FeatureStore.open(mapDb, clock));
            FeatureStore store = featureStore.get();
            if (config.squaremapMirror() && store != null) {
              // Full rebuild of every loaded world's squaremap markers (worker thread).
              for (WorldDirectory.World w : worldDir.worlds()) {
                try {
                  mirror.replaceAll(w.id(), store.list(w.id()));
                } catch (RuntimeException | LinkageError e) {
                  LOGGER.warn("squaremap mirror rebuild failed for {}", w.id(), e);
                }
              }
            }
          });
        } catch (RejectedExecutionException e) {
          LOGGER.warn("squaremap-pro stores not opened (server stopping)");
        }
        return new ApiServices(ex, services.tokens(), services::sessions, featureStore::get,
            services.permissions(), worldDir, playerDir, layer::tilesDir, MapFabricMod.class.getClassLoader(),
            ApiServices.WEB_PREFIX, clock,
            config.squaremapMirror() ? mirror : FeatureChangeListener.NONE);
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
      StoreHolder<FeatureStore> featureStore = features;
      features = null;
      LevelWorldDirectory worldDir = worlds;
      if (worldDir != null) {
        worldDir.clear();
      }
      LevelPlayerDirectory playerDir = players;
      players = null;
      if (playerDir != null) {
        playerDir.clear();
      }
      MapExecutor ex = executor;
      executor = null;
      if (ex != null) {
        try {
          // queued before shutdown, so they still run
          if (services != null) {
            ex.execute(services::close);
          }
          if (featureStore != null) {
            ex.execute(featureStore::close);
          }
        } catch (RejectedExecutionException e) {
          LOGGER.warn("squaremap-pro store close rejected", e);
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
