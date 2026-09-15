package dev.nelsongx.map.fabric.squaremap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MapGeometryTest {

  @Test
  void pointsAreBlockCentres() {
    assertEquals(new MapGeometry.MapPoint(0.5, 0.5), MapGeometry.blockCentre(0, 0));
    assertEquals(new MapGeometry.MapPoint(-0.5, -15.5), MapGeometry.blockCentre(-1, -16));
  }

  @Test
  void htmlIsEscaped() {
    assertEquals("Steve", MapGeometry.escapeHtml("Steve"));
    assertEquals("&lt;b&gt;&amp;&quot;&#39;", MapGeometry.escapeHtml("<b>&\"'"));
  }
}
