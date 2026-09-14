package dev.nelsongx.nav.fabric.graph;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Maps a dimension id ({@code namespace:path}) to the relative location of its region-graph database:
 * {@code data/squaremap-pro/regions/<namespace>/<path>.sqlite}. Minecraft-free.
 *
 * <p>Only identifier characters are accepted (namespace {@code [a-z0-9_.-]}, path {@code [a-z0-9_.-/]},
 * matching {@code Identifier.validNamespaceChar/validPathChar}); empty, {@code .} and {@code ..}
 * segments, leading/trailing or doubled {@code /} are rejected, so the result can never escape the
 * regions directory.
 */
// THREADING: pure static functions; any thread.
public final class RegionStorePaths {

  private RegionStorePaths() {
  }

  /**
   * Relative database path for a dimension.
   *
   * @param namespace identifier namespace, e.g. {@code minecraft}
   * @param path identifier path, e.g. {@code overworld} or {@code my/dim}
   * @return relative path {@code data/squaremap-pro/regions/<namespace>/<path...>.sqlite}
   * @throws IllegalArgumentException on invalid characters or traversal segments
   */
  public static Path relativePath(String namespace, String path) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(path, "path");
    requireSegment(namespace, "namespace");
    if (path.isEmpty()) {
      throw new IllegalArgumentException("empty path");
    }
    String[] segments = path.split("/", -1);
    for (String seg : segments) {
      requireSegment(seg, "path");
    }
    Path p = Path.of("data", "squaremap-pro", "regions", namespace);
    for (int i = 0; i < segments.length - 1; i++) {
      p = p.resolve(segments[i]);
    }
    return p.resolve(segments[segments.length - 1] + ".sqlite");
  }

  private static void requireSegment(String seg, String what) {
    if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
      throw new IllegalArgumentException("invalid " + what + " segment: '" + seg + "'");
    }
    for (int i = 0; i < seg.length(); i++) {
      char c = seg.charAt(i);
      boolean ok = c == '_' || c == '-' || c == '.' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (!ok) {
        throw new IllegalArgumentException("invalid character '" + c + "' in " + what + ": " + seg);
      }
    }
  }
}
