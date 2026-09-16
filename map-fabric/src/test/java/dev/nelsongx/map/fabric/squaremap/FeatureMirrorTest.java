package dev.nelsongx.map.fabric.squaremap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.Actor;
import dev.nelsongx.map.core.feature.BuildingCategory;
import dev.nelsongx.map.core.feature.Feature;
import dev.nelsongx.map.core.feature.FeatureData;
import dev.nelsongx.map.core.feature.FeatureData.BuildingData;
import dev.nelsongx.map.core.feature.FeatureData.RailwayData;
import dev.nelsongx.map.core.feature.FeatureData.RoadData;
import dev.nelsongx.map.core.feature.FeatureData.StationData;
import dev.nelsongx.map.core.feature.RoadClass;
import dev.nelsongx.map.core.feature.Vertex;
import dev.nelsongx.map.fabric.squaremap.MapGeometry.MapPoint;
import dev.nelsongx.map.fabric.squaremap.MarkerSpec.Shape;
import java.awt.Color;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import xyz.jpenilla.squaremap.api.marker.Circle;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.Polygon;
import xyz.jpenilla.squaremap.api.marker.Polyline;

class FeatureMirrorTest {

  private static final Actor A = new Actor(UUID.fromString("00000000-0000-0000-0000-000000000001"), "A");
  private static final String W = "minecraft:overworld";

  private static Feature feature(String id, FeatureData data) {
    Instant t = Instant.parse("2026-09-15T10:00:00Z");
    return new Feature(id, W, 1, data, A, t, A, t);
  }

  private static List<Vertex> vs(int... xz) {
    Vertex[] out = new Vertex[xz.length / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = new Vertex(xz[2 * i], xz[2 * i + 1]);
    }
    return List.of(out);
  }

  // ---- pure mapping ----------------------------------------------------------------------------

  @Test
  void buildingIsFilledPolygonInCategoryColourAtBlockCentres() {
    MarkerSpec s = FeatureMarkers.toSpec(feature("f_b", new BuildingData(vs(0, 0, 4, 0, 4, -3),
        "Town <Hall>", BuildingCategory.PUBLIC, "")), id -> null);
    assertEquals(Shape.POLYGON, s.shape());
    assertEquals(List.of(new MapPoint(0.5, 0.5), new MapPoint(4.5, 0.5), new MapPoint(4.5, -2.5)),
        s.points());
    assertEquals(0x9b5de5, s.strokeRgb());
    assertTrue(s.fill());
    assertEquals(0x9b5de5, s.fillRgb());
    assertEquals(0.4, s.fillOpacity());
    assertEquals("Town &lt;Hall&gt;", s.hoverTooltip());
    assertEquals("<b>Town &lt;Hall&gt;</b><br>Public building", s.clickTooltip());
  }

  @Test
  void roadWidthAndColourByClass() {
    MarkerSpec highway = FeatureMarkers.toSpec(feature("f_r1",
        new RoadData(vs(0, 0, 10, 0), "A1", RoadClass.HIGHWAY)), id -> null);
    MarkerSpec path = FeatureMarkers.toSpec(feature("f_r2",
        new RoadData(vs(0, 0, 10, 0), "", RoadClass.PATH)), id -> null);
    assertEquals(Shape.POLYLINE, highway.shape());
    assertFalse(highway.fill());
    assertEquals(0xf59e0b, highway.strokeRgb());
    assertEquals(9, highway.strokeWeight());
    assertEquals(0xe7d7b1, path.strokeRgb());
    assertEquals(3, path.strokeWeight());
    assertEquals("Unnamed road", path.hoverTooltip());
    assertTrue(path.clickTooltip().endsWith("Path"));
  }

  @Test
  void railwayAndStationColours() {
    Feature rail = feature("f_rail", new RailwayData(vs(0, 0, 100, 0), "Red Line", "#FF0000"));
    MarkerSpec r = FeatureMarkers.toSpec(rail, id -> null);
    assertEquals(0xff0000, r.strokeRgb());
    assertEquals(4, r.strokeWeight());

    Feature station = feature("f_st", new StationData(new Vertex(100, 0), "Central", "f_rail"));
    MarkerSpec s = FeatureMarkers.toSpec(station, id -> id.equals("f_rail") ? rail : null);
    assertEquals(Shape.CIRCLE, s.shape());
    assertEquals(List.of(new MapPoint(100.5, 0.5)), s.points());
    assertEquals(FeatureMarkers.STATION_RADIUS, s.radius());
    assertEquals(0xff0000, s.strokeRgb());
    assertEquals(0xffffff, s.fillRgb());
    assertEquals("<b>Central</b><br>Station · Red Line", s.clickTooltip());

    assertEquals(FeatureMarkers.UNKNOWN_RAILWAY_RGB, FeatureMarkers.toSpec(station, id -> null).strokeRgb());
  }

