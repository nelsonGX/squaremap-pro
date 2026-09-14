package dev.nelsongx.nav.fabric.squaremap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.nav.core.GridPos;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapGeometryTest {

  @Test
  void pointsAreBlockCentres() {
    List<MapGeometry.MapPoint> pts = MapGeometry.toMapPoints(List.of(
        new GridPos(0, 64, 0), new GridPos(-1, 70, -16), new GridPos(310, 71, 95)));
    assertEquals(List.of(
        new MapGeometry.MapPoint(0.5, 0.5),
        new MapGeometry.MapPoint(-0.5, -15.5),
        new MapGeometry.MapPoint(310.5, 95.5)), pts);
  }

  @Test
  void markerKeyUsesOnlyValidSquaremapKeyChars() {
    UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    String key = MapGeometry.markerKey(id);
    assertEquals("route_123e4567-e89b-12d3-a456-426614174000", key);
    assertTrue(key.chars().allMatch(c -> c == '_' || c == '-' || c == '.'
        || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')));
  }

  @Test
  void tooltipIsEscaped() {
    assertEquals("Steve: 343 blocks", MapGeometry.tooltip("Steve", 342.7));
    assertEquals("&lt;b&gt;&amp;: 0 blocks", MapGeometry.tooltip("<b>&", 0));
  }
}
