package dev.nelsongx.map.fabric.squaremap;

/** Pure helpers for turning map content into squaremap markers. Free of Minecraft and squaremap types. */
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
   * @param x block x
   * @param z block z
   * @return the block-centre map point {@code (x + 0.5, z + 0.5)}
   */
  public static MapPoint blockCentre(int x, int z) {
    return new MapPoint(x + 0.5, z + 0.5);
  }

  /**
   * HTML-escapes text (squaremap renders tooltips and popups as HTML).
   *
   * @param s raw text
   * @return escaped text
   */
  public static String escapeHtml(String s) {
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
