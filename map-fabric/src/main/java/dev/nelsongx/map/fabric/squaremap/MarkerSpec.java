package dev.nelsongx.map.fabric.squaremap;

import dev.nelsongx.map.fabric.squaremap.MapGeometry.MapPoint;
import java.util.List;
import java.util.Objects;

/**
 * A squaremap marker described without squaremap types (so the feature → marker mapping is testable
 * and callers never load squaremap classes).
 *
 * @param shape marker shape
 * @param points polygon ring / polyline vertices, or the single circle centre (map-plane coordinates)
 * @param radius circle radius in blocks (0 for other shapes)
 * @param strokeRgb stroke colour {@code 0xRRGGBB}
 * @param strokeWeight stroke width in pixels
 * @param strokeOpacity stroke opacity 0..1
 * @param fill whether the shape is filled
 * @param fillRgb fill colour {@code 0xRRGGBB} (ignored unless {@code fill})
 * @param fillOpacity fill opacity 0..1
 * @param hoverTooltip HTML hover tooltip (already escaped)
 * @param clickTooltip HTML click popup (already escaped)
 */
// THREADING: immutable value; any thread.
public record MarkerSpec(Shape shape, List<MapPoint> points, double radius, int strokeRgb,
    int strokeWeight, double strokeOpacity, boolean fill, int fillRgb, double fillOpacity,
    String hoverTooltip, String clickTooltip) {

  /** Marker shapes used by the mirror. */
  public enum Shape {
    POLYGON, POLYLINE, CIRCLE
  }

  /** Validates and copies. */
  public MarkerSpec {
    Objects.requireNonNull(shape, "shape");
    points = List.copyOf(points);
    Objects.requireNonNull(hoverTooltip, "hoverTooltip");
    Objects.requireNonNull(clickTooltip, "clickTooltip");
  }
}
