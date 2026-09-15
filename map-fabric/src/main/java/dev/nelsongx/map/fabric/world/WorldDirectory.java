package dev.nelsongx.map.fabric.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The worlds the web map offers. Minecraft-free so HTTP handlers can depend on it. */
// THREADING: implementations are read from any thread and never block.
public interface WorldDirectory {

  /**
   * A world.
   *
   * @param id {@code namespace:path} (squaremap {@code WorldIdentifier.asString()} form)
   * @param name display name
   */
  record World(String id, String name) {
    /** Validates. */
    public World {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(name, "name");
    }
  }

  /** @return current worlds, in a stable order */
  List<World> worlds();

  /**
   * @param id world id
   * @return the world if currently known
   */
  default Optional<World> find(String id) {
    for (World w : worlds()) {
      if (w.id().equals(id)) {
        return Optional.of(w);
      }
    }
    return Optional.empty();
  }

  /**
   * squaremap's web name for a world: {@code level.dimension().identifier().toString().replace(":",
   * "_")} ({@code Util.levelWebName}, common v1.3.12). Used for {@code tiles/<web name>/}.
   *
   * @param id world id
   * @return web name
   */
  static String webName(String id) {
    return id.replace(":", "_");
  }
}
