package dev.nelsongx.map.fabric;

import dev.nelsongx.map.fabric.http.HttpLifecycle;
import dev.nelsongx.map.fabric.squaremap.MapLayer;
import dev.nelsongx.map.fabric.squaremap.MapLayers;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entrypoint: creates the shared {@link MapLayer} and {@link MapExecutor}, and starts/stops the HTTP
 * server with the Minecraft server.
 */
// THREADING: onInitialize runs once on the main/loader thread and only registers callbacks. The
// lifecycle callbacks (SERVER_STARTED, SERVER_STOPPING, SERVER_STOPPED) are invoked by Fabric on the
// server thread and never block; SERVER_STARTING only stores the server reference. `server`, `executor`
// and `mapLayer` are volatile and read from any thread.
public final class MapFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private static volatile MinecraftServer server;
  private static volatile MapLayer mapLayer;
  private static volatile MapExecutor executor;

  /** @return the shared map layer (null before mod init). ANY THREAD. */
  public static MapLayer mapLayer() {
    return mapLayer;
  }

  /** @return the worker pool of the running server (null when no server runs). ANY THREAD. */
  public static MapExecutor executor() {
    return executor;
  }

  @Override
  public void onInitialize() {
    MapLayer layer = MapLayers.create(() -> server);
    mapLayer = layer;
    // Config I/O and Javalin start/stop run on the HTTP lifecycle's own daemon thread.
    HttpLifecycle http = new HttpLifecycle(() -> FabricLoader.getInstance().getConfigDir());

    ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
    ServerLifecycleEvents.SERVER_STARTED.register(s -> {
      MapExecutor old = executor;
      if (old != null) {
        old.shutdown();
      }
      executor = new MapExecutor();
      http.onServerStarted();
      LOGGER.info("squaremap-pro services started");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
      http.onServerStopping();
      try {
        layer.onServerStopping();
      } catch (RuntimeException | LinkageError e) {
        LOGGER.warn("failed to release squaremap layers", e);
      }
      MapExecutor ex = executor;
      executor = null;
      if (ex != null) {
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
