package dev.nelsongx.map.fabric.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random identifiers for login tokens and session ids, and their hashes. Minecraft-free. */
// THREADING: stateless apart from a SecureRandom (thread-safe); any thread.
final class SecureIds {

  /** Length of a 256-bit value encoded as base64url without padding. */
  static final int ENCODED_LENGTH = 43;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

  private SecureIds() {
  }

  /** @return 256 random bits, base64url without padding (43 chars) */
  static String random256() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  /** @return whether {@code s} has the shape produced by {@link #random256()} */
  static boolean wellFormed(String s) {
    if (s == null || s.length() != ENCODED_LENGTH) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
          || c == '-' || c == '_';
      if (!ok) {
        return false;
      }
    }
    return true;
  }

  /** @return lowercase hex SHA-256 of the UTF-8 bytes of {@code s} */
  static String sha256Hex(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
