package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory single-use login tokens issued by {@code /mapedit} and redeemed by the web.
 *
 * <p>Tokens are 256 random bits (base64url, no padding), expire {@link #DEFAULT_TTL} after issue, and
 * can be redeemed at most once. Issuing a token for a player invalidates that player's previous
 * unredeemed token. Expired tokens are purged lazily on every call. Tokens are never logged.
 */
// THREADING: all methods synchronized; issue() is called on the server thread (command), redeem() on
// HTTP/worker threads. O(outstanding tokens) per call, no I/O — cheap enough for the server thread.
public final class TokenStore {

  /** Token lifetime. */
  public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

  private record Entry(Actor actor, Instant expiresAt) {
  }

  private final Clock clock;
  private final Duration ttl;
  private final Map<String, Entry> byToken = new HashMap<>();
  private final Map<UUID, String> byPlayer = new HashMap<>();

  /** @param clock time source */
  public TokenStore(Clock clock) {
    this(clock, DEFAULT_TTL);
  }

  /**
   * @param clock time source
   * @param ttl token lifetime, &gt; 0
   */
  public TokenStore(Clock clock, Duration ttl) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("ttl must be > 0: " + ttl);
    }
  }

  /** @return the token lifetime */
  public Duration ttl() {
    return ttl;
  }

  /**
   * Issues a new token for {@code actor}, invalidating the player's previous unredeemed token.
   *
   * @param actor the player
   * @return the token
   */
  public synchronized String issue(Actor actor) {
    Objects.requireNonNull(actor, "actor");
    Instant now = clock.instant();
    purgeExpired(now);
    String previous = byPlayer.remove(actor.uuid());
    if (previous != null) {
      byToken.remove(previous);
    }
    String token;
    do {
      token = SecureIds.random256();
    } while (byToken.containsKey(token));
    byToken.put(token, new Entry(actor, now.plus(ttl)));
    byPlayer.put(actor.uuid(), token);
    return token;
  }

  /**
   * Consumes a token.
   *
   * @param token the token (may be null or malformed)
   * @return the actor it was issued to, or empty if unknown, already redeemed, replaced or expired
   */
  public synchronized Optional<Actor> redeem(String token) {
    Instant now = clock.instant();
    purgeExpired(now);
    if (!SecureIds.wellFormed(token)) {
      return Optional.empty();
    }
    Entry e = byToken.remove(token);
    if (e == null) {
      return Optional.empty();
    }
    byPlayer.remove(e.actor().uuid(), token);
    return Optional.of(e.actor());
  }

  /** @return number of outstanding (unexpired, unredeemed) tokens */
  public synchronized int size() {
    purgeExpired(clock.instant());
    return byToken.size();
  }

  private void purgeExpired(Instant now) {
    Iterator<Map.Entry<String, Entry>> it = byToken.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, Entry> e = it.next();
      if (!now.isBefore(e.getValue().expiresAt())) {
        it.remove();
        byPlayer.remove(e.getValue().actor().uuid(), e.getKey());
      }
    }
  }
}
