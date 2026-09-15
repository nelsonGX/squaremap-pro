package dev.nelsongx.map.fabric.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpConfigTest {

  @Test
  void missingFileCreatesDefaults(@TempDir Path dir) {
    assertEquals(HttpConfig.defaults(), HttpConfig.load(dir));
    assertTrue(Files.exists(dir.resolve(HttpConfig.FILE_NAME)));
    assertEquals(HttpConfig.defaults(), HttpConfig.load(dir), "written file round-trips");
  }

  @Test
  void validValuesParsed() {
    Properties p = new Properties();
    p.setProperty("http.enabled", "false");
    p.setProperty("http.bind", "0.0.0.0");
    p.setProperty("http.port", "9000");
    p.setProperty("http.cors.origins", "http://a.test:3000, https://b.test");
    p.setProperty("http.timeoutMs", "2500");
    p.setProperty("http.maxConcurrent", "3");
    List<String> warnings = new ArrayList<>();
    HttpConfig c = HttpConfig.parse(p, warnings::add);
    assertEquals(new HttpConfig(false, "0.0.0.0", 9000, List.of("http://a.test:3000",
        "https://b.test"), 2500, 3), c);
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void invalidValuesFallBackToDefaults() {
    Properties p = new Properties();
    p.setProperty("http.enabled", "yes");
    p.setProperty("http.port", "70000");
    p.setProperty("http.cors.origins", "localhost:3000");
    p.setProperty("http.timeoutMs", "abc");
    p.setProperty("http.maxConcurrent", "0");
    List<String> warnings = new ArrayList<>();
    assertEquals(HttpConfig.defaults(), HttpConfig.parse(p, warnings::add));
    assertEquals(5, warnings.size(), warnings.toString());
  }

  @Test
  void authDefaults() {
    HttpConfig d = HttpConfig.defaults();
    assertEquals("", d.publicUrl());
    assertEquals(168, d.sessionTtlHours());
    assertEquals(java.time.Duration.ofDays(7), d.sessionTtl());
    assertEquals(false, d.cookieSecure());
    assertEquals(d, HttpConfig.parse(new Properties(), w -> { throw new AssertionError(w); }));
  }

  @Test
  void defaultFileContainsEveryKey(@TempDir Path dir) throws Exception {
    HttpConfig.load(dir);
    Properties p = new Properties();
    try (var r = Files.newBufferedReader(dir.resolve(HttpConfig.FILE_NAME))) {
      p.load(r);
    }
    for (String key : HttpConfig.KEYS) {
      assertTrue(p.containsKey(key), key);
    }
    assertEquals("", p.getProperty("auth.cookieSecure"));
  }

  @Test
  void missingNewKeysAreAppendedToOldFile(@TempDir Path dir) throws Exception {
    Path file = dir.resolve(HttpConfig.FILE_NAME);
    Files.writeString(file, "http.port=9001\n");
    HttpConfig c = HttpConfig.load(dir);
    assertEquals(9001, c.port());
    String content = Files.readString(file);
    assertTrue(content.startsWith("http.port=9001\n"), content);
    assertTrue(content.contains("http.publicUrl="), content);
    assertTrue(content.contains("auth.sessionTtlHours=168"), content);
    assertEquals(1, content.split("http\\.port=", -1).length - 1, "existing key not duplicated");
    assertEquals(c, HttpConfig.load(dir), "second load is stable");
    assertEquals(content, Files.readString(file), "nothing appended the second time");
  }

  @Test
  void publicUrlAndCookieSecure() {
    List<String> warnings = new ArrayList<>();
    Properties p = new Properties();
    p.setProperty("http.publicUrl", "HTTPS://map.example.com/");
    p.setProperty("auth.sessionTtlHours", "24");
    HttpConfig c = HttpConfig.parse(p, warnings::add);
    assertEquals("https://map.example.com", c.publicUrl());
    assertEquals(24, c.sessionTtlHours());
    assertTrue(c.cookieSecure(), "https public URL implies Secure");

    p.setProperty("auth.cookieSecure", "false");
    assertEquals(false, HttpConfig.parse(p, warnings::add).cookieSecure(), "explicit override");

    p.setProperty("http.publicUrl", "http://1.2.3.4:8765");
    p.setProperty("auth.cookieSecure", "");
    assertEquals(false, HttpConfig.parse(p, warnings::add).cookieSecure());
    assertTrue(warnings.isEmpty(), warnings.toString());
  }

  @Test
  void invalidAuthValuesFallBack() {
    Properties p = new Properties();
    p.setProperty("http.publicUrl", "ftp://x");
    p.setProperty("auth.sessionTtlHours", "0");
    p.setProperty("auth.cookieSecure", "maybe");
    List<String> warnings = new ArrayList<>();
    assertEquals(HttpConfig.defaults(), HttpConfig.parse(p, warnings::add));
    assertEquals(3, warnings.size(), warnings.toString());
    assertEquals(null, HttpConfig.normalizePublicUrl("https://map.example.com/?q=1"));
    assertEquals(null, HttpConfig.normalizePublicUrl("https://user@map.example.com"));
    assertEquals(null, HttpConfig.normalizePublicUrl("map.example.com"));
  }

  @Test
  void wildcardAndEmptyOrigins() {
    assertEquals(List.of("*"), HttpConfig.parseOrigins("*"));
    assertEquals(List.of(), HttpConfig.parseOrigins(""));
  }
}
