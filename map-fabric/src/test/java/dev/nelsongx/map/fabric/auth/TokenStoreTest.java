package dev.nelsongx.map.fabric.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nelsongx.map.core.feature.Actor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenStoreTest {

  private static final Actor STEVE = new Actor(UUID.fromString("00000000-0000-0000-0000-000000000001"),
      "Steve");
  private static final Actor ALEX = new Actor(UUID.fromString("00000000-0000-0000-0000-000000000002"),
      "Alex");

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T10:00:00Z"));
  private final TokenStore store = new TokenStore(clock);

  @Test
  void tokenIs256BitBase64UrlWithoutPadding() {
    String token = store.issue(STEVE);
    assertEquals(43, token.length());
    assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
    assertEquals(32, Base64.getUrlDecoder().decode(token).length);
    assertNotEquals(token, store.issue(ALEX));
    assertEquals(Duration.ofMinutes(5), store.ttl());
  }

  @Test
  void redeemIsSingleUse() {
    String token = store.issue(STEVE);
    assertEquals(Optional.of(STEVE), store.redeem(token));
    assertEquals(Optional.empty(), store.redeem(token));
    assertEquals(0, store.size());
  }

  @Test
  void unknownAndMalformedTokensAreRejected() {
    store.issue(STEVE);
    assertEquals(Optional.empty(), store.redeem(null));
    assertEquals(Optional.empty(), store.redeem(""));
    assertEquals(Optional.empty(), store.redeem("not a token"));
    assertEquals(Optional.empty(), store.redeem(SecureIds.random256()));
    assertEquals(1, store.size());
  }

  @Test
  void tokenExpiresAfterFiveMinutes() {
    String early = store.issue(STEVE);
    String exact = store.issue(ALEX);
    clock.advance(Duration.ofMinutes(5).minusMillis(1));
    assertEquals(Optional.of(STEVE), store.redeem(early), "still valid just before expiry");
    clock.advance(Duration.ofMillis(1));
    assertEquals(Optional.empty(), store.redeem(exact), "expired at exactly 5 minutes");
  }

  @Test
  void expiredTokensArePurgedLazily() {
    store.issue(STEVE);
    store.issue(ALEX);
    assertEquals(2, store.size());
    clock.advance(Duration.ofMinutes(6));
    assertEquals(0, store.size());
    // After purge a new token for the same player works normally.
    String fresh = store.issue(STEVE);
    assertEquals(Optional.of(STEVE), store.redeem(fresh));
  }

  @Test
  void reissueInvalidatesPreviousTokenOfSamePlayerOnly() {
    String steve1 = store.issue(STEVE);
    String alex = store.issue(ALEX);
    String steve2 = store.issue(STEVE);
    assertEquals(Optional.empty(), store.redeem(steve1));
    assertEquals(Optional.of(STEVE), store.redeem(steve2));
    assertEquals(Optional.of(ALEX), store.redeem(alex));
  }

  @Test
  void redeemedTokenDoesNotAffectLaterIssue() {
    String first = store.issue(STEVE);
    assertEquals(Optional.of(STEVE), store.redeem(first));
    String second = store.issue(STEVE);
    assertEquals(Optional.of(STEVE), store.redeem(second));
  }

  @Test
  void concurrentRedeemSucceedsExactlyOncePerToken() throws Exception {
    int players = 200;
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < players; i++) {
      tokens.add(store.issue(new Actor(new UUID(0, i), "p" + i)));
    }
    Set<String> unique = new HashSet<>(tokens);
    assertEquals(players, unique.size());

    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      AtomicInteger successes = new AtomicInteger();
      Set<UUID> redeemedBy = ConcurrentHashMap.newKeySet();
      List<Future<?>> futures = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        futures.add(pool.submit(() -> {
          start.await();
          for (String token : tokens) {
            store.redeem(token).ifPresent(a -> {
              successes.incrementAndGet();
              redeemedBy.add(a.uuid());
            });
            store.issue(new Actor(UUID.randomUUID(), "noise")); // concurrent issues
          }
          return null;
        }));
      }
      start.countDown();
      for (Future<?> f : futures) {
        f.get(30, TimeUnit.SECONDS);
      }
      assertEquals(players, successes.get(), "each token redeemed exactly once");
      assertEquals(players, redeemedBy.size());
      assertEquals(threads * players, store.size(), "noise tokens all outstanding");
    } finally {
      pool.shutdownNow();
    }
  }
}
