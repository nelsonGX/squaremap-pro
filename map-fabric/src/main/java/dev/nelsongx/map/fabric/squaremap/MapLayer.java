package dev.nelsongx.map.fabric.squaremap;

/**
 * Mirrors map content onto squaremap's own web map. Free of Minecraft and squaremap types so callers
 * never load squaremap classes. Feature mirroring is added by PLAN task 10.
 */
// THREADING: available() — ANY THREAD. onServerStopping — SERVER THREAD ONLY.
public interface MapLayer {

  /** @return whether a map backend is installed and its API is currently loaded */
  boolean available();

  /**
   * @return squaremap's tiles directory ({@code Squaremap.webDir().resolve("tiles")}), or null when
   *     squaremap is not loaded. ANY THREAD.
   */
  java.nio.file.Path tilesDir();

  /** Releases map registrations. SERVER THREAD ONLY (SERVER_STOPPING). */
  void onServerStopping();
}
