package dev.nelsongx.nav.fabric.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.PathResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RouteOutcomeTest {

  private static final String W = "minecraft:overworld";
  private static final GridPos A = new GridPos(0, 64, 0);
  private static final GridPos B = new GridPos(3, 68, 0);
  private static final GridPos C = new GridPos(3, 68, 12);

  @Test
  void wireNamesMatchSchemaExactly() {
    Map<RouteOutcome.Status, String> expected = Map.of(
        RouteOutcome.Status.OK, "ok",
        RouteOutcome.Status.NO_PATH, "no_path",
        RouteOutcome.Status.CAP_EXCEEDED, "cap_exceeded",
        RouteOutcome.Status.INVALID_REQUEST, "invalid_request",
        RouteOutcome.Status.WORLD_NOT_FOUND, "world_not_found",
        RouteOutcome.Status.NOT_READY, "not_ready");
    assertEquals(expected.size(), RouteOutcome.Status.values().length);
    for (RouteOutcome.Status s : RouteOutcome.Status.values()) {
      assertEquals(expected.get(s), s.wireName());
    }
  }

  @Test
  void distanceIs3dEuclideanAlongPoints() {
    // A->B: sqrt(9 + 16) = 5; B->C: 12
    assertEquals(17.0, RouteOutcome.length(List.of(A, B, C)), 1e-9);
    assertEquals(0.0, RouteOutcome.length(List.of(A)), 0);
    assertEquals(0.0, RouteOutcome.length(List.of()), 0);
    RouteOutcome ok = RouteOutcome.ok(W, A, C, List.of(A, B, C), 42);
    assertEquals(17.0, ok.distance(), 1e-9);
    assertEquals(42, ok.nodesExpanded());
    assertNull(ok.error());
  }

  @Test
  void okInvariants() {
    assertThrows(IllegalArgumentException.class, () -> RouteOutcome.ok(W, A, C, List.of(), 0));
    assertThrows(NullPointerException.class, () -> RouteOutcome.ok(W, null, C, List.of(A, C), 0));
    assertThrows(NullPointerException.class, () -> RouteOutcome.ok(W, A, null, List.of(A, C), 0));
    assertThrows(IllegalArgumentException.class,
        () -> new RouteOutcome(RouteOutcome.Status.OK, W, A, C, List.of(A, C), 1, 0, "err"));
    assertThrows(IllegalArgumentException.class, () -> RouteOutcome.ok(W, A, C, List.of(A, C), -1));
  }

  @Test
  void failureInvariants() {
    assertThrows(IllegalArgumentException.class,
        () -> new RouteOutcome(RouteOutcome.Status.NO_PATH, W, A, C, List.of(A), 0, 0, "x"));
    assertThrows(IllegalArgumentException.class,
        () -> new RouteOutcome(RouteOutcome.Status.NO_PATH, W, A, C, List.of(), 0, 0, null));
    assertThrows(IllegalArgumentException.class,
        () -> new RouteOutcome(RouteOutcome.Status.NO_PATH, W, A, C, List.of(), 3.0, 0, "x"));
    assertThrows(IllegalArgumentException.class,
        () -> RouteOutcome.failure(RouteOutcome.Status.OK, W, A, C, 0, "x"));
    RouteOutcome f = RouteOutcome.invalidRequest(W, "bad");
    assertTrue(f.points().isEmpty());
    assertNull(f.from());
    assertNull(f.to());
    assertEquals(0, f.distance());
    assertEquals("bad", f.error());
    assertEquals(RouteOutcome.Status.WORLD_NOT_FOUND, RouteOutcome.worldNotFound(W, "x").status());
    assertEquals(RouteOutcome.Status.NOT_READY, RouteOutcome.notReady(W, "x").status());
  }

  @Test
  void pointsAreDefensivelyCopied() {
    List<GridPos> pts = new ArrayList<>(List.of(A, C));
    RouteOutcome ok = RouteOutcome.ok(W, A, C, pts, 0);
    pts.add(B);
    assertEquals(2, ok.points().size());
    assertThrows(UnsupportedOperationException.class, () -> ok.points().add(B));
  }

  @Test
  void mapsSuccess() {
    RouteOutcome o = RouteOutcome.fromPathResult(W, A, C,
        new PathResult.Success(List.of(A, B, C), 99));
    assertEquals(RouteOutcome.Status.OK, o.status());
    assertEquals(List.of(A, B, C), o.points());
    assertEquals(17.0, o.distance(), 1e-9);
    assertEquals(99, o.nodesExpanded());
    assertEquals(A, o.from());
    assertEquals(C, o.to());
    assertEquals(W, o.world());
  }

  @Test
  void mapsFailures() {
    assertFailure(PathResult.FailureReason.NO_PATH, RouteOutcome.Status.NO_PATH);
    assertFailure(PathResult.FailureReason.CAP_EXCEEDED, RouteOutcome.Status.CAP_EXCEEDED);
    assertFailure(PathResult.FailureReason.INVALID_ENDPOINT, RouteOutcome.Status.INVALID_REQUEST);
    for (PathResult.FailureReason r : PathResult.FailureReason.values()) {
      RouteOutcome o = RouteOutcome.fromPathResult(W, A, C, new PathResult.Failure(r, 0, " "));
      assertTrue(o.error() != null && !o.error().isBlank(), "blank detail gets default: " + r);
    }
  }

  private static void assertFailure(PathResult.FailureReason reason, RouteOutcome.Status status) {
    RouteOutcome o = RouteOutcome.fromPathResult(W, A, C,
        new PathResult.Failure(reason, 7, "detail"));
    assertEquals(status, o.status());
    assertEquals("detail", o.error());
    assertEquals(7, o.nodesExpanded());
    assertTrue(o.points().isEmpty());
    assertEquals(0.0, o.distance(), 0);
    assertEquals(A, o.from());
    assertEquals(C, o.to());
  }

  @Test
  void chunkBoxExpandsByMargin() {
    ChunkBox b = ChunkBox.around(-1, 17, 40, -33, 6);
    // chunks: x -1 -> -1, 40 -> 2; z 17 -> 1, -33 -> -3
    assertEquals(new ChunkBox(-7, -9, 8, 7), b);
    assertEquals(16L * 17L, b.chunkCount());
    assertEquals(1L, ChunkBox.around(5, 5, 5, 5, 0).chunkCount());
    assertThrows(IllegalArgumentException.class, () -> ChunkBox.around(0, 0, 0, 0, -1));
  }
}
