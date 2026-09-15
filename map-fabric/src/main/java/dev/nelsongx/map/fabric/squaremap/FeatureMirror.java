package dev.nelsongx.map.fabric.squaremap;

import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.FeatureType;
import dev.nelsongx.map.fabric.http.api.FeatureChangeListener;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors features onto a {@link MapLayer}: keeps a per-world copy of the features and renders each
 * one as a {@link MarkerSpec}. A full rebuild runs at server start ({@link #replaceAll}); after that
 * every successful HTTP write updates it incrementally. Stations take their railway's colour, so a
 * railway change re-renders its stations. When the layer (re-)registers a world it asks for
 * {@link #renderAll} to repaint from the copy.
 */
// THREADING: any thread. Each world's state is a ConcurrentHashMap and every render of a world is
// serialized on that map's monitor (in-memory work only, no I/O). replaceAll runs on the worker
// executor, onUpsert/onDelete on the worker that wrote, renderAll on the server thread (after layer
// registration). MapLayer marker calls are safe from any thread (SimpleLayerProvider uses a
// ConcurrentHashMap, api v1.3.12).
public final class FeatureMirror implements FeatureChangeListener {

  private final MapLayer layer;
  private final Map<String, Map<String, Feature>> worlds = new ConcurrentHashMap<>();

  /** @param layer target layer */
  public FeatureMirror(MapLayer layer) {
    this.layer = Objects.requireNonNull(layer, "layer");
  }

  /**
   * Replaces a world's features and repaints it.
   *
   * @param worldId world
   * @param features all features of the world
   */
  public void replaceAll(String worldId, List<Feature> features) {
    Map<String, Feature> model = model(worldId);
    synchronized (model) {
      model.clear();
      for (Feature f : features) {
        model.put(f.id(), f);
      }
      paint(worldId, model);
    }
  }

  /** Repaints a world from the mirrored copy (e.g. after squaremap re-registered its layer). */
  public void renderAll(String worldId) {
    Map<String, Feature> model = worlds.get(worldId);
    if (model == null) {
      return;
    }
    synchronized (model) {
      paint(worldId, model);
    }
  }

  @Override
  public void onUpsert(String worldId, Feature feature) {
    Map<String, Feature> model = model(worldId);
    synchronized (model) {
      model.put(feature.id(), feature);
      layer.putMarker(worldId, feature.id(), FeatureMarkers.toSpec(feature, model::get));
      if (feature.type() == FeatureType.RAILWAY) {
        repaintStationsOf(worldId, model, feature.id());
      }
    }
  }

  @Override
  public void onDelete(String worldId, Feature deleted) {
    Map<String, Feature> model = model(worldId);
    synchronized (model) {
      model.remove(deleted.id());
      layer.removeMarker(worldId, deleted.id());
      if (deleted.type() == FeatureType.RAILWAY) {
        repaintStationsOf(worldId, model, deleted.id());
      }
    }
  }

  /** @return the mirrored features of a world (live view; tests) */
  Collection<Feature> features(String worldId) {
    Map<String, Feature> model = worlds.get(worldId);
    return model == null ? List.of() : model.values();
  }

  private Map<String, Feature> model(String worldId) {
    return worlds.computeIfAbsent(worldId, w -> new ConcurrentHashMap<>());
  }

  private void paint(String worldId, Map<String, Feature> model) {
    layer.clearMarkers(worldId);
    for (Feature f : model.values()) {
      layer.putMarker(worldId, f.id(), FeatureMarkers.toSpec(f, model::get));
    }
  }

  private void repaintStationsOf(String worldId, Map<String, Feature> model, String railwayId) {
    for (Feature f : model.values()) {
      if (f.data() instanceof StationData s && railwayId.equals(s.railwayId())) {
        layer.putMarker(worldId, f.id(), FeatureMarkers.toSpec(f, model::get));
      }
    }
  }
}
