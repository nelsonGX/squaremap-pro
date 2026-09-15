package dev.nelsongx.map.fabric.http.api;

import dev.nelsongx.map.core.feature.Feature;

/** Notified after every successful feature write through the HTTP API (e.g. the squaremap mirror). */
// THREADING: called on the worker executor thread that performed the write, after the store
// committed. Implementations must be thread-safe and must not block for long.
public interface FeatureChangeListener {

  /** Listener that ignores everything. */
  FeatureChangeListener NONE = new FeatureChangeListener() {
    @Override
    public void onUpsert(String worldId, Feature feature) {
    }

    @Override
    public void onDelete(String worldId, Feature deleted) {
    }
  };

  /**
   * A feature was created or updated.
   *
   * @param worldId world
   * @param feature the stored feature (new revision)
   */
  void onUpsert(String worldId, Feature feature);

  /**
   * A feature was deleted.
   *
   * @param worldId world
   * @param deleted the feature as it was before deletion
   */
  void onDelete(String worldId, Feature deleted);
}
