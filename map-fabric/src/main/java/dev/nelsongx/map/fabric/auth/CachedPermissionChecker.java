package dev.nelsongx.map.fabric.auth;

import dev.nelsongx.map.core.feature.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches successful {@link PermissionChecker} decisions per player for a short time, so that
 * {@code /api/auth/me} and bursts of edits do not hop to the server thread for every request. Failed
 * checks are not cached. A revoked permission takes effect after at most {@link #DEFAULT_TTL}.
 */
// THREADING: any thread; ConcurrentHashMap of immutable entries, never blocks.
public final class CachedPermissionChecker implements PermissionChecker {

  /** Cache lifetime of a decision. */
  public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

  private record Entry(boolean allowed, Instant expiresAt) {
  }

  private final PermissionChecker delegate;
  private final Clock clock;
  private final Duration ttl;
  private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();

  /**
   * @param delegate the real checker
   * @param clock time source
   * @param ttl cache lifetime
   */
  public CachedPermissionChecker(PermissionChecker delegate, Clock clock, Duration ttl) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ttl = Objects.requireNonNull(ttl, "ttl");
  }

  @Override
  public CompletableFuture<Boolean> canEdit(Actor actor) {
    Instant now = clock.instant();
    Entry e = cache.get(actor.uuid());
    if (e != null && now.isBefore(e.expiresAt())) {
      return CompletableFuture.completedFuture(e.allowed());
    }
    return delegate.canEdit(actor).thenApply(allowed -> {
      cache.put(actor.uuid(), new Entry(allowed, clock.instant().plus(ttl)));
      if (cache.size() > 10_000) {
        Instant t = clock.instant();
        cache.values().removeIf(x -> !t.isBefore(x.expiresAt()));
      }
      return allowed;
    });
  }

  /**
   * Fresh check that bypasses (and refreshes) the cache.
   *
   * @param actor player
   * @return decision
   */
  public CompletableFuture<Boolean> canEditUncached(Actor actor) {
    cache.remove(actor.uuid());
    return canEdit(actor);
  }
}
