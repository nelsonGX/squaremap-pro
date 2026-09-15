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
  void wildcardAndEmptyOrigins() {
    assertEquals(List.of("*"), HttpConfig.parseOrigins("*"));
    assertEquals(List.of(), HttpConfig.parseOrigins(""));
  }
}
