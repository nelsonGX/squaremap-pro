package dev.nelsongx.map.fabric.squaremap;

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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Pure feature → {@link MarkerSpec} mapping. Colours and widths follow the web viewer
 * ({@code web/lib/features/styles.ts}: {@code CATEGORY_COLOURS}, {@code ROAD_LOOKS},
 * {@code railwayStyle}, {@code stationStyle}, {@code UNKNOWN_RAILWAY_COLOUR}). squaremap markers
 * cannot express the web's casings or dash patterns ({@code MarkerOptions} v1.3.12 has no dash
 * option), so only the main stroke is mirrored.
 */
// THREADING: pure static functions; any thread.
public final class FeatureMarkers {

  /** Station circle radius in blocks. */
  public static final double STATION_RADIUS = 3;
  /** {@code UNKNOWN_RAILWAY_COLOUR} in styles.ts. */
  public static final int UNKNOWN_RAILWAY_RGB = 0x374151;

  private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

  private FeatureMarkers() {
  }

  /** @return {@code CATEGORY_COLOURS} in styles.ts */
  public static int categoryRgb(BuildingCategory category) {
    if (category == null) {
      return 0x98989f;
    }
    return switch (category) {
      case RESIDENTIAL -> 0xbf8a5c;
      case COMMERCIAL -> 0xe8912f;
      case PUBLIC -> 0x6d5fc7;
      case INDUSTRIAL -> 0x6a7d8c;
      case GOVERNMENT -> 0x5b7fa6;
      case EDUCATION -> 0xa9689b;
      case HEALTHCARE -> 0xc9645e;
      case RELIGIOUS -> 0xb39a5f;
      case FARM -> 0x7d9a55;
      case STORAGE -> 0x8b7a63;
      case LANDMARK -> 0x4f9a92;
      case OTHER -> 0x98989f;
    };
  }

  /** @return {@code ROAD_LOOKS[class].color} in styles.ts */
  public static int roadRgb(RoadClass roadClass) {
    if (roadClass == null) {
      return 0xffffff;
    }
    return switch (roadClass) {
      case HIGHWAY -> 0xf59e0b;
      case MAIN -> 0xfde68a;
      case STREET -> 0xffffff;
      case PATH -> 0xe7d7b1;
    };
  }

  /** @return {@code ROAD_LOOKS[class].weight} in styles.ts, rounded to whole pixels (path 2.5 → 3) */
  public static int roadWeight(RoadClass roadClass) {
    if (roadClass == null) {
      return 5;
    }
    return switch (roadClass) {
      case HIGHWAY -> 9;
      case MAIN -> 7;
      case STREET -> 5;
      case PATH -> 3;
    };
  }

  /** @return the colour of {@code #rrggbb}, or {@code fallback} if malformed */
  public static int parseRgb(String hex, int fallback) {
    if (hex == null || !HEX.matcher(hex).matches()) {
      return fallback;
    }
    return Integer.parseInt(hex.substring(1), 16);
  }

  /**
   * @param f feature
   * @param railways looks up a railway feature by id in the same world (for station colours); may
   *     return null
   * @return the marker
   */
  public static MarkerSpec toSpec(Feature f, Function<String, Feature> railways) {
    FeatureData d = f.data();
    List<MapPoint> points = new ArrayList<>();
    for (Vertex v : d.geometry()) {
      points.add(MapGeometry.blockCentre(v.x(), v.z()));
    }
    String name = displayName(f);
    String hover = MapGeometry.escapeHtml(name);
    String click = "<b>" + hover + "</b><br>" + MapGeometry.escapeHtml(subtitle(f, railways));
    return switch (d) {
      case BuildingData b -> {
        int c = categoryRgb(b.category());
        yield new MarkerSpec(Shape.POLYGON, points, 0, c, 2, 0.95, true, c, 0.4, hover, click);
      }
      case RoadData r -> new MarkerSpec(Shape.POLYLINE, points, 0, roadRgb(r.roadClass()),
          roadWeight(r.roadClass()), 1, false, 0, 0, hover, click);
      case RailwayData r -> new MarkerSpec(Shape.POLYLINE, points, 0,
          parseRgb(r.colour(), UNKNOWN_RAILWAY_RGB), 4, 1, false, 0, 0, hover, click);
      case StationData s -> {
        Feature railway = s.railwayId() == null ? null : railways.apply(s.railwayId());
        int c = railway != null && railway.data() instanceof RailwayData rd
            ? parseRgb(rd.colour(), UNKNOWN_RAILWAY_RGB)
            : UNKNOWN_RAILWAY_RGB;
        yield new MarkerSpec(Shape.CIRCLE, points, STATION_RADIUS, c, 3, 1, true, 0xffffff, 1,
            hover, click);
      }
    };
  }

  /** @return the name, or "Unnamed &lt;type&gt;" when blank (styles.ts {@code displayName}) */
  static String displayName(Feature f) {
    String n = f.data().name() == null ? "" : f.data().name().strip();
    return n.isEmpty() ? "Unnamed " + f.type().wireName() : n;
  }

  /** @return styles.ts {@code featureSubtitle} */
  static String subtitle(Feature f, Function<String, Feature> railways) {
    return switch (f.data()) {
      case BuildingData b -> categoryLabel(b.category()) + " building";
      case RoadData r -> roadLabel(r.roadClass());
      case RailwayData r -> "Railway";
      case StationData s -> {
        Feature railway = s.railwayId() == null ? null : railways.apply(s.railwayId());
        yield railway != null ? "Station · " + displayName(railway) : "Station";
      }
    };
  }

  private static String categoryLabel(BuildingCategory c) {
    if (c == null) {
      return "Other";
    }
    return switch (c) {
      case RESIDENTIAL -> "Residential";
      case COMMERCIAL -> "Commercial";
      case PUBLIC -> "Public";
      case INDUSTRIAL -> "Industrial";
      case GOVERNMENT -> "Government";
      case EDUCATION -> "Education";
      case HEALTHCARE -> "Healthcare";
      case RELIGIOUS -> "Religious";
      case FARM -> "Farm";
      case STORAGE -> "Storage";
      case LANDMARK -> "Landmark";
      case OTHER -> "Other";
    };
  }

  private static String roadLabel(RoadClass c) {
    if (c == null) {
      return "Road";
    }
    return switch (c) {
      case HIGHWAY -> "Highway";
      case MAIN -> "Main road";
      case STREET -> "Street";
      case PATH -> "Path";
    };
  }
}
