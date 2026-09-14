package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.WorldView;
import dev.nelsongx.nav.core.fixture.FixtureWorld;
import dev.nelsongx.nav.core.region.SectorPos;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiPredicate;

/** Fixtures for graph tests: generated 64x64 worlds (4x4 chunks) and a loaded-chunk mask. */
final class GraphTestSupport {

  static final int SIZE = 64;
  static final Executor DIRECT = Runnable::run;

  /** Loaded check for {@link MaskedWorld}. */
  static final BiPredicate<MaskedWorld, SectorPos> LOADED = (v, s) -> v.hasChunk(s.sx(), s.sz());

  private GraphTestSupport() {
  }

  /**
   * Floor at y=0 with holes and fluid, walls at y=1 (fluid-capped at y=2), platforms at y=3, all placed
   * pseudo-randomly from {@code seed}.
   */
  static String worldText(long seed) {
    Random rnd = new Random(seed);
    char[][][] layers = new char[4][SIZE][SIZE];
    for (int y = 0; y < 4; y++) {
      for (int z = 0; z < SIZE; z++) {
        for (int x = 0; x < SIZE; x++) {
          layers[y][z][x] = '.';
        }
      }
    }
    for (int z = 0; z < SIZE; z++) {
      for (int x = 0; x < SIZE; x++) {
        int r = rnd.nextInt(100);
        layers[0][z][x] = r < 6 ? '.' : r < 9 ? '~' : '#';
      }
    }
    for (int i = 0; i < 14; i++) {
      boolean alongX = rnd.nextBoolean();
      int a = rnd.nextInt(SIZE);
      int b0 = rnd.nextInt(SIZE);
      int len = 5 + rnd.nextInt(30);
      for (int k = 0; k < len; k++) {
        int b = b0 + k;
        if (b >= SIZE) {
          break;
        }
        int x = alongX ? b : a;
        int z = alongX ? a : b;
        layers[1][z][x] = '#';
        layers[2][z][x] = '~';
      }
    }
    for (int i = 0; i < 8; i++) {
      int x0 = rnd.nextInt(SIZE - 6);
      int z0 = rnd.nextInt(SIZE - 6);
      int w = 2 + rnd.nextInt(10);
      int d = 2 + rnd.nextInt(10);
      for (int z = z0; z < Math.min(SIZE, z0 + d); z++) {
        for (int x = x0; x < Math.min(SIZE, x0 + w); x++) {
          if (layers[1][z][x] == '.' && layers[2][z][x] == '.') {
            layers[3][z][x] = '#';
          }
        }
      }
    }
    return render(layers);
  }

  static String render(char[][][] layers) {
    StringBuilder sb = new StringBuilder();
    for (int y = 0; y < layers.length; y++) {
      sb.append("y=").append(y).append('\n');
      for (char[] row : layers[y]) {
        sb.append(row).append('\n');
      }
    }
    return sb.toString();
  }

  /** Parses {@code text} after replacing a rectangle of layer {@code y} with {@code c}. */
  static FixtureWorld modified(String text, int y, int x0, int z0, int x1, int z1, char c) {
    String[] lines = text.split("\n");
    int header = -1;
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].equals("y=" + y)) {
        header = i;
      }
    }
    for (int z = z0; z <= z1; z++) {
      char[] row = lines[header + 1 + z].toCharArray();
      for (int x = x0; x <= x1; x++) {
        row[x] = c;
      }
      lines[header + 1 + z] = new String(row);
    }
    return FixtureWorld.parse(String.join("\n", lines));
  }

  /** WorldView that reads chunks outside {@code loaded} as unknown (like a snapshot view). */
  static final class MaskedWorld implements WorldView {
    private final WorldView delegate;
    private final Set<SectorPos> loaded;

    MaskedWorld(WorldView delegate, Set<SectorPos> loaded) {
      this.delegate = delegate;
      this.loaded = loaded;
    }

    boolean hasChunk(int cx, int cz) {
      return loaded.contains(new SectorPos(cx, cz));
    }

    private boolean in(int x, int z) {
      return hasChunk(x >> 4, z >> 4);
    }

    @Override
    public int minY() {
      return delegate.minY();
    }

    @Override
    public int maxY() {
      return delegate.maxY();
    }

    @Override
    public boolean passable(int x, int y, int z) {
      return in(x, z) && delegate.passable(x, y, z);
    }

    @Override
    public boolean walkable(int x, int y, int z) {
      return in(x, z) && delegate.walkable(x, y, z);
    }

    @Override
    public OptionalInt groundY(int x, int z) {
      return in(x, z) ? delegate.groundY(x, z) : OptionalInt.empty();
    }
  }
}
