package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

/**
 * {@link PermissionChecker} backed by fabric-permissions-api 0.6.1, falling back to op level 2
 * ({@link PermissionLevel#GAMEMASTERS}) when no permission provider sets the node.
 *
 * <ul>
 *   <li>Online player: on the server thread,
 *       {@code Permissions.check(Entity, String, PermissionLevel)}.</li>
 *   <li>Offline player: {@code Permissions.getPermissionValue(UUID, String)} →
 *       {@code CompletableFuture<TriState>} (provider-defined thread); if {@code TriState.DEFAULT}, hop
 *       back to the server thread for {@code MinecraftServer.getProfilePermissions(NameAndId)}
 *       {@code .level().isEqualOrHigherThan(GAMEMASTERS)}. (The API's own
 *       {@code check(NameAndId, String, PermissionLevel, MinecraftServer)} is not used because it calls
 *       {@code getProfilePermissions} inside {@code thenApplyAsync}, i.e. on the common pool.)</li>
 * </ul>
 */
// THREADING: canEdit() — any thread, never blocks. All MinecraftServer/PlayerList/player access runs
// inside server.executeIfPossible(...) and re-checks server.isSameThread() (MinecraftServer.execute
// runs the task inline on the caller when the server is stopped). Futures time out after TIMEOUT.
public final class FabricPermissionChecker implements PermissionChecker {

  /** Fallback vanilla level when no provider decides. */
  public static final PermissionLevel DEFAULT_LEVEL = PermissionLevel.GAMEMASTERS;

  private static final long TIMEOUT_SECONDS = 10;

  private final MinecraftServer server;

  /** @param server the running server */
  public FabricPermissionChecker(MinecraftServer server) {
    this.server = Objects.requireNonNull(server, "server");
  }

  @Override
  public CompletableFuture<Boolean> canEdit(Actor actor) {
    Objects.requireNonNull(actor, "actor");
    CompletableFuture<Boolean> result = new CompletableFuture<>();
    onServerThread(result, () -> {
      ServerPlayer online = server.getPlayerList().getPlayer(actor.uuid());
      if (online != null) {
        result.complete(Permissions.check(online, EDIT_NODE, DEFAULT_LEVEL));
        return;
      }
      Permissions.getPermissionValue(actor.uuid(), EDIT_NODE).whenComplete((state, error) -> {
        if (error != null) {
          result.completeExceptionally(error);
        } else if (state != null && state != TriState.DEFAULT) {
          result.complete(state.get());
        } else {
          onServerThread(result, () -> result.complete(
              server.getProfilePermissions(new NameAndId(actor.uuid(), actor.name()))
                  .level().isEqualOrHigherThan(DEFAULT_LEVEL)));
        }
      });
    });
    return result.orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /** Runs {@code task} on the server thread; failures complete {@code future} exceptionally. */
  private void onServerThread(CompletableFuture<?> future, Runnable task) {
    try {
      server.executeIfPossible(() -> {
        if (!server.isSameThread()) {
          future.completeExceptionally(new RejectedExecutionException("server is not running"));
          return;
        }
        try {
          task.run();
        } catch (RuntimeException | LinkageError e) {
          future.completeExceptionally(e);
        }
      });
    } catch (RejectedExecutionException e) {
      future.completeExceptionally(e);
    }
  }
}
