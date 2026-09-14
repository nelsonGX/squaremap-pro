package dev.nelsongx.nav.fabric.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegionStorePathsTest {

  @Test
  void mapsIdentifiers() {
    assertEquals(Path.of("data", "squaremap-pro", "regions", "minecraft", "overworld.sqlite"),
        RegionStorePaths.relativePath("minecraft", "overworld"));
    assertEquals(Path.of("data", "squaremap-pro", "regions", "minecraft", "the_nether.sqlite"),
        RegionStorePaths.relativePath("minecraft", "the_nether"));
    assertEquals(Path.of("data", "squaremap-pro", "regions", "my-mod.x", "dims", "a.b-c.sqlite"),
        RegionStorePaths.relativePath("my-mod.x", "dims/a.b-c"));
  }

  @Test
  void resultStaysInsideRegionsDirectory() {
    Path root = Path.of("world").toAbsolutePath();
    Path regions = root.resolve(Path.of("data", "squaremap-pro", "regions"));
    Path p = root.resolve(RegionStorePaths.relativePath("a", "b/c")).normalize();
    assertEquals(regions.resolve(Path.of("a", "b", "c.sqlite")), p);
    assertFalse(RegionStorePaths.relativePath("a", "b").isAbsolute());
  }

  @Test
  void rejectsTraversalAndInvalidCharacters() {
    List<String[]> bad = List.of(
        new String[] {"..", "overworld"},
        new String[] {".", "overworld"},
        new String[] {"minecraft", ".."},
        new String[] {"minecraft", "../x"},
        new String[] {"minecraft", "a/../b"},
        new String[] {"minecraft", "a/./b"},
        new String[] {"minecraft", "/abs"},
        new String[] {"minecraft", "a/"},
        new String[] {"minecraft", "a//b"},
        new String[] {"minecraft", "a\\b"},
        new String[] {"minecraft", "C:x"},
        new String[] {"mine/craft", "x"},
        new String[] {"Minecraft", "x"},
        new String[] {"minecraft", "a b"},
        new String[] {"", "x"},
        new String[] {"minecraft", ""},
        new String[] {"minecraft", "a\0b"});
    for (String[] b : bad) {
      assertThrows(IllegalArgumentException.class, () -> RegionStorePaths.relativePath(b[0], b[1]),
          b[0] + ":" + b[1]);
    }
  }
}
