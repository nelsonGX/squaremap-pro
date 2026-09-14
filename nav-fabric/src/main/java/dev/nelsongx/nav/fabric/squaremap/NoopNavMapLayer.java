package dev.nelsongx.nav.fabric.squaremap;

import dev.nelsongx.nav.core.GridPos;
import java.util.List;
import java.util.UUID;

/** Map layer used when squaremap is absent: draws nothing. */
// THREADING: stateless; any thread.
public final class NoopNavMapLayer implements NavMapLayer {

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public boolean showRoute(String worldId, UUID player, String playerName, List<GridPos> points,
      double distance) {
    return false;
  }

  @Override
  public void clearRoute(String worldId, UUID player) {
  }

  @Override
  public void clearAll() {
  }

  @Override
  public void onServerStopping() {
  }
}
