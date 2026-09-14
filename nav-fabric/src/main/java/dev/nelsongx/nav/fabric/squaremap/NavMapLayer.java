package dev.nelsongx.nav.fabric.squaremap;

import dev.nelsongx.nav.core.GridPos;
import java.util.List;
import java.util.UUID;

/**
 * Draws player routes on a web map. Free of Minecraft and squaremap types so callers never load
 * squaremap classes.
 */
// THREADING: showRoute/clearRoute/clearAll — ANY THREAD (implementations defer anything that needs the
// server thread). onServerStopping — SERVER THREAD ONLY.
public interface NavMapLayer {

  /** @return whether a map backend is installed and its API is currently loaded */
  boolean available();

  /**
   * Shows (or replaces) a player's route.
   *
   * @param worldId world in {@code namespace:path} form
   * @param player player id (one route per player per world)
   * @param playerName name for the tooltip
   * @param points route polyline, block coordinates
   * @param distance route length in blocks
   * @return whether the route was (or has been scheduled to be) drawn; false if no backend or the
   *     world is not mapped
   */
  boolean showRoute(String worldId, UUID player, String playerName, List<GridPos> points,
      double distance);

  /**
   * Removes a player's route from one world. No-op if none.
   *
   * @param worldId world in {@code namespace:path} form
   * @param player player id
   */
  void clearRoute(String worldId, UUID player);

  /** Removes every route this layer drew. */
  void clearAll();

  /** Releases map registrations. SERVER THREAD ONLY (SERVER_STOPPING). */
  void onServerStopping();
}
