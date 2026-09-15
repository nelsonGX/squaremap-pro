package dev.nelsongx.map.fabric.http;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP server and web-auth settings, read from {@code config/squaremap-pro.properties}. Minecraft-free.
 *
 * @param enabled whether the HTTP server starts at all
 * @param bind host/interface to bind (e.g. {@code 127.0.0.1})
 * @param port TCP port, 0..65535 (0 = ephemeral, for tests)
 * @param corsOrigins allowed CORS origins ({@code *} allows any); empty = no CORS headers
 * @param timeoutMs per-request timeout for asynchronous handlers in milliseconds, &gt; 0
 * @param maxConcurrent maximum concurrently served asynchronous requests, &gt; 0
 * @param publicUrl externally reachable base URL used in {@code /mapedit} links (e.g.
 *     {@code https://map.example.com}), lowercase scheme, no trailing slash; empty = not configured
 * @param sessionTtlHours editor session lifetime in hours, 1..{@value #MAX_SESSION_TTL_HOURS}
 * @param cookieSecure whether the session cookie gets the {@code Secure} attribute (when not set in
 *     the file: whether {@code publicUrl} is https)
 */
// THREADING: immutable value, read from any thread. load() does file I/O and is called only on the
// "squaremap-pro-http-lifecycle" thread (HttpLifecycle) or a test thread, never on the server thread.
public record HttpConfig(boolean enabled, String bind, int port, List<String> corsOrigins,
    long timeoutMs, int maxConcurrent, String publicUrl, int sessionTtlHours, boolean cookieSecure) {

  /** Config file name inside the Fabric config directory. */
  public static final String FILE_NAME = "squaremap-pro.properties";

  /** Default {@link #sessionTtlHours()}: 7 days. */
  public static final int DEFAULT_SESSION_TTL_HOURS = 168;
  /** Upper bound for {@link #sessionTtlHours()}: one year. */
  public static final int MAX_SESSION_TTL_HOURS = 8760;

  private static final Logger LOGGER = LoggerFactory.getLogger("squaremap-pro");

  static final String K_ENABLED = "http.enabled";
  static final String K_BIND = "http.bind";
  static final String K_PORT = "http.port";
  static final String K_CORS = "http.cors.origins";
  static final String K_TIMEOUT = "http.timeoutMs";
  static final String K_MAX_CONCURRENT = "http.maxConcurrent";
  static final String K_PUBLIC_URL = "http.publicUrl";
  static final String K_SESSION_TTL = "auth.sessionTtlHours";
  static final String K_COOKIE_SECURE = "auth.cookieSecure";

  /** Every key, in file order. */
  static final List<String> KEYS = List.of(K_ENABLED, K_BIND, K_PORT, K_CORS, K_TIMEOUT,
      K_MAX_CONCURRENT, K_PUBLIC_URL, K_SESSION_TTL, K_COOKIE_SECURE);

  /** Validates and copies. */
  public HttpConfig {
    Objects.requireNonNull(bind, "bind");
    Objects.requireNonNull(publicUrl, "publicUrl");
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
    if (!publicUrl.isEmpty() && !publicUrl.equals(normalizePublicUrl(publicUrl))) {
      throw new IllegalArgumentException("invalid or non-normalized publicUrl: " + publicUrl);
    }
    if (sessionTtlHours <= 0 || sessionTtlHours > MAX_SESSION_TTL_HOURS) {
      throw new IllegalArgumentException("sessionTtlHours out of range: " + sessionTtlHours);
    }
  }

  /**
   * Config with the given HTTP settings and default public URL and auth settings.
   *
   * @param enabled see record
   * @param bind see record
   * @param port see record
   * @param corsOrigins see record
   * @param timeoutMs see record
   * @param maxConcurrent see record
   */
  public HttpConfig(boolean enabled, String bind, int port, List<String> corsOrigins, long timeoutMs,
      int maxConcurrent) {
    this(enabled, bind, port, corsOrigins, timeoutMs, maxConcurrent, "", DEFAULT_SESSION_TTL_HOURS,
        false);
  }

  /** @return the documented defaults */
  public static HttpConfig defaults() {
    return new HttpConfig(true, "127.0.0.1", 8765, List.of("http://localhost:3000"), 10_000, 16);
  }

  /** @return {@link #sessionTtlHours()} as a duration */
  public Duration sessionTtl() {
    return Duration.ofHours(sessionTtlHours);
  }

  /**
   * Loads the config file, writing a default file if it does not exist and appending defaults for
   * settings missing from an existing file. Never throws: I/O errors are logged and defaults are
   * used. Blocking file I/O — call off the server thread.
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
      String missing = missingKeysContent(props);
      if (!missing.isEmpty()) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
            StandardOpenOption.APPEND)) {
          w.write(missing);
        }
        LOGGER.info("added default values for new settings to {}", file);
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
    boolean enabled = parseBoolean(props, K_ENABLED, d.enabled(), warn);

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

    String publicUrl = d.publicUrl();
    String rawPublicUrl = trimmed(props, K_PUBLIC_URL);
    if (rawPublicUrl != null && !rawPublicUrl.isEmpty()) {
      String normalized = normalizePublicUrl(rawPublicUrl);
      if (normalized != null) {
        publicUrl = normalized;
      } else {
        warn.accept(invalid(K_PUBLIC_URL, rawPublicUrl,
            "an http(s)://host[:port][/path] URL without query or fragment", "(empty)"));
      }
    }

    int sessionTtlHours = (int) parseLong(props, K_SESSION_TTL, 1, MAX_SESSION_TTL_HOURS,
        d.sessionTtlHours(), warn);

    // Empty or absent auth.cookieSecure = derived from the public URL scheme.
    boolean cookieSecure = parseBoolean(props, K_COOKIE_SECURE, publicUrl.startsWith("https://"),
        warn);

    return new HttpConfig(enabled, bind, port, origins, timeoutMs, maxConcurrent, publicUrl,
        sessionTtlHours, cookieSecure);
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

  /**
   * Validates a public base URL: {@code http(s)://host[:port][/path]}, no user info, query or
   * fragment. Trailing slashes are stripped and the scheme lower-cased.
   *
   * @param raw URL text
   * @return the normalized URL, or null if invalid
   */
  static String normalizePublicUrl(String raw) {
    String s = raw.trim();
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.chars().anyMatch(Character::isWhitespace)) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(s);
    } catch (URISyntaxException e) {
      return null;
    }
    String scheme = uri.getScheme();
    boolean ok = scheme != null
        && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
        && uri.getHost() != null && !uri.getHost().isEmpty()
        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null;
    if (!ok) {
      return null;
    }
    return scheme.toLowerCase(Locale.ROOT) + s.substring(scheme.length());
  }

  private static boolean parseBoolean(Properties props, String key, boolean def,
      Consumer<String> warn) {
    String raw = trimmed(props, key);
    if (raw == null || (raw.isEmpty() && key.equals(K_COOKIE_SECURE))) {
      return def;
    }
    if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
      return Boolean.parseBoolean(raw.toLowerCase(Locale.ROOT));
    }
    warn.accept(invalid(key, raw, "true or false", def));
    return def;
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

  /** @return the content of a freshly written config file */
  static String defaultFileContent() {
    StringBuilder b = new StringBuilder("# squaremap-pro HTTP server (web map and API)\n");
    for (String key : KEYS) {
      b.append(defaultEntry(key));
    }
    return b.toString();
  }

  /** @return default entries for keys absent from {@code props}, or "" if none is missing */
  static String missingKeysContent(Properties props) {
    StringBuilder b = new StringBuilder();
    for (String key : KEYS) {
      if (!props.containsKey(key)) {
        b.append(defaultEntry(key));
      }
    }
    return b.isEmpty() ? "" : "\n# settings added by a newer squaremap-pro version\n" + b;
  }

  private static String defaultEntry(String key) {
    HttpConfig d = defaults();
    return switch (key) {
      case K_ENABLED -> K_ENABLED + "=" + d.enabled() + "\n";
      case K_BIND -> "# interface to bind; 127.0.0.1 = local only, 0.0.0.0 = all interfaces\n"
          + K_BIND + "=" + d.bind() + "\n";
      case K_PORT -> K_PORT + "=" + d.port() + "\n";
      case K_CORS -> "# comma-separated allowed CORS origins; * allows any origin\n"
          + K_CORS + "=" + String.join(",", d.corsOrigins()) + "\n";
      case K_TIMEOUT -> "# per-request timeout in milliseconds\n"
          + K_TIMEOUT + "=" + d.timeoutMs() + "\n";
      case K_MAX_CONCURRENT -> "# maximum concurrently served asynchronous requests (excess get HTTP 503)\n"
          + K_MAX_CONCURRENT + "=" + d.maxConcurrent() + "\n";
      case K_PUBLIC_URL -> "# public base URL of the web map used in /mapedit links, e.g. https://map.example.com\n"
          + "# empty = http://<bind address, or localhost>:<port>\n"
          + K_PUBLIC_URL + "=" + d.publicUrl() + "\n";
      case K_SESSION_TTL -> "# web editor session lifetime in hours (1.." + MAX_SESSION_TTL_HOURS + ")\n"
          + K_SESSION_TTL + "=" + d.sessionTtlHours() + "\n";
      case K_COOKIE_SECURE -> "# Secure attribute on the session cookie (true/false); empty = true iff "
          + K_PUBLIC_URL + " is https\n"
          + K_COOKIE_SECURE + "=\n";
      default -> throw new IllegalArgumentException(key);
    };
  }
}
