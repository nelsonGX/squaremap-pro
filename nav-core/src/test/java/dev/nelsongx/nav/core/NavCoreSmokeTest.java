package dev.nelsongx.nav.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NavCoreSmokeTest {
  @Test
  void runsOnJava21() {
    assertEquals(21, Runtime.version().feature());
  }
}
