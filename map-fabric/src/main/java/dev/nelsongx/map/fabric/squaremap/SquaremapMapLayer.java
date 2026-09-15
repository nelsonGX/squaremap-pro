package dev.nelsongx.map.fabric.squaremap;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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
 * squaremap-backed layer: one {@link SimpleLayerProvider} "Map features" per mapped world, registered
 * lazily on the server thread and unregistered on server stop; markers come from {@link FeatureMirror}.
 *
 * <p>squaremap-api v1.3.12 signatures used: {@code SquaremapProvider.get()},
 * {@code Squaremap.getWorldIfEnabled(WorldIdentifier)}, {@code Squaremap.webDir()},
 * {@code WorldIdentifier.parse(String)}, {@code MapWorld.layerRegistry()},
 * {@code Registry.register/unregister/hasEntry/get(Key)}, {@code SimpleLayerProvider.builder(String)}
 * {@code .showControls(boolean).defaultHidden(boolean).layerPriority(int).zIndex(int).build()},
 * {@code SimpleLayerProvider.addMarker(Key, Marker)}, {@code removeMarker(Key)}, {@code clearMarkers()}.
 *
 * <p>This class and {@link SquaremapMarkers} are the only classes that reference
 * {@code xyz.jpenilla.squaremap}; they are only loaded through {@link MapLayers#create} after
 * {@code FabricLoader.isModLoaded("squaremap")} returned true.
 *
 * <p>The squaremap API is looked up lazily on every use because squaremap registers
 * {@code SquaremapProvider} in its own initializer (load order relative to ours is unspecified) and
 * unregisters it on shutdown ({@code SquaremapCommon.shutdown()} → {@code shutdownApi()}, v1.3.12).
 */
// THREADING:
// - Layer registration/unregistration (MapWorld.layerRegistry(), Registry.register/unregister) — SERVER
//   THREAD ONLY. MapWorldInternal.layerRegistry() does computeIfAbsent on a plain static HashMap
//   (`private static final Map<WorldIdentifier, LayerRegistry> LAYER_REGISTRIES = new HashMap<>();`,
//   common/.../data/MapWorldInternal.java v1.3.12). Workers that find no provider schedule
//   registration with server.execute(...) and never wait for it.
// - Marker add/remove/clear on a registered provider — ANY THREAD. SimpleLayerProvider stores markers in
//   `private final Map<Key, Marker> markers = new ConcurrentHashMap<>();` (api/.../SimpleLayerProvider.java
//   v1.3.12).
// - `providers` is a ConcurrentHashMap written only on the server thread and read anywhere;
//   `pending` is a concurrent set; `listener` is volatile.
public final class SquaremapMapLayer implements MapLayer {

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");
  static final Key LAYER_KEY = Key.of("squaremap-pro_features");
  static final String LAYER_LABEL = "Map features";

  private final Supplier<MinecraftServer> server;
  /** worldId → provider we registered. Written on the server thread only. */
  private final Map<String, SimpleLayerProvider> providers = new ConcurrentHashMap<>();
  /** Worlds whose registration has been scheduled (or that we want kept registered). */
  private final Set<String> wanted = ConcurrentHashMap.newKeySet();
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean loggedApiMissing = new AtomicBoolean();
  private volatile Consumer<String> listener = w -> { };

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

  @Override
  public boolean available() {
    return api() != null;
  }

  /**
   * {@code @NonNull Path webDir()} (api v1.3.12 {@code Squaremap}); the implementation
   * ({@code SquaremapApiProvider.webDir()} → {@code DirectoryProvider.webDirectory()}, common v1.3.12)
   * returns an immutable {@code Path} field assigned during squaremap startup/config load, so reading
   * it from HTTP threads only risks a stale value right after a squaremap reload.
   */
  @Override
  public Path tilesDir() {
    Squaremap api = api();
    return api == null ? null : api.webDir().resolve("tiles");
  }

  @Override
  public void putMarker(String worldId, String featureId, MarkerSpec spec) {
    SimpleLayerProvider p = providerOrSchedule(worldId);
    if (p != null) {
      p.addMarker(SquaremapMarkers.key(featureId), SquaremapMarkers.toMarker(spec));
    }
  }

  @Override
  public void removeMarker(String worldId, String featureId) {
    SimpleLayerProvider p = providers.get(worldId);
    if (p != null) {
      p.removeMarker(SquaremapMarkers.key(featureId));
    }
  }

  @Override
  public void clearMarkers(String worldId) {
    SimpleLayerProvider p = providerOrSchedule(worldId);
    if (p != null) {
      p.clearMarkers();
    }
  }

  @Override
  public void onWorldRegistered(Consumer<String> listener) {
    this.listener = Objects.requireNonNull(listener, "listener");
  }

  /** @return our provider, or null after scheduling registration on the server thread. ANY THREAD. */
  private SimpleLayerProvider providerOrSchedule(String worldId) {
    wanted.add(worldId);
    SimpleLayerProvider p = providers.get(worldId);
    if (p != null) {
      return p;
    }
    MinecraftServer s = server.get();
    if (s == null || s.isSameThread() || !pending.add(worldId)) {
      // on the server thread the tick() check will register it; avoid re-entrant repaint
      return null;
    }
    try {
      s.executeIfPossible(() -> {
        pending.remove(worldId);
        if (s.isSameThread()) {
          registerAndNotify(worldId);
        }
      });
    } catch (RejectedExecutionException e) {
      pending.remove(worldId);
    }
    return null;
  }

  /** SERVER THREAD ONLY. */
  private void registerAndNotify(String worldId) {
    SimpleLayerProvider before = providers.get(worldId);
    Optional<SimpleLayerProvider> after = ensureLayer(worldId);
    if (after.isPresent() && after.get() != before) {
      try {
        listener.accept(worldId);
      } catch (RuntimeException e) {
        LOGGER.warn("squaremap-pro repaint of {} failed", worldId, e);
      }
    }
  }

  @Override
  public void tick() {
    for (String worldId : wanted) {
      registerAndNotify(worldId);
    }
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
    SimpleLayerProvider provider = SimpleLayerProvider.builder(LAYER_LABEL)
        .showControls(true)
        .defaultHidden(false)
        .layerPriority(10)
        .zIndex(250)
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
      wanted.clear();
      pending.clear();
    }
  }
}
