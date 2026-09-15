package dev.nelsongx.map.fabric.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EditorLinksTest {

  private static final String TOKEN = "abcDEF123_-abcDEF123_-abcDEF123_-abcDEF1234";

  @Test
  void usesPublicUrlWhenConfigured() {
    EditorLinks.Link link = EditorLinks.redeemLink("https://map.example.com/sub", "0.0.0.0", 8765,
        TOKEN);
    assertTrue(link.usedPublicUrl());
    assertEquals("https://map.example.com/sub/api/auth/redeem?token=" + TOKEN, link.uri().toString());
  }

  @Test
  void fallsBackToBindAddress() {
    EditorLinks.Link link = EditorLinks.redeemLink("", "127.0.0.1", 8765, TOKEN);
    assertFalse(link.usedPublicUrl());
    assertEquals("http://127.0.0.1:8765/api/auth/redeem?token=" + TOKEN, link.uri().toString());
  }

  @Test
  void wildcardAndIpv6Hosts() {
    assertEquals("localhost", EditorLinks.fallbackHost("0.0.0.0"));
    assertEquals("localhost", EditorLinks.fallbackHost("::"));
    assertEquals("[::1]", EditorLinks.fallbackHost("::1"));
    assertEquals("map.local", EditorLinks.fallbackHost("map.local"));
    assertEquals("http://localhost:9000/api/auth/redeem?token=" + TOKEN,
        EditorLinks.redeemLink("", "0.0.0.0", 9000, TOKEN).uri().toString());
  }
}
