package dev.nelsongx.map.fabric.http.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.core.route.Mode;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonCodecTest {

  @Test
  void strictJson() {
    assertNotNull(FeatureJson.parseStrict("{\"a\":1}"));
    assertNull(FeatureJson.parseStrict("{\"a\":1} trailing"));
    assertNull(FeatureJson.parseStrict("{'a':1}"));
    assertNull(FeatureJson.parseStrict("{a:1}"));
    assertNull(FeatureJson.parseStrict(""));
  }

  @Test
  void inputParsing() {
    FeatureJson.Input b = FeatureJson.parseInput("{\"type\":\"building\",\"name\":\"Hall\","
        + "\"geometry\":[{\"x\":0,\"z\":0},{\"x\":4,\"z\":0},{\"x\":4,\"z\":4.0}],"
        + "\"props\":{\"category\":\"public\",\"description\":null}}", false);
    assertTrue(b.errors().isEmpty(), b.errors().toString());
    BuildingData data = (BuildingData) b.data();
    assertEquals(new Vertex(4, 4), data.ring().get(2));
    assertEquals("", data.description());

    FeatureJson.Input s = FeatureJson.parseInput("{\"revision\":3,\"type\":\"station\",\"name\":\"C\","
        + "\"geometry\":[{\"x\":1,\"z\":2}],\"props\":{\"railwayId\":\"f_1\"}}", true);
    assertEquals(3L, s.revision());
    assertEquals(new Vertex(1, 2), ((StationData) s.data()).point());

    assertEquals("revision", FeatureJson.parseInput("{\"type\":\"road\"}", true).errors().get(0).field());
    assertEquals("category", FeatureJson.parseInput("{\"type\":\"building\",\"props\":{\"category\":\"castle\"}}",
        false).errors().get(0).field());
    assertEquals("geometry", FeatureJson.parseInput(
        "{\"type\":\"road\",\"geometry\":[{\"x\":3000000000,\"z\":0}]}", false).errors().get(0).field());
    assertEquals("body", FeatureJson.parseInput("[]", false).errors().get(0).field());
  }

  @Test
  void routeQueryParsing() {
    assertEquals(Optional.of(new Vertex(-12, 40)), RouteJson.parseXz("-12,40"));
    assertEquals(Optional.empty(), RouteJson.parseXz("1, 2"));
    assertEquals(Optional.empty(), RouteJson.parseXz("2147483648,0"));
    assertEquals(Optional.of(EnumSet.allOf(Mode.class)), RouteJson.parseModes(null));
    assertEquals(Optional.of(EnumSet.of(Mode.WALK, Mode.RAIL)), RouteJson.parseModes("rail"));
    assertEquals(Optional.empty(), RouteJson.parseModes("rail,"));
    assertEquals(1.3, RouteJson.round1(1.25));
  }

  @Test
  void relativePathCleaning() {
    assertEquals(Optional.of("a/b.png"), StaticFiles.cleanRelativePath("a/b.png"));
    assertEquals(Optional.of("dir/"), StaticFiles.cleanRelativePath("dir/"));
    assertEquals(Optional.of(""), StaticFiles.cleanRelativePath(""));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("../x"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("a/%2e%2e/x"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("a%2F..%2Fx"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("a%5Cx"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("a//x"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("C:/x"));
    assertEquals(Optional.empty(), StaticFiles.cleanRelativePath("%zz"));
  }
}
