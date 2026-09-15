package dev.nelsongx.map.fabric.command;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Builds {@code /mapedit} login links. Minecraft-free. */
// THREADING: pure static functions; any thread.
public final class EditorLinks {

  /** Redeem endpoint path. */
  public static final String REDEEM_PATH = "/api/auth/redeem";

  /**
   * A login link.
   *
   * @param uri the link
   * @param usedPublicUrl false when {@code http.publicUrl} was not configured and the bind address
   *     was used instead
   */
  public record Link(URI uri, boolean usedPublicUrl) {
  }

  private EditorLinks() {
  }

  /**
   * @param publicUrl normalized public base URL (see {@code HttpConfig.publicUrl}), or empty
   * @param bind HTTP bind host
   * @param port bound HTTP port
   * @param token login token (base64url)
   * @return {@code <base>/api/auth/redeem?token=<token>}
   */
  public static Link redeemLink(String publicUrl, String bind, int port, String token) {
    Objects.requireNonNull(publicUrl, "publicUrl");
    Objects.requireNonNull(token, "token");
    boolean usePublic = !publicUrl.isEmpty();
    String base = usePublic ? publicUrl : "http://" + fallbackHost(bind) + ":" + port;
    return new Link(URI.create(base + REDEEM_PATH + "?token=" + token), usePublic);
  }

  /** @return a host usable in a URL for the bind address; wildcard addresses become localhost */
  static String fallbackHost(String bind) {
    String b = bind == null ? "" : bind.trim();
    String lower = b.toLowerCase(Locale.ROOT);
    if (b.isEmpty() || lower.equals("0.0.0.0") || lower.equals("::") || lower.equals("[::]")
        || lower.equals("0:0:0:0:0:0:0:0") || lower.equals("*")) {
      return "localhost";
    }
    if (b.indexOf(':') >= 0 && !b.startsWith("[")) {
      return "[" + b + "]"; // IPv6 literal
    }
    return b;
  }
}
