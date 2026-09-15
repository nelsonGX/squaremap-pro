package dev.nelsongx.map.fabric.squaremap;

/** Map layer used when squaremap is absent: draws nothing. */
// THREADING: stateless; any thread.
public final class NoopMapLayer implements MapLayer {

  @Override
  public boolean available() {
    return false;
  }

  @Override
  public void onServerStopping() {
  }
}
