package dev.nelsongx.nav.fabric.squaremap;

import dev.nelsongx.nav.core.GridPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Pure helpers for turning routes into map markers. Free of Minecraft and squaremap types. */
// THREADING: pure static functions; any thread.
public final class MapGeometry {

  /**
   * A map-plane point.
   *
   * @param x world x
   * @param z world z
   */
  public record MapPoint(double x, double z) {
  }

  private MapGeometry() {
  }

  /**
   * Block coordinates → block-centre map points {@code (x + 0.5, z + 0.5)}; Y is dropped.
   *
   * @param points route points
   * @return map points in the same order
   */
  public static List<MapPoint> toMapPoints(List<GridPos> points) {
    List<MapPoint> out = new ArrayList<>(points.size());
    for (GridPos p : points) {
      out.add(new MapPoint(p.x() + 0.5, p.z() + 0.5));
    }
    return out;
  }

  /**
   * @param player player id
   * @return marker key {@code route_<uuid>} (only {@code [a-zA-Z0-9._-]} characters)
   */
  public static String markerKey(UUID player) {
    return "route_" + player;
  }

  /**
   * Hover tooltip {@code "<playerName>: N blocks"}, HTML-escaped (squaremap renders tooltips as HTML).
   *
   * @param playerName player name
   * @param distance distance in blocks
   * @return tooltip HTML
   */
  public static String tooltip(String playerName, double distance) {
    return escapeHtml(playerName) + ": " + String.format(Locale.ROOT, "%d", Math.round(distance))
        + " blocks";
  }

  static String escapeHtml(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '<' -> b.append("&lt;");
        case '>' -> b.append("&gt;");
        case '&' -> b.append("&amp;");
        case '"' -> b.append("&quot;");
        case '\'' -> b.append("&#39;");
        default -> b.append(c);
      }
    }
    return b.toString();
  }
}
