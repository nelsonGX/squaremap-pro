package dev.nelsongx.map.fabric.squaremap;

import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chooses the map layer implementation. */
// THREADING: create() runs once from MapFabricMod.onInitialize (loader thread).
public final class MapLayers {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  private MapLayers() {
  }

  /**
   * Returns a squaremap-backed layer if squaremap is installed, else a no-op layer.
   *
   * <p>Class-loading: the JVM resolves class references lazily, the first time the bytecode that uses
   * them executes. {@code SquaremapMapLayer} (the only class referencing
   * {@code xyz.jpenilla.squaremap.*}) is referenced solely from {@link SquaremapFactory#create}, a
   * separate nested class that is itself only touched inside the {@code isModLoaded} branch. So when
   * squaremap is absent neither {@code SquaremapFactory} nor {@code SquaremapMapLayer} is ever
   * loaded or linked, and no {@code NoClassDefFoundError} can occur. (Putting the {@code new} in a
   * separate class also keeps the bytecode verifier of this class from needing to load it: this
   * method's return type is an interface, which the verifier treats as assignable without loading.)
   * {@code LinkageError} is still caught in case an incompatible squaremap version is installed.
   *
   * @param server supplier of the running server (null when none)
   * @return the layer
   */
  public static MapLayer create(Supplier<MinecraftServer> server) {
    if (!FabricLoader.getInstance().isModLoaded("squaremap")) {
      LOGGER.info("squaremap not installed; squaremap layer disabled");
      return new NoopMapLayer();
    }
    try {
      MapLayer layer = SquaremapFactory.create(server);
      LOGGER.info("squaremap detected; squaremap layer enabled");
      return layer;
    } catch (RuntimeException | LinkageError e) {
      LOGGER.warn("squaremap integration unavailable; squaremap layer disabled", e);
      return new NoopMapLayer();
    }
  }

  /** Isolates the reference to the squaremap-dependent class. */
  private static final class SquaremapFactory {
    static MapLayer create(Supplier<MinecraftServer> server) {
      return new SquaremapMapLayer(server);
    }
  }
}
