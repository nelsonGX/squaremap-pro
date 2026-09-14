package dev.nelsongx.nav.fabric.http;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP endpoint settings, read from {@code config/squaremap-pro.properties}. Minecraft-free.
 *
 * @param enabled whether the HTTP server starts at all
 * @param bind host/interface to bind (e.g. {@code 127.0.0.1})
 * @param port TCP port, 0..65535 (0 = ephemeral, for tests)
 * @param corsOrigins allowed CORS origins ({@code *} allows any); empty = no CORS headers
 * @param timeoutMs per-request route timeout in milliseconds, &gt; 0
 * @param maxConcurrent maximum concurrently served route requests, &gt; 0
 */
// THREADING: immutable value. load() does file I/O and is called only on the dedicated
// "squaremap-pro-http" lifecycle thread (RouteHttpLifecycle), never on the server thread.
public record HttpConfig(boolean enabled, String bind, int port, List<String> corsOrigins,
    long timeoutMs, int maxConcurrent) {

  /** Config file name inside the Fabric config directory. */
  public static final String FILE_NAME = "squaremap-pro.properties";

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  static final String K_ENABLED = "http.enabled";
  static final String K_BIND = "http.bind";
  static final String K_PORT = "http.port";
  static final String K_CORS = "http.cors.origins";
  static final String K_TIMEOUT = "http.timeoutMs";
  static final String K_MAX_CONCURRENT = "http.maxConcurrent";

  /** Validates and copies. */
  public HttpConfig {
    Objects.requireNonNull(bind, "bind");
    corsOrigins = List.copyOf(corsOrigins);
    if (bind.isBlank()) {
      throw new IllegalArgumentException("bind must not be blank");
    }
    if (port < 0 || port > 65535) {
      throw new IllegalArgumentException("port out of range: " + port);
    }
    if (timeoutMs <= 0) {
      throw new IllegalArgumentException("timeoutMs must be > 0: " + timeoutMs);
    }
    if (maxConcurrent <= 0) {
      throw new IllegalArgumentException("maxConcurrent must be > 0: " + maxConcurrent);
    }
  }

  /** @return the documented defaults */
  public static HttpConfig defaults() {
    return new HttpConfig(true, "127.0.0.1", 8765, List.of("http://localhost:3000"), 10_000, 16);
  }

  /**
   * Loads the config file, writing a default file if it does not exist. Never throws: I/O errors
   * are logged and defaults are used. Blocking file I/O — call off the server thread.
   *
   * @param configDir Fabric config directory
   * @return the config
   */
  public static HttpConfig load(Path configDir) {
    HttpConfig defaults = defaults();
    Path file = configDir.resolve(FILE_NAME);
    try {
      if (!Files.exists(file)) {
        Files.createDirectories(configDir);
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
          w.write(defaultFileContent());
        }
        LOGGER.info("created default HTTP config at {}", file);
        return defaults;
      }
      Properties props = new Properties();
      try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
        props.load(r);
      }
      return parse(props, msg -> LOGGER.warn("{}: {}", file, msg));
    } catch (IOException | RuntimeException e) {
      LOGGER.error("failed to read HTTP config {}; using defaults", file, e);
      return defaults;
    }
  }

  /**
   * Parses properties. Each missing or invalid value falls back to its default; invalid values are
   * reported through {@code warn}. Pure apart from {@code warn}.
   *
   * @param props properties
   * @param warn receives one message per invalid value
   * @return the config
   */
  public static HttpConfig parse(Properties props, Consumer<String> warn) {
    HttpConfig d = defaults();
    boolean enabled = d.enabled();
    String rawEnabled = trimmed(props, K_ENABLED);
    if (rawEnabled != null) {
      if (rawEnabled.equalsIgnoreCase("true") || rawEnabled.equalsIgnoreCase("false")) {
        enabled = Boolean.parseBoolean(rawEnabled.toLowerCase(Locale.ROOT));
      } else {
        warn.accept(invalid(K_ENABLED, rawEnabled, "true or false", d.enabled()));
      }
    }

    String bind = d.bind();
    String rawBind = trimmed(props, K_BIND);
    if (rawBind != null) {
      if (!rawBind.isEmpty() && rawBind.chars().noneMatch(Character::isWhitespace)) {
        bind = rawBind;
      } else {
        warn.accept(invalid(K_BIND, rawBind, "a host name or IP address", d.bind()));
      }
    }

    int port = (int) parseLong(props, K_PORT, 0, 65535, d.port(), warn);
    long timeoutMs = parseLong(props, K_TIMEOUT, 1, 600_000, d.timeoutMs(), warn);
    int maxConcurrent = (int) parseLong(props, K_MAX_CONCURRENT, 1, 10_000, d.maxConcurrent(),
        warn);

    List<String> origins = d.corsOrigins();
    String rawCors = props.getProperty(K_CORS);
    if (rawCors != null) {
      List<String> parsed = parseOrigins(rawCors);
      if (parsed != null) {
        origins = parsed;
      } else {
        warn.accept(invalid(K_CORS, rawCors,
            "a comma-separated list of http(s)://host[:port] origins or *", d.corsOrigins()));
      }
    }
    return new HttpConfig(enabled, bind, port, origins, timeoutMs, maxConcurrent);
  }

  /** @return parsed origins, or null if any entry is invalid */
  static List<String> parseOrigins(String raw) {
    List<String> out = new ArrayList<>();
    for (String part : raw.split(",", -1)) {
      String o = part.trim();
      if (o.isEmpty()) {
        if (raw.isBlank()) {
          continue; // empty value = no CORS
        }
        return null;
      }
      boolean valid = o.equals("*")
          || o.matches("https?://[A-Za-z0-9.\\-\\[\\]:]+(:[0-9]{1,5})?");
      if (!valid) {
        return null;
      }
      out.add(o);
    }
    return List.copyOf(out);
  }

  private static long parseLong(Properties props, String key, long min, long max, long def,
      Consumer<String> warn) {
    String raw = trimmed(props, key);
    if (raw == null) {
      return def;
    }
    try {
      long v = Long.parseLong(raw);
      if (v >= min && v <= max) {
        return v;
      }
    } catch (NumberFormatException ignored) {
      // fall through
    }
    warn.accept(invalid(key, raw, "an integer in [" + min + ", " + max + "]", def));
    return def;
  }

  private static String trimmed(Properties props, String key) {
    String v = props.getProperty(key);
    return v == null ? null : v.trim();
  }

  private static String invalid(String key, String raw, String expected, Object def) {
    return "invalid " + key + "=\"" + raw + "\" (expected " + expected + "); using default " + def;
  }

  private static String defaultFileContent() {
    HttpConfig d = defaults();
    return "# squaremap-pro HTTP route endpoint (GET /route, GET /health)\n"
        + K_ENABLED + "=" + d.enabled() + "\n"
        + "# interface to bind; 127.0.0.1 = local only, 0.0.0.0 = all interfaces\n"
        + K_BIND + "=" + d.bind() + "\n"
        + K_PORT + "=" + d.port() + "\n"
        + "# comma-separated allowed CORS origins; * allows any origin\n"
        + K_CORS + "=" + String.join(",", d.corsOrigins()) + "\n"
        + "# per-request route timeout in milliseconds\n"
        + K_TIMEOUT + "=" + d.timeoutMs() + "\n"
        + "# maximum concurrently served route requests (excess get HTTP 503)\n"
        + K_MAX_CONCURRENT + "=" + d.maxConcurrent() + "\n";
  }
}
