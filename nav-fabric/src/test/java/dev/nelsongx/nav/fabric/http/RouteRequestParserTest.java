package dev.nelsongx.nav.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RouteRequestParserTest {

  private static RouteQuery ok(String from, String to, String world) {
    return assertInstanceOf(RouteRequestParser.Ok.class,
        RouteRequestParser.parse(from, to, world)).query();
  }

  private static RouteRequestParser.Invalid invalid(String from, String to, String world) {
    return assertInstanceOf(RouteRequestParser.Invalid.class,
        RouteRequestParser.parse(from, to, world));
  }

  @Test
  void validRequest() {
    assertEquals(new RouteQuery("minecraft:the_nether", 12, 40, 310, 95),
        ok("12,40", "310,95", "minecraft:the_nether"));
  }

  @Test
  void negativeCoordinatesAndLimits() {
    assertEquals(new RouteQuery("minecraft:overworld", -12, -40, -30_000_000, 30_000_000),
        ok("-12,-40", "-30000000,30000000", null));
    assertEquals(new RouteQuery("minecraft:overworld", 5, 0, 0, 0), ok("0005,-0", "0,0", null));
  }

  @Test
  void worldDefaultsToOverworld() {
    assertEquals("minecraft:overworld", ok("1,2", "3,4", null).world());
  }

  @Test
  void worldWithPathSegments() {
    assertEquals("my_mod:dims/sky-1.x", ok("1,2", "3,4", "my_mod:dims/sky-1.x").world());
  }

  @Test
  void missingFrom() {
    RouteRequestParser.Invalid r = invalid(null, "3,4", null);
    assertTrue(r.error().contains("missing 'from'"), r.error());
    assertEquals("minecraft:overworld", r.world());
  }

  @Test
  void missingTo() {
    assertTrue(invalid("1,2", null, null).error().contains("missing 'to'"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "1", "1,2,3", "1.5,2", "1,2.0", " 1,2", "1, 2", "1 ,2", "1,2 ",
      "+1,2", "a,b", ",", "1,", ",2", "--1,2", "1e3,2", "0x10,2"})
  void malformedFromRejected(String from) {
    RouteRequestParser.Invalid r = invalid(from, "0,0", "minecraft:the_end");
    assertTrue(r.error().contains("invalid 'from'"), r.error());
    assertEquals("minecraft:the_end", r.world(), "valid world is echoed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"30000001,0", "0,-30000001", "99999999999999999999,0",
      "2147483648,0", "-2147483649,0"})
  void outOfRangeAndOverflowRejected(String to) {
    RouteRequestParser.Invalid r = invalid("0,0", to, null);
    assertTrue(r.error().contains("out of range"), r.error());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "overworld", "Minecraft:overworld", "minecraft:", ":overworld",
      "minecraft:over world", "a:b:c", "minecraft:Overworld"})
  void invalidWorldRejectedWithDefaultEcho(String world) {
    RouteRequestParser.Invalid r = invalid("1,2", "3,4", world);
    assertTrue(r.error().contains("invalid 'world'"), r.error());
    assertEquals("minecraft:overworld", r.world());
  }
}
