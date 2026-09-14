package dev.nelsongx.nav.core.hierarchy;

import dev.nelsongx.nav.core.fixture.FixtureWorld;

/** Shared fixture worlds for hierarchy tests. */
final class HierarchyTestSupport {

  static final int SIZE = 64;
  static final int SECTOR = 8;

  private HierarchyTestSupport() {
  }

  /**
   * 64x64 world, floor at y=0 (feet y=1), walls two blocks tall (y=1..2).
   *
   * <ul>
   *   <li>Vertical wall x=24, z=0..39, with a gap at z=30..31 (gap cells sit on the sector boundary so
   *       the links through it start on the gap cells) unless {@code staleVariant}, in which case that
   *       gap is closed and a gap at z=10..11 is opened instead.
   *   <li>Horizontal wall z=20, x=25..63, gap at x=50..51.
   *   <li>Moat (fluid floor) z=40..43 across the whole width, crossed by a one-high bridge at x=5..6
   *       (feet y=2 on the bridge).
   *   <li>Enclosed pocket: wall ring x=36..44, z=50..58, marker C inside.
   *   <li>A = (60,1,2) top-right, B = (60,1,60) bottom-right, C = (40,1,54) in the pocket.
   * </ul>
   */
  static FixtureWorld acceptanceWorld(boolean staleVariant) {
    char[][][] c = new char[3][SIZE][SIZE];
    for (int y = 0; y < 3; y++) {
      for (int z = 0; z < SIZE; z++) {
        for (int x = 0; x < SIZE; x++) {
          c[y][z][x] = y == 0 ? (z >= 40 && z <= 43 ? '~' : '#') : '.';
        }
      }
    }
    for (int y = 1; y <= 2; y++) {
      for (int z = 0; z <= 39; z++) {
        boolean gap = staleVariant ? (z == 10 || z == 11) : (z == 30 || z == 31);
        if (!gap) {
          c[y][z][24] = '#';
        }
      }
      for (int x = 25; x < SIZE; x++) {
        if (x != 50 && x != 51) {
          c[y][20][x] = '#';
        }
      }
      for (int x = 36; x <= 44; x++) {
        for (int z = 50; z <= 58; z++) {
          if (x == 36 || x == 44 || z == 50 || z == 58) {
            c[y][z][x] = '#';
          }
        }
      }
    }
    for (int z = 40; z <= 43; z++) {
      c[1][z][5] = '#';
      c[1][z][6] = '#';
    }
    c[1][2][60] = 'A';
    c[1][60][60] = 'B';
    c[1][54][40] = 'C';
    return FixtureWorld.parse(toFixture(c));
  }

  static String toFixture(char[][][] cells) {
    StringBuilder sb = new StringBuilder();
    for (int y = 0; y < cells.length; y++) {
      sb.append("y=").append(y).append('\n');
      for (char[] row : cells[y]) {
        sb.append(row).append('\n');
      }
    }
    return sb.toString();
  }
}
