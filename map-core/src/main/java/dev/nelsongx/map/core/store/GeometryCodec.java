package dev.nelsongx.map.core.store;

import dev.nelsongx.map.core.feature.Vertex;
import java.util.ArrayList;
import java.util.List;

/**
 * Exact text encoding for vertex lists: {@code "x,z;x,z;..."} with decimal integers.
 *
 * <p>THREADING: stateless, any thread.
 */
final class GeometryCodec {
  private GeometryCodec() {}

  static String encode(List<Vertex> vertices) {
    StringBuilder sb = new StringBuilder(vertices.size() * 12);
    for (int i = 0; i < vertices.size(); i++) {
      if (i > 0) {
        sb.append(';');
      }
      Vertex v = vertices.get(i);
      sb.append(v.x()).append(',').append(v.z());
    }
    return sb.toString();
  }

  static List<Vertex> decode(String text) {
    if (text == null || text.isEmpty()) {
      throw new FeatureStoreException("corrupt geometry: empty");
    }
    List<Vertex> out = new ArrayList<>();
    for (String pair : text.split(";", -1)) {
      int comma = pair.indexOf(',');
      if (comma < 0) {
        throw new FeatureStoreException("corrupt geometry: " + text);
      }
      try {
        out.add(
            new Vertex(
                Integer.parseInt(pair, 0, comma, 10),
                Integer.parseInt(pair, comma + 1, pair.length(), 10)));
      } catch (NumberFormatException e) {
        throw new FeatureStoreException("corrupt geometry: " + text, e);
      }
    }
    return List.copyOf(out);
  }
}
