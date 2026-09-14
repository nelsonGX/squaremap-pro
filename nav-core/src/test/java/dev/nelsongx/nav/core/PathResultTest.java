package dev.nelsongx.nav.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nelsongx.nav.core.PathResult.Failure;
import dev.nelsongx.nav.core.PathResult.FailureReason;
import dev.nelsongx.nav.core.PathResult.Success;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PathResultTest {

  @Test
  void successDefensivelyCopiesPoints() {
    List<GridPos> src = new ArrayList<>(List.of(new GridPos(0, 1, 0), new GridPos(1, 1, 0)));
    Success s = new Success(src, 7);
    src.add(new GridPos(9, 9, 9));
    assertEquals(2, s.points().size());
    assertEquals(7, s.nodesExpanded());
    assertThrows(UnsupportedOperationException.class, () -> s.points().add(new GridPos(0, 0, 0)));
    assertThrows(UnsupportedOperationException.class, () -> s.points().clear());
  }

  @Test
  void successValidation() {
    assertThrows(NullPointerException.class, () -> new Success(null, 0));
    assertThrows(IllegalArgumentException.class, () -> new Success(List.of(), 0));
    assertThrows(NullPointerException.class,
        () -> new Success(Arrays.asList(new GridPos(0, 0, 0), null), 0));
    assertThrows(IllegalArgumentException.class,
        () -> new Success(List.of(new GridPos(0, 0, 0)), -1));
  }

  @Test
  void failureValidation() {
    Failure f = new Failure(FailureReason.CAP_EXCEEDED, 100, "cap");
    assertEquals(FailureReason.CAP_EXCEEDED, f.reason());
    assertEquals(100, f.nodesExpanded());
    assertEquals("cap", f.detail());
    assertThrows(NullPointerException.class, () -> new Failure(null, 0, "x"));
    assertThrows(NullPointerException.class, () -> new Failure(FailureReason.NO_PATH, 0, null));
    assertThrows(IllegalArgumentException.class,
        () -> new Failure(FailureReason.INVALID_ENDPOINT, -1, "x"));
  }

  @Test
  void sealedSwitchIsExhaustive() {
    PathResult r = new Failure(FailureReason.NO_PATH, 3, "none");
    int n = switch (r) {
      case Success s -> s.points().size();
      case Failure f -> -f.nodesExpanded();
    };
    assertEquals(-3, n);
  }
}
