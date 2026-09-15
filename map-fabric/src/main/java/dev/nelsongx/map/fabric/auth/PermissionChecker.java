package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.util.concurrent.CompletableFuture;

/** Decides whether a player may edit the map. */
// THREADING: canEdit may be called from any thread and never blocks; the future may complete on any
// thread (the server thread or a permission provider's thread).
public interface PermissionChecker {

  /** Permission node for editing. */
  String EDIT_NODE = "squaremappro.edit";

  /**
   * Checks {@link #EDIT_NODE} for a player who may be offline.
   *
   * <p>Takes an {@link Actor} rather than a bare UUID: the vanilla op-level fallback for offline
   * players ({@code MinecraftServer.getProfilePermissions(NameAndId)}) compares the name for the
   * singleplayer owner of an integrated server.
   *
   * @param actor the player (uuid and last known name)
   * @return future of the decision; fails if the server is gone or the check times out
   */
  CompletableFuture<Boolean> canEdit(Actor actor);
}
