package dev.nelsongx.nav.fabric;

import dev.nelsongx.nav.fabric.world.NavServices;
import dev.nelsongx.nav.fabric.world.SnapshotCache;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Mod entrypoint: wires server lifecycle and tick events to {@link NavServices}. */
// THREADING: onInitialize runs once on the main/loader thread and only registers callbacks. All
// registered callbacks (SERVER_STARTED, SERVER_STOPPING, END_SERVER_TICK, END_DATA_PACK_RELOAD) are
// invoked by Fabric on the server thread.
public final class NavFabricMod implements ModInitializer {
  public static final String MOD_ID = "squaremap-pro";
  private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(server -> {
      NavServices.start(server, SnapshotCache.Config.defaults());
      LOGGER.info("squaremap-pro nav services started");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
      NavServices.stop(server);
      LOGGER.info("squaremap-pro nav services stopped");
    });
    ServerTickEvents.END_SERVER_TICK.register(server -> {
      NavServices s = NavServices.current();
      if (s != null) {
        s.tick(server);
      }
    });
    ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
      NavServices s = NavServices.current();
      if (s != null && success) {
        s.onDataPackReload(server);
      }
    });
    LOGGER.info("squaremap-pro initialized");
  }
}
