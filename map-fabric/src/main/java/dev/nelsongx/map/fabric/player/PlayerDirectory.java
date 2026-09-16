package dev.nelsongx.map.fabric.player;

/** The players the web map may show. Minecraft-free so HTTP handlers can depend on it. */
// THREADING: implementations are read from any thread and never block.
@FunctionalInterface
public interface PlayerDirectory {

  /** A directory that never has players. */
  PlayerDirectory EMPTY = () -> PlayerSnapshot.EMPTY;

  /** @return the latest snapshot; never null */
  PlayerSnapshot players();
}
