package dev.nelsongx.map.fabric.squaremap;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mirrors map content onto squaremap's own web map. Free of Minecraft and squaremap types so callers
 * never load squaremap classes.
 */
// THREADING: available(), tilesDir(), putMarker(), removeMarker(), clearMarkers(),
// onWorldRegistered(), hiddenOnMap() — ANY THREAD. tick() and onServerStopping() — SERVER THREAD ONLY.
public interface MapLayer {

  /** @return whether a map backend is installed and its API is currently loaded */
  boolean available();

  /**
   * @return squaremap's tiles directory ({@code Squaremap.webDir().resolve("tiles")}), or null when
   *     squaremap is not loaded
   */
  Path tilesDir();

  /**
   * Adds or replaces a marker. If the world's layer is not registered yet, registration is scheduled
   * on the server thread and the marker is dropped; the {@link #onWorldRegistered} listener then
   * repaints the world.
   *
   * @param worldId world id ({@code namespace:path})
   * @param featureId feature id (marker key suffix)
   * @param spec marker
   */
  void putMarker(String worldId, String featureId, MarkerSpec spec);

  /** Removes a marker if present. */
  void removeMarker(String worldId, String featureId);

  /**
   * Whether the map backend hides this player (squaremap's {@code /squaremap hide}, the
   * {@code PlayerManager} API and persistent hide state). Always false without a backend.
   *
   * @param uuid player UUID
   * @return whether the player must be left off the map
   */
  boolean hiddenOnMap(UUID uuid);

  /** Removes all of our markers of a world. */
  void clearMarkers(String worldId);

  /**
   * @param listener called on the server thread with a world id whenever this layer (re-)registered
   *     that world's squaremap layer and its markers need a full repaint
   */
  void onWorldRegistered(Consumer<String> listener);

  /** Periodic check that registered layers are still present (squaremap reload). SERVER THREAD. */
  void tick();

  /** Releases map registrations. SERVER THREAD ONLY (SERVER_STOPPING). */
  void onServerStopping();
}
