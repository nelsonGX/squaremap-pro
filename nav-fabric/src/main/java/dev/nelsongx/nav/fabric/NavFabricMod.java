package dev.nelsongx.nav.fabric;

import dev.nelsongx.nav.fabric.command.NavCommand;
import dev.nelsongx.nav.fabric.graph.BlockChangeTracker;
import dev.nelsongx.nav.fabric.graph.NavBuildCommand;
import dev.nelsongx.nav.fabric.graph.NavGraphServices;
import dev.nelsongx.nav.fabric.http.RouteHttpLifecycle;
import dev.nelsongx.nav.fabric.route.RouteService;
import dev.nelsongx.nav.fabric.squaremap.NavMapLayer;
import dev.nelsongx.nav.fabric.squaremap.NavMapLayers;
import dev.nelsongx.nav.fabric.world.NavServices;
import dev.nelsongx.nav.fabric.world.SnapshotCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod entrypoint: wires server lifecycle and tick events to {@link NavServices}, registers
 * {@code /nav}, and creates the shared {@link RouteService} and {@link NavMapLayer}.
 */
// THREADING: onInitialize runs once on the main/loader thread and only registers callbacks. The
// lifecycle callbacks (SERVER_STARTED, SERVER_STOPPING, SERVER_STOPPED, END_SERVER_TICK,
// END_DATA_PACK_RELOAD) are invoked by Fabric on the server thread; SERVER_STARTING only stores the
// server reference. CommandRegistrationCallback fires wherever `new Commands(...)` runs
// (ReloadableServerResources, possibly a reload executor thread) and only adds nodes to the dispatcher
// it is given — no server/level/player access. ServerChunkEvents CHUNK_LOAD/CHUNK_UNLOAD fire on the
// server thread (BlockChangeTracker re-checks). `server` is volatile and read from any thread;
// routeService()/mapLayer() are set once during init and read from any thread.
public final class NavFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  private static volatile MinecraftServer server;
  private static volatile RouteService routeService;
  private static volatile NavMapLayer mapLayer;

  /** @return the shared route service (null before mod init). ANY THREAD. */
  public static RouteService routeService() {
    return routeService;
  }

  /** @return the shared map layer (null before mod init). ANY THREAD. */
  public static NavMapLayer mapLayer() {
    return mapLayer;
  }

  @Override
  public void onInitialize() {
    // Region graph per dimension (null when services are not running); RouteService only uses it when it
    // covers the whole query box (GraphUsePolicy).
    RouteService routes = new RouteService(NavServices::current, dimension -> {
      NavGraphServices graphs = NavGraphServices.current();
      return graphs == null ? null : graphs.graph(dimension);
    });
    NavMapLayer layer = NavMapLayers.create(() -> server);
    routeService = routes;
    mapLayer = layer;
    NavCommand navCommand = new NavCommand(routes, layer);
    NavBuildCommand navBuildCommand = new NavBuildCommand();
    // HTTP GET /route: config I/O and Javalin start/stop run on its own daemon lifecycle thread.
    RouteHttpLifecycle http = new RouteHttpLifecycle(() -> FabricLoader.getInstance().getConfigDir(), routes);

    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
      navCommand.register(dispatcher);
      navBuildCommand.register(dispatcher);
    });

    ServerLifecycleEvents.SERVER_STARTING.register(s -> server = s);
    ServerLifecycleEvents.SERVER_STARTED.register(s -> {
      NavServices nav = NavServices.start(s, SnapshotCache.Config.defaults());
      NavGraphServices.start(s, nav);
      http.onServerStarted();
      LOGGER.info("squaremap-pro nav services started");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
      http.onServerStopping();
      try {
        layer.onServerStopping();
      } catch (RuntimeException | LinkageError e) {
        LOGGER.warn("failed to release squaremap navigation layers", e);
      }
      // Queue final graph saves before the executor stops accepting tasks; never waits.
      NavGraphServices.stop(s);
      NavServices.stop(s);
      navCommand.clearInFlight();
      LOGGER.info("squaremap-pro nav services stopped");
    });
    ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
      if (server == s) {
        server = null;
      }
    });
    ServerTickEvents.END_SERVER_TICK.register(s -> {
      NavServices services = NavServices.current();
      if (services != null) {
        services.tick(s);
      }
      NavGraphServices graphs = NavGraphServices.current();
      if (graphs != null) {
        graphs.tick(s);
      }
    });
    ServerChunkEvents.CHUNK_LOAD.register(BlockChangeTracker::onChunkLoad);
    ServerChunkEvents.CHUNK_UNLOAD.register(BlockChangeTracker::onChunkUnload);
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, resourceManager, success) -> {
      NavServices services = NavServices.current();
      if (services != null && success) {
        services.onDataPackReload(s);
      }
    });
    LOGGER.info("squaremap-pro initialized");
  }
}
