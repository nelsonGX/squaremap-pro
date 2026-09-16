package dev.nelsongx.map.fabric.player;

import java.util.Objects;

/**
 * One online player as shown on the map. Minecraft-free so HTTP handlers can depend on it.
 *
 * @param uuid player UUID, dashed form
 * @param name profile name
 * @param world world id ({@code namespace:path}, {@code WorldIdentifier.asString()} form)
 * @param x block x
 * @param y block y (not drawn; useful for tooltips)
 * @param z block z
 * @param yaw head rotation in degrees, squaremap's {@code Math.round(player.getYHeadRot())}
 */
// THREADING: immutable value, published through PlayerDirectory; safe on any thread.
public record PlayerPosition(String uuid, String name, String world, int x, int y, int z, int yaw) {

  /** Validates. */
  public PlayerPosition {
    Objects.requireNonNull(uuid, "uuid");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(world, "world");
  }
}
