package dev.nelsongx.nav.fabric.squaremap;

import dev.nelsongx.nav.core.GridPos;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.LayerProvider;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.Registry;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

/**
 * squaremap-backed route layer: one "Navigation" {@link SimpleLayerProvider} per mapped world, one
 * polyline marker per player.
 *
 * <p>This is the only class in the mod that references {@code xyz.jpenilla.squaremap}. It is only
 * ever loaded through {@link NavMapLayers#create}, after {@code FabricLoader.isModLoaded("squaremap")}
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
// - Marker add/remove — ANY THREAD. SimpleLayerProvider stores markers in
//   `private final Map<Key, Marker> markers = new ConcurrentHashMap<>();` (api/.../SimpleLayerProvider.java
//   v1.3.12), and a fresh immutable-in-practice Polyline is built per call and never mutated after
//   being added.
// - `providers` is a ConcurrentHashMap written only on the server thread and read anywhere.
public final class SquaremapNavMapLayer implements NavMapLayer {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");
  static final Key LAYER_KEY = Key.of("squaremap-pro_navigation");
  private static final Color STROKE = new Color(0x1A73E8);

  private final Supplier<MinecraftServer> server;
  /** worldId → provider we registered. Written on the server thread only. */
  private final Map<String, SimpleLayerProvider> providers = new ConcurrentHashMap<>();
  private final AtomicBoolean loggedApiMissing = new AtomicBoolean();

  /**
   * @param server supplier of the running server, or null when none; any thread
   */
  public SquaremapNavMapLayer(Supplier<MinecraftServer> server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  /** @return the squaremap API or null (logged once). ANY THREAD. */
  private Squaremap api() {
    try {
      return SquaremapProvider.get();
    } catch (IllegalStateException e) {
      if (loggedApiMissing.compareAndSet(false, true)) {
        LOGGER.warn("squaremap API not loaded ({}); routes will not be drawn", e.getMessage());
      }
      return null;
    }
  }

  @Override
  public boolean available() {
    return api() != null;
  }

  @Override
  public boolean showRoute(String worldId, UUID player, String playerName, List<GridPos> points,
      double distance) {
    Squaremap api = api();
    if (api == null || points.isEmpty()) {
      return false;
    }
    Optional<MapWorld> mapWorld;
    try {
      mapWorld = api.getWorldIfEnabled(WorldIdentifier.parse(worldId));
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (mapWorld.isEmpty()) {
      return false; // world disabled in squaremap config or not loaded
    }
    Key key = Key.of(MapGeometry.markerKey(player));
    Marker marker = polyline(playerName, points, distance);

    MinecraftServer srv = server.get();
    if (srv != null && srv.isSameThread()) {
      return registerAndAdd(worldId, mapWorld.get(), key, marker);
    }
    SimpleLayerProvider existing = providers.get(worldId);
    if (existing != null) {
      existing.addMarker(key, marker); // ConcurrentHashMap-backed; any thread
      return true;
    }
    if (srv == null) {
      return false;
    }
    srv.execute(() -> {
      // MinecraftServer.execute runs inline on the caller when the server is stopped
      // (scheduleExecutables() = !isSameThread() && !isStopped()), so re-check the thread.
      if (srv.isSameThread()) {
        registerAndAdd(worldId, mapWorld.get(), key, marker);
      }
    });
    return true;
  }

  /** SERVER THREAD ONLY. */
  private boolean registerAndAdd(String worldId, MapWorld mapWorld, Key key, Marker marker) {
    try {
      SimpleLayerProvider provider = ensureLayer(worldId, mapWorld);
      provider.addMarker(key, marker);
      return true;
    } catch (RuntimeException e) {
      LOGGER.warn("failed to draw route on squaremap world {}", worldId, e);
      return false;
    }
  }

  /** SERVER THREAD ONLY. Registers our layer on first use (re-registers if replaced/stale). */
  private SimpleLayerProvider ensureLayer(String worldId, MapWorld mapWorld) {
    Registry<LayerProvider> registry = mapWorld.layerRegistry();
    SimpleLayerProvider ours = providers.get(worldId);
    if (ours != null && registry.hasEntry(LAYER_KEY) && registry.get(LAYER_KEY) == ours) {
      return ours;
    }
    if (registry.hasEntry(LAYER_KEY)) {
      // Left over from an earlier server session in this JVM (squaremap's per-world layer registries
      // are static and outlive a server); replace it.
      registry.unregister(LAYER_KEY);
    }
    SimpleLayerProvider provider = SimpleLayerProvider.builder("Navigation")
        .showControls(true)
        .defaultHidden(false)
        .layerPriority(10)
        .zIndex(500)
        .build();
    registry.register(LAYER_KEY, provider);
    providers.put(worldId, provider);
    return provider;
  }

  private static Marker polyline(String playerName, List<GridPos> points, double distance) {
    List<Point> mapPoints = new ArrayList<>(points.size());
    for (MapGeometry.MapPoint p : MapGeometry.toMapPoints(points)) {
      mapPoints.add(Point.of(p.x(), p.z()));
    }
    return Marker.polyline(mapPoints).markerOptions(MarkerOptions.builder()
        .stroke(true)
        .strokeColor(STROKE)
        .strokeWeight(4)
        .strokeOpacity(0.9)
        .fill(false)
        .hoverTooltip(MapGeometry.tooltip(playerName, distance)));
  }

  @Override
  public void clearRoute(String worldId, UUID player) {
    SimpleLayerProvider provider = providers.get(worldId);
    if (provider != null) {
      provider.removeMarker(Key.of(MapGeometry.markerKey(player))); // any thread
    }
  }

  @Override
  public void clearAll() {
    for (SimpleLayerProvider provider : providers.values()) {
      provider.clearMarkers(); // any thread
    }
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
          LOGGER.debug("could not unregister navigation layer for {}", e.getKey(), ex);
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
