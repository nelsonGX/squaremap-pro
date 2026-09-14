package dev.nelsongx.nav.fabric.http;

import dev.nelsongx.nav.fabric.route.RouteOutcome;
import java.util.concurrent.CompletableFuture;

/**
 * The route computation behind {@code GET /route}, injected into {@link RouteHttpServer} so the server
 * needs no Minecraft classes (tests pass a fake).
 */
// THREADING: route() is called on a Jetty request thread and must not block or touch world state;
// the returned future may complete on any thread.
@FunctionalInterface
public interface RouteFunction {

  /**
   * Starts a route computation.
   *
   * @param query validated request
   * @return future outcome; should not complete exceptionally for expected failures
   */
  CompletableFuture<RouteOutcome> route(RouteQuery query);
}
