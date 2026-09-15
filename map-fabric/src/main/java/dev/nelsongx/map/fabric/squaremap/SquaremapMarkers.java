package dev.nelsongx.map.fabric.squaremap;

import dev.nelsongx.map.fabric.squaremap.MapGeometry.MapPoint;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

/**
 * Converts {@link MarkerSpec}s to squaremap markers. squaremap-api v1.3.12 signatures used:
 * {@code static Point Point.of(double x, double z)}, {@code static Key Key.of(String)} (characters
 * {@code [A-Za-z0-9_.-]}), {@code static Polygon Marker.polygon(List<Point>)},
 * {@code static Polyline Marker.polyline(List<Point>)},
 * {@code static Circle Marker.circle(Point center, double radius)},
 * {@code Marker markerOptions(MarkerOptions.Builder)} and {@code MarkerOptions.builder()} with
 * {@code strokeColor(java.awt.Color)}, {@code strokeWeight(int)}, {@code strokeOpacity(double)},
 * {@code fill(boolean)}, {@code fillColor(Color)}, {@code fillOpacity(double)},
 * {@code hoverTooltip(String)}, {@code clickTooltip(String)}.
 */
// THREADING: pure static functions; any thread. Only loaded when squaremap is installed.
public final class SquaremapMarkers {

  /** Prefix of every marker key. */
  static final String KEY_PREFIX = "squaremap-pro_";

  private SquaremapMarkers() {
  }

  /** @return marker key for a feature id (feature ids are {@code f_} + base62, valid key chars) */
  public static Key key(String featureId) {
    StringBuilder b = new StringBuilder(KEY_PREFIX.length() + featureId.length());
    b.append(KEY_PREFIX);
    for (int i = 0; i < featureId.length(); i++) {
      char c = featureId.charAt(i);
      boolean ok = c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z')
          || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
      b.append(ok ? c : '_');
    }
    return Key.of(b.toString());
  }

  /** @return the squaremap marker */
  public static Marker toMarker(MarkerSpec spec) {
    List<Point> points = new ArrayList<>(spec.points().size());
    for (MapPoint p : spec.points()) {
      points.add(Point.of(p.x(), p.z()));
    }
    Marker marker = switch (spec.shape()) {
      case POLYGON -> Marker.polygon(points);
      case POLYLINE -> Marker.polyline(points);
      case CIRCLE -> Marker.circle(points.get(0), spec.radius());
    };
    MarkerOptions.Builder options = MarkerOptions.builder()
        .stroke(true)
        .strokeColor(new Color(spec.strokeRgb()))
        .strokeWeight(spec.strokeWeight())
        .strokeOpacity(spec.strokeOpacity())
        .fill(spec.fill())
        .fillOpacity(spec.fill() ? spec.fillOpacity() : 0)
        .hoverTooltip(spec.hoverTooltip())
        .clickTooltip(spec.clickTooltip());
    if (spec.fill()) {
      options.fillColor(new Color(spec.fillRgb()));
    }
    return marker.markerOptions(options);
  }
}
