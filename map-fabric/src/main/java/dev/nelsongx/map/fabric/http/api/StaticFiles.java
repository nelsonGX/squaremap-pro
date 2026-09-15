package dev.nelsongx.map.fabric.http.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;

/**
 * Static file lookup for the bundled web export (classpath) and squaremap tiles (file system), with
 * path-traversal protection. Minecraft-free.
 */
// THREADING: stateless apart from the immutable ClassLoader/prefix; any thread. Lookups do blocking
// file/jar I/O and run on Jetty request threads (small files; never the server thread).
public final class StaticFiles {

  /** An opened file. */
  public record Opened(InputStream stream, String contentType) {
  }

  private StaticFiles() {
  }

  /**
   * Decodes and sanitizes a request sub-path into relative segments joined by {@code /}.
   *
   * @param rawPath raw (percent-encoded) path below the mount point, without leading slash
   * @return the clean relative path ("" for the root), or empty if it contains {@code ..}, {@code .},
   *     backslashes, NUL, or empty segments (other than a trailing slash)
   */
  public static Optional<String> cleanRelativePath(String rawPath) {
    String decoded;
    try {
      decoded = URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
    if (decoded.indexOf('\\') >= 0 || decoded.indexOf('\0') >= 0 || decoded.indexOf(':') >= 0) {
      return Optional.empty();
    }
    boolean trailingSlash = decoded.endsWith("/");
    String body = trailingSlash ? decoded.substring(0, decoded.length() - 1) : decoded;
    if (body.isEmpty()) {
      return Optional.of(trailingSlash || decoded.isEmpty() ? "" : body);
    }
    for (String seg : body.split("/", -1)) {
      if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) {
        return Optional.empty();
      }
    }
    return Optional.of(decoded);
  }

  /**
   * Opens a file below {@code root}, refusing anything that resolves outside it (including via
   * symbolic links).
   *
   * @param root base directory
   * @param relative cleaned relative path (see {@link #cleanRelativePath})
   * @return the opened regular file, or empty
   */
  public static Optional<Opened> openFile(Path root, String relative) {
    if (root == null || relative.isEmpty() || relative.endsWith("/")) {
      return Optional.empty();
    }
    try {
      Path base = root.toAbsolutePath().normalize();
      Path file = base.resolve(relative).normalize();
      if (!file.startsWith(base) || !Files.isRegularFile(file)) {
        return Optional.empty();
      }
      if (!file.toRealPath().startsWith(base.toRealPath())) {
        return Optional.empty();
      }
      return Optional.of(new Opened(Files.newInputStream(file), contentType(relative)));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Opens a classpath resource {@code prefix + relative}.
   *
   * @param loader class loader
   * @param prefix resource prefix ending in {@code /}, e.g. {@code web/}
   * @param relative cleaned relative path
   * @return the opened resource if it exists and is not a directory
   */
  public static Optional<Opened> openResource(ClassLoader loader, String prefix, String relative) {
    if (relative.isEmpty() || relative.endsWith("/")) {
      return Optional.empty();
    }
    URL url = loader.getResource(prefix + relative);
    if (url == null) {
      return Optional.empty();
    }
    try {
      if ("file".equals(url.getProtocol()) && Files.isDirectory(Paths.get(url.toURI()))) {
        return Optional.empty();
      }
      if ("jar".equals(url.getProtocol()) && loader.getResource(prefix + relative + "/") != null) {
        return Optional.empty();
      }
      return Optional.of(new Opened(url.openStream(), contentType(relative)));
    } catch (IOException | URISyntaxException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /** @return a content type for a file name */
  public static String contentType(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    int dot = n.lastIndexOf('.');
    String ext = dot < 0 ? "" : n.substring(dot + 1);
    return switch (ext) {
      case "html", "htm" -> "text/html; charset=utf-8";
      case "js", "mjs" -> "text/javascript; charset=utf-8";
      case "css" -> "text/css; charset=utf-8";
      case "json", "map" -> "application/json; charset=utf-8";
      case "txt" -> "text/plain; charset=utf-8";
      case "svg" -> "image/svg+xml";
      case "png" -> "image/png";
      case "jpg", "jpeg" -> "image/jpeg";
      case "gif" -> "image/gif";
      case "webp" -> "image/webp";
      case "ico" -> "image/x-icon";
      case "woff" -> "font/woff";
      case "woff2" -> "font/woff2";
      case "ttf" -> "font/ttf";
      case "wasm" -> "application/wasm";
      case "webmanifest" -> "application/manifest+json";
      default -> "application/octet-stream";
    };
  }
}
