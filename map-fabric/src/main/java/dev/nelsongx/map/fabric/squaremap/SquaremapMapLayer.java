package dev.nelsongx.map.fabric.squaremap;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.LayerProvider;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Registry;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;

/**
 * squaremap-backed layer plumbing: one {@link SimpleLayerProvider} per mapped world, registered lazily
 * via {@link #ensureLayer} and unregistered on server stop. Markers are added by PLAN task 10.
 *
 * <p>This is the only class in the mod that references {@code xyz.jpenilla.squaremap}. It is only
 * ever loaded through {@link MapLayers#create}, after {@code FabricLoader.isModLoaded("squaremap")}
 * returned true.
 *
 * <p>The squaremap API is looked up lazily on every use because squaremap registers
 * {@code SquaremapProvider} in its own initializer (load order relative to ours is unspecified) and
 * unregisters it on shutdown ({@code SquaremapCommon.shutdown()} → {@code shutdownApi()}, v1.3.12).
 */
// THREADING:
// - Layer registration/unregistration (MapWorld.layerRegistry(), Registry.register/unregister) — SERVER
//   THREAD ONLY. MapWorldInternal.layerRegistry() does computeIfAbsent on a plain static HashMap
//   (`private static final Map<WorldIdentifier, LayerRegistry> LAYER_REGISTRIES = new HashMap<>();`,
//   common/.../data/MapWorldInternal.java v1.3.12), so it is never called off the server thread here.
// - Marker add/remove on a registered provider — ANY THREAD. SimpleLayerProvider stores markers in
//   `private final Map<Key, Marker> markers = new ConcurrentHashMap<>();` (api/.../SimpleLayerProvider.java
//   v1.3.12).
// - `providers` is a ConcurrentHashMap written only on the server thread and read anywhere.
public final class SquaremapMapLayer implements MapLayer {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");
  static final Key LAYER_KEY = Key.of("squaremap-pro_map");

  private final Supplier<MinecraftServer> server;
  /** worldId → provider we registered. Written on the server thread only. */
  private final Map<String, SimpleLayerProvider> providers = new ConcurrentHashMap<>();
  private final AtomicBoolean loggedApiMissing = new AtomicBoolean();

  /**
   * @param server supplier of the running server, or null when none; any thread
   */
  public SquaremapMapLayer(Supplier<MinecraftServer> server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  /** @return the squaremap API or null (logged once). ANY THREAD. */
  private Squaremap api() {
    try {
      return SquaremapProvider.get();
    } catch (IllegalStateException e) {
      if (loggedApiMissing.compareAndSet(false, true)) {
        LOGGER.warn("squaremap API not loaded ({}); squaremap layer inactive", e.getMessage());
      }
      return null;
    }
  }

  /** @return the running server or null. ANY THREAD. */
  MinecraftServer server() {
    return server.get();
  }

  @Override
  public boolean available() {
    return api() != null;
  }

  /**
   * Our provider for a world, registering it on first use (re-registers if replaced/stale). SERVER
   * THREAD ONLY.
   *
   * @param worldId world in {@code namespace:path} form
   * @return the provider, or empty if squaremap is not loaded or the world is not mapped
   */
  Optional<SimpleLayerProvider> ensureLayer(String worldId) {
    Squaremap api = api();
    if (api == null) {
      return Optional.empty();
    }
    Optional<MapWorld> mapWorld;
    try {
      mapWorld = api.getWorldIfEnabled(WorldIdentifier.parse(worldId));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (mapWorld.isEmpty()) {
      return Optional.empty(); // world disabled in squaremap config or not loaded
    }
    Registry<LayerProvider> registry = mapWorld.get().layerRegistry();
    SimpleLayerProvider ours = providers.get(worldId);
    if (ours != null && registry.hasEntry(LAYER_KEY) && registry.get(LAYER_KEY) == ours) {
      return Optional.of(ours);
    }
    if (registry.hasEntry(LAYER_KEY)) {
      // Left over from an earlier server session in this JVM (squaremap's per-world layer registries
      // are static and outlive a server); replace it.
      registry.unregister(LAYER_KEY);
    }
    SimpleLayerProvider provider = SimpleLayerProvider.builder("Map")
        .showControls(true)
        .defaultHidden(false)
        .layerPriority(10)
        .zIndex(500)
        .build();
    registry.register(LAYER_KEY, provider);
    providers.put(worldId, provider);
    return Optional.of(provider);
  }

  @Override
  public void onServerStopping() {
    // Our SERVER_STOPPING runs before squaremap's shutdown on SERVER_STOPPED (dedicated) but squaremap
    // may already be gone on an integrated server (CLIENT_STOPPING) — guard every step.
    try {
      Squaremap api = api();
      if (api == null) {
        return;
      }
      for (Map.Entry<String, SimpleLayerProvider> e : providers.entrySet()) {
        try {
          Optional<MapWorld> mapWorld = api.getWorldIfEnabled(WorldIdentifier.parse(e.getKey()));
          if (mapWorld.isEmpty()) {
            continue;
          }
          Registry<LayerProvider> registry = mapWorld.get().layerRegistry();
          if (registry.hasEntry(LAYER_KEY) && registry.get(LAYER_KEY) == e.getValue()) {
            registry.unregister(LAYER_KEY);
          }
        } catch (RuntimeException ex) {
          LOGGER.debug("could not unregister squaremap layer for {}", e.getKey(), ex);
        }
      }
    } finally {
      for (SimpleLayerProvider provider : providers.values()) {
        provider.clearMarkers();
      }
      providers.clear();
    }
  }
}
