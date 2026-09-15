package dev.nelsongx.map.fabric.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * {@link WorldDirectory} over the server's loaded levels ({@code MinecraftServer.getAllLevels()}).
 * World id = {@code level.dimension().identifier().toString()} ({@code Level.dimension()} →
 * {@code ResourceKey<Level>}, {@code ResourceKey.identifier()} → {@code Identifier}, 1.21.11 Mojmap;
 * the same expression squaremap v1.3.12 {@code Util.levelConfigName} uses). Name = squaremap web name
 * ({@link WorldDirectory#webName}) when squaremap is available, else the identifier path.
 */
// THREADING: refresh() — SERVER THREAD ONLY (reads MinecraftServer/ServerLevel); called at
// SERVER_STARTED and periodically from END_SERVER_TICK. It publishes an immutable list through a
// volatile field; worlds() is read from any thread (HTTP handlers). clear() — any thread.
public final class LevelWorldDirectory implements WorldDirectory {

  private final BooleanSupplier squaremapAvailable;
  private volatile List<World> worlds = List.of();

  /** @param squaremapAvailable whether squaremap's API is loaded (any thread) */
  public LevelWorldDirectory(BooleanSupplier squaremapAvailable) {
    this.squaremapAvailable = squaremapAvailable;
  }

  @Override
  public List<World> worlds() {
    return worlds;
  }

  /** Snapshots all loaded levels; publishes only when the list changed. SERVER THREAD ONLY. */
  public void refresh(MinecraftServer server) {
    boolean squaremap = squaremapAvailable.getAsBoolean();
    List<World> out = new ArrayList<>();
    for (ServerLevel level : server.getAllLevels()) {
      String id = level.dimension().identifier().toString();
      String name = squaremap ? WorldDirectory.webName(id) : level.dimension().identifier().getPath();
      out.add(new World(id, name));
    }
    // vanilla dimensions first in their usual order, then the rest by id
    out.sort(Comparator.comparingInt((World w) -> rank(w.id())).thenComparing(World::id));
    if (!out.equals(worlds)) {
      worlds = List.copyOf(out);
    }
  }

  /** Clears the list (server stopping). Any thread. */
  public void clear() {
    worlds = List.of();
  }

  private static int rank(String id) {
    return switch (id) {
      case "minecraft:overworld" -> 0;
      case "minecraft:the_nether" -> 1;
      case "minecraft:the_end" -> 2;
      default -> 3;
    };
  }
}
