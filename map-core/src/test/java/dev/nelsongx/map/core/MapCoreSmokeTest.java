package dev.nelsongx.map.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MapCoreSmokeTest {
  @Test
  void runsOnJava21() {
    assertEquals(21, Runtime.version().feature());
  }
}
