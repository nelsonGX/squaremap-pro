package dev.nelsongx.map.fabric.player;

import java.util.List;

/**
 * The players visible on the map at one moment.
 *
 * @param players visible players, in a stable order (by name)
 * @param max the server's player slot count
 */
// THREADING: immutable value published through a volatile field; safe on any thread.
public record PlayerSnapshot(List<PlayerPosition> players, int max) {

  /** An empty snapshot (no server running). */
  public static final PlayerSnapshot EMPTY = new PlayerSnapshot(List.of(), 0);

  /** Copies the list. */
  public PlayerSnapshot {
    players = List.copyOf(players);
  }
}
