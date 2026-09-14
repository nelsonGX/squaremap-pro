package dev.nelsongx.nav.fabric.http;

import java.util.Objects;

/**
 * A validated HTTP route request. Minecraft-free.
 *
 * @param world world id in {@code namespace:path} form (validated by {@link RouteRequestParser})
 * @param fromX start block x
 * @param fromZ start block z
 * @param toX goal block x
 * @param toZ goal block z
 */
// THREADING: immutable value; created on a Jetty request thread, read on any thread.
public record RouteQuery(String world, int fromX, int fromZ, int toX, int toZ) {

  /** Validates non-null world. */
  public RouteQuery {
    Objects.requireNonNull(world, "world");
  }
}