  @Test
  void specToSquaremapMarker() {
    Marker polygon = SquaremapMarkers.toMarker(FeatureMarkers.toSpec(feature("f_b",
        new BuildingData(vs(0, 0, 4, 0, 4, 4), "H", BuildingCategory.RESIDENTIAL, "")), id -> null));
    assertTrue(polygon instanceof Polygon);
    assertEquals(new Color(0xe76f51), polygon.markerOptions().strokeColor());
    assertEquals(new Color(0xe76f51), polygon.markerOptions().fillColor());
    assertEquals("H", polygon.markerOptions().hoverTooltip());

    Marker line = SquaremapMarkers.toMarker(FeatureMarkers.toSpec(feature("f_r",
        new RoadData(vs(0, 0, 4, 0), "R", RoadClass.MAIN)), id -> null));
    assertTrue(line instanceof Polyline);
    assertFalse(line.markerOptions().fill());
    assertEquals(7, line.markerOptions().strokeWeight());

    Marker circle = SquaremapMarkers.toMarker(FeatureMarkers.toSpec(feature("f_s",
        new StationData(new Vertex(1, 1), "S", "x")), id -> null));
    assertTrue(circle instanceof Circle);
    assertEquals(3, ((Circle) circle).radius());

    assertEquals("squaremap-pro_f_Ab9", SquaremapMarkers.key("f_Ab9").getKey());
    assertEquals("squaremap-pro_a_b", SquaremapMarkers.key("a:b").getKey());
  }

  // ---- mirror bookkeeping ------------------------------------------------------------------------

  /** Records markers per world like a SimpleLayerProvider would. */
  private static final class FakeLayer implements MapLayer {
    final Map<String, Map<String, MarkerSpec>> markers = new ConcurrentHashMap<>();
    int clears;

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public Path tilesDir() {
      return null;
    }

    @Override
    public void putMarker(String worldId, String featureId, MarkerSpec spec) {
      markers.computeIfAbsent(worldId, w -> new ConcurrentHashMap<>()).put(featureId, spec);
    }

    @Override
    public void removeMarker(String worldId, String featureId) {
      markers.getOrDefault(worldId, new ConcurrentHashMap<>()).remove(featureId);
    }

    @Override
    public boolean hiddenOnMap(java.util.UUID uuid) {
      return false;
    }

    @Override
    public void clearMarkers(String worldId) {
      clears++;
      markers.remove(worldId);
    }

    @Override
    public void onWorldRegistered(Consumer<String> listener) {
    }

    @Override
    public void tick() {
    }

    @Override
    public void onServerStopping() {
    }
  }

  @Test
  void rebuildAndIncrementalUpdates() {
    FakeLayer layer = new FakeLayer();
    FeatureMirror mirror = new FeatureMirror(layer);
    Feature rail = feature("f_rail", new RailwayData(vs(0, 0, 100, 0), "Red", "#ff0000"));
    Feature station = feature("f_st", new StationData(new Vertex(100, 0), "Central", "f_rail"));
    Feature road = feature("f_road", new RoadData(vs(0, 5, 50, 5), "Main", RoadClass.MAIN));

    mirror.replaceAll(W, List.of(rail, station, road));
    assertEquals(3, layer.markers.get(W).size());
    assertEquals(0xff0000, layer.markers.get(W).get("f_st").strokeRgb());

    // railway recoloured → its station is repainted
    Feature blue = new Feature("f_rail", W, 2, new RailwayData(vs(0, 0, 100, 0), "Blue", "#0000ff"),
        A, rail.createdAt(), A, rail.updatedAt());
    mirror.onUpsert(W, blue);
    assertEquals(0x0000ff, layer.markers.get(W).get("f_rail").strokeRgb());
    assertEquals(0x0000ff, layer.markers.get(W).get("f_st").strokeRgb());
    assertTrue(layer.markers.get(W).get("f_st").clickTooltip().contains("Blue"));

    mirror.onDelete(W, road);
    assertNull(layer.markers.get(W).get("f_road"));
    assertEquals(2, mirror.features(W).size());

    // repaint from the mirrored copy after squaremap re-registered the layer
    layer.markers.clear();
    mirror.renderAll(W);
    assertEquals(2, layer.markers.get(W).size());

    // full rebuild replaces stale markers
    mirror.replaceAll(W, List.of(road));
    assertEquals(Map.of("f_road", layer.markers.get(W).get("f_road")), layer.markers.get(W));
    mirror.renderAll("minecraft:the_end"); // unknown world: no-op
    assertFalse(layer.markers.containsKey("minecraft:the_end"));
  }
}
