package dev.nelsongx.map.fabric.player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * {@link PlayerDirectory} over the server's online players, with squaremap's visibility rules
 * (squaremap v1.3.12 {@code common/.../task/UpdatePlayers#collectData}): spectators, invisible
 * players and players hidden through squaremap's {@code PlayerManager} are left out. World id =
 * {@code level.dimension().identifier().toString()}, the same form
 * {@link dev.nelsongx.map.fabric.world.LevelWorldDirectory} uses.
 *
 * <p>Mojmap signatures used (1.21.11): {@code MinecraftServer#getAllLevels()},
 * {@code MinecraftServer#getPlayerList()}, {@code PlayerList#getMaxPlayers()},
 * {@code ServerLevel#players()}, {@code Player#isSpectator()}, {@code Entity#isInvisible()},
 * {@code Entity#position()} → {@code Vec3}, {@code Entity#getYHeadRot()},
 * {@code Player#getGameProfile()} → {@code GameProfile#name()}, {@code Entity#getUUID()},
 * {@code Mth#floor(double)}.
 */
// THREADING: refresh() — SERVER THREAD ONLY (reads MinecraftServer/ServerLevel/ServerPlayer and calls
// the squaremap hide check); called from END_SERVER_TICK. It publishes an immutable snapshot through
// a volatile field; players() is read from any thread (HTTP handlers). clear() — any thread.
public final class LevelPlayerDirectory implements PlayerDirectory {

  private final Predicate<UUID> hiddenOnMap;
  private volatile PlayerSnapshot snapshot = PlayerSnapshot.EMPTY;

  /**
   * @param hiddenOnMap whether a player is hidden on squaremap (false when squaremap is absent);
   *     called on the server thread
   */
  public LevelPlayerDirectory(Predicate<UUID> hiddenOnMap) {
    this.hiddenOnMap = hiddenOnMap;
  }

  @Override
  public PlayerSnapshot players() {
    return snapshot;
  }

  /** Snapshots all visible players; publishes only when something changed. SERVER THREAD ONLY. */
  public void refresh(MinecraftServer server) {
    List<PlayerPosition> out = new ArrayList<>();
    for (ServerLevel level : server.getAllLevels()) {
      String world = level.dimension().identifier().toString();
      for (ServerPlayer player : level.players()) {
        if (player.isSpectator() || player.isInvisible()) {
          continue;
        }
        UUID uuid = player.getUUID();
        if (hiddenOnMap.test(uuid)) {
          continue;
        }
        Vec3 pos = player.position();
        out.add(new PlayerPosition(uuid.toString(), player.getGameProfile().name(), world,
            Mth.floor(pos.x()), Mth.floor(pos.y()), Mth.floor(pos.z()),
            Math.floorMod(Math.round(player.getYHeadRot()), 360)));
      }
    }
    out.sort(Comparator.comparing(PlayerPosition::name).thenComparing(PlayerPosition::uuid));
    PlayerSnapshot next = new PlayerSnapshot(out, server.getPlayerList().getMaxPlayers());
    if (!next.equals(snapshot)) {
      snapshot = next;
    }
  }

  /** Clears the snapshot (server stopping). Any thread. */
  public void clear() {
    snapshot = PlayerSnapshot.EMPTY;
  }
}
