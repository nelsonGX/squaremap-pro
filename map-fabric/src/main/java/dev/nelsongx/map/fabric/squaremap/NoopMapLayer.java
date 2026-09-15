package dev.nelsongx.map.fabric.squaremap;

import java.nio.file.Path;
import java.util.function.Consumer;

/** Map layer used when squaremap is absent: draws nothing. */
// THREADING: stateless; any thread.
public final class NoopMapLayer implements MapLayer {

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public Path tilesDir() {
    return null;
  }

  @Override
  public void putMarker(String worldId, String featureId, MarkerSpec spec) {
  }

  @Override
  public void removeMarker(String worldId, String featureId) {
  }

  @Override
  public void clearMarkers(String worldId) {
  }

  @Override
  public void onWorldRegistered(Consumer<String> listener) {
  }

  @Override
  public void tick() {
  }

  @Override
  public void onServerStopping() {
  }
}
