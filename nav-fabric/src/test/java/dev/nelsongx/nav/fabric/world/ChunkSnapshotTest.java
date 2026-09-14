package dev.nelsongx.nav.fabric.world;

import static dev.nelsongx.nav.fabric.world.BlockClass.BLOCKED;
import static dev.nelsongx.nav.fabric.world.BlockClass.PASSABLE;
import static dev.nelsongx.nav.fabric.world.BlockClass.STANDABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ChunkSnapshotTest {

  @Test
  void uniformSnapshotReturnsValueEverywhereInRange() {
    ChunkSnapshot s = ChunkSnapshot.uniform(3, -2, -64, 384, STANDABLE);
    assertEquals(24, s.uniformSectionCount());
    assertEquals(STANDABLE, s.classify(48, -64, -32));
    assertEquals(STANDABLE, s.classify(63, 319, -17));
    assertEquals(BLOCKED, s.classify(48, -65, -32));
    assertEquals(BLOCKED, s.classify(48, 320, -32));
  }

  @Test
  void indexingUsesLowBitsForNegativeCoordinates() {
    // chunk (-1,-1) covers x,z in [-16,-1]
    ChunkSnapshot.Builder b = ChunkSnapshot.builder(-1, -1, -64, 64);
    for (int s = 0; s < b.sectionCount(); s++) {
      b.setUniformSection(s, PASSABLE);
    }
    b.set(-1, -64, -16, STANDABLE);   // local (15, 0, 0)
    b.set(-16, -1, -1, BLOCKED);      // local (0, 63, 15)
    b.set(-9, -30, -7, STANDABLE);    // local (7, 34, 9)
    ChunkSnapshot s = b.build();

    assertEquals(STANDABLE, s.classify(-1, -64, -16));
    assertEquals(BLOCKED, s.classify(-16, -1, -1));
    assertEquals(STANDABLE, s.classify(-9, -30, -7));
    assertEquals(PASSABLE, s.classify(-2, -64, -16));
    assertEquals(PASSABLE, s.classify(-1, -63, -16));
    assertEquals(PASSABLE, s.classify(-1, -64, -15));
    // low-bit aliasing: local coordinates address the same cells
    assertEquals(STANDABLE, s.classify(15, -64, 0));
    assertEquals(STANDABLE, s.classify(7, -30, 9));
  }

  @Test
  void setSectionIndexLayout() {
    byte[] data = new byte[ChunkSnapshot.SECTION_VOLUME];
    Arrays.fill(data, PASSABLE);
    int lx = 5;
    int ly = 11;
    int lz = 2;
    data[ly * 256 + lz * 16 + lx] = STANDABLE;
    ChunkSnapshot s = ChunkSnapshot.builder(0, 0, 0, 16).setSection(0, data).build();
    assertEquals(STANDABLE, s.classify(lx, ly, lz));
    assertEquals(PASSABLE, s.classify(lz, ly, lx));
    assertEquals(0, s.uniformSectionCount());
    // input array was copied
    data[ly * 256 + lz * 16 + lx] = BLOCKED;
    assertEquals(STANDABLE, s.classify(lx, ly, lz));
  }

  @Test
  void buildCollapsesUniformSections() {
    ChunkSnapshot.Builder b = ChunkSnapshot.builder(0, 0, 0, 48);
    b.setUniformSection(0, STANDABLE);
    b.setUniformSection(1, PASSABLE);
    b.setUniformSection(2, PASSABLE);
    b.set(4, 20, 4, BLOCKED);  // section 1 becomes non-uniform
    b.set(4, 36, 4, BLOCKED);  // section 2 non-uniform, then restored
    b.set(4, 36, 4, PASSABLE);
    ChunkSnapshot s = b.build();
    assertEquals(2, s.uniformSectionCount());
    assertEquals(BLOCKED, s.classify(4, 20, 4));
    assertEquals(PASSABLE, s.classify(4, 36, 4));
    assertEquals(STANDABLE, s.classify(15, 15, 15));
  }

  @Test
  void partialTopSectionAndBounds() {
    ChunkSnapshot s = ChunkSnapshot.uniform(0, 0, 10, 20, PASSABLE);
    assertEquals(2, s.uniformSectionCount());
    assertEquals(29, s.maxY());
    assertEquals(PASSABLE, s.classify(0, 29, 0));
    assertEquals(BLOCKED, s.classify(0, 30, 0));
    assertEquals(BLOCKED, s.classify(0, 9, 0));
  }

  @Test
  void defaultIsBlockedAndValidation() {
    ChunkSnapshot s = ChunkSnapshot.builder(0, 0, 0, 16).build();
    assertEquals(BLOCKED, s.classify(0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> ChunkSnapshot.builder(0, 0, 0, 0));
    assertThrows(IllegalArgumentException.class,
        () -> ChunkSnapshot.builder(0, 0, 0, 16).set(0, 16, 0, PASSABLE));
    assertThrows(IllegalArgumentException.class,
        () -> ChunkSnapshot.builder(0, 0, 0, 16).set(0, 0, 0, (byte) 7));
    assertThrows(IllegalArgumentException.class,
        () -> ChunkSnapshot.builder(0, 0, 0, 16).setSection(0, new byte[10]));
    ChunkSnapshot.Builder b = ChunkSnapshot.builder(0, 0, 0, 16);
    b.build();
    assertThrows(IllegalStateException.class, b::build);
  }

  @Test
  void keyDistinguishesSignedCoordinates() {
    assertNotEquals(ChunkSnapshot.key(-1, 0), ChunkSnapshot.key(0, -1));
    assertNotEquals(ChunkSnapshot.key(-1, -1), ChunkSnapshot.key(1, 1));
    assertNotEquals(ChunkSnapshot.key(Integer.MIN_VALUE, 0), ChunkSnapshot.key(0, Integer.MIN_VALUE));
  }
}
