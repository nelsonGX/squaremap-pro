package dev.nelsongx.nav.core.fixture;

import dev.nelsongx.nav.core.GridPos;
import dev.nelsongx.nav.core.WorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link WorldView} parsed from ASCII art, for tests.
 *
 * <h2>Format</h2>
 *
 * <ul>
 *   <li>Lines starting with {@code ;} are comments; blank lines are ignored.
 *   <li>A header line {@code y=<int>} starts a layer at that Y. Layers may appear in any order; Y
 *       values must be unique.
 *   <li>Following lines until the next header are rows of that layer: row index = z (first row z=0),
 *       column index = x (first column x=0). Trailing whitespace is ignored.
 *   <li>Cells: {@code #} solid (standable, not passable); {@code .} air (passable); {@code ~} fluid
 *       (neither standable nor passable); {@code A}-{@code Z} air that also records a named marker at
 *       that position (each letter at most once in the whole world).
 *   <li>All layers must have the same width and depth.
 *   <li>Y levels without a layer are air. {@link #minY()} is the lowest layer Y; {@link #maxY()} is the
 *       highest layer Y + 2. x/z outside the grid and y outside [minY, maxY] are neither solid nor
 *       passable.
 * </ul>
 *
 * <p>Malformed input throws {@link IllegalArgumentException} whose message starts with
 * {@code "line N: "} (1-based source line).
 *
 * <p>Immutable and thread-safe after parsing.
 */
// THREADING: immutable after parse; safe for concurrent reads from any thread (test fixture only).
public final class FixtureWorld implements WorldView {

  private static final Pattern HEADER = Pattern.compile("y=(-?\\d+)");

  private static final byte AIR = 0;
  private static final byte SOLID = 1;
  private static final byte FLUID = 2;
  private static final int OUTSIDE = -1;

  private final int minY;
  private final int maxY;
  private final int width;
  private final int depth;
  /** Indexed [y - minY][z][x]; a null level is all air. Never mutated after construction. */
  private final byte[][][] cells;
  private final Map<Character, GridPos> markers;

  private FixtureWorld(int minY, int maxY, int width, int depth, byte[][][] cells,
      Map<Character, GridPos> markers) {
    this.minY = minY;
    this.maxY = maxY;
    this.width = width;
    this.depth = depth;
    this.cells = cells;
    this.markers = markers;
  }

  /**
   * Parses a fixture world.
   *
   * @param text the ASCII description (see class Javadoc)
   * @return the parsed world
   * @throws IllegalArgumentException if the input is malformed; the message includes the 1-based line
   *     number
   */
  public static FixtureWorld parse(String text) {
    Objects.requireNonNull(text, "text");
    String[] lines = text.split("\\R", -1);
    TreeMap<Integer, byte[][]> layers = new TreeMap<>();
    Map<Character, GridPos> markers = new TreeMap<>();

    int width = -1;
    int depth = -1;
    Integer curY = null;
    int curHeaderLine = 0;
    List<byte[]> curRows = null;

    for (int i = 0; i <= lines.length; i++) {
      int lineNo = i + 1;
      boolean atEnd = i == lines.length;
      String line = atEnd ? "" : lines[i].stripTrailing();
      Matcher m = atEnd ? null : HEADER.matcher(line);
      boolean header = m != null && m.matches();

      if (atEnd || header) {
        if (curY != null) {
          if (curRows.isEmpty()) {
            throw error(curHeaderLine, "layer y=" + curY + " has no rows");
          }
          if (depth < 0) {
            depth = curRows.size();
          } else if (curRows.size() != depth) {
            throw error(curHeaderLine, "layer y=" + curY + " has depth " + curRows.size()
                + " but expected " + depth);
          }
          layers.put(curY, curRows.toArray(new byte[0][]));
        }
        if (atEnd) {
          break;
        }
        int y;
        try {
          y = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
          throw error(lineNo, "invalid layer Y: " + line);
        }
        if (layers.containsKey(y) || (curY != null && curY == y)) {
          throw error(lineNo, "duplicate layer y=" + y);
        }
        curY = y;
        curHeaderLine = lineNo;
        curRows = new ArrayList<>();
        continue;
      }

      if (line.isEmpty() || line.startsWith(";")) {
        continue;
      }
      if (curY == null) {
        throw error(lineNo, "row before first 'y=<int>' header");
      }
      if (width < 0) {
        width = line.length();
      } else if (line.length() != width) {
        throw error(lineNo, "row has width " + line.length() + " but expected " + width);
      }
      int z = curRows.size();
      byte[] row = new byte[width];
      for (int x = 0; x < width; x++) {
        char c = line.charAt(x);
        if (c == '#') {
          row[x] = SOLID;
        } else if (c == '.') {
          row[x] = AIR;
        } else if (c == '~') {
          row[x] = FLUID;
        } else if (c >= 'A' && c <= 'Z') {
          row[x] = AIR;
          if (markers.containsKey(c)) {
            throw error(lineNo, "duplicate marker '" + c + "'");
          }
          markers.put(c, new GridPos(x, curY, z));
        } else {
          throw error(lineNo, "invalid cell character '" + c + "' at column " + (x + 1));
        }
      }
      curRows.add(row);
    }

    if (layers.isEmpty()) {
      throw error(lines.length, "no layers defined");
    }
    int minY = layers.firstKey();
    int maxY = layers.lastKey() + 2;
    byte[][][] cells = new byte[maxY - minY + 1][][];
    for (Map.Entry<Integer, byte[][]> e : layers.entrySet()) {
      cells[e.getKey() - minY] = e.getValue();
    }
    return new FixtureWorld(minY, maxY, width, depth, cells, Collections.unmodifiableMap(markers));
  }

  private static IllegalArgumentException error(int lineNo, String msg) {
    return new IllegalArgumentException("line " + lineNo + ": " + msg);
  }

  private int cell(int x, int y, int z) {
    if (x < 0 || x >= width || z < 0 || z >= depth || y < minY || y > maxY) {
      return OUTSIDE;
    }
    byte[][] layer = cells[y - minY];
    return layer == null ? AIR : layer[z][x];
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
  }

  /**
   * Grid width (number of columns, x extent).
   *
   * @return width
   */
  public int width() {
    return width;
  }

  /**
   * Grid depth (number of rows, z extent).
   *
   * @return depth
   */
  public int depth() {
    return depth;
  }

  /**
   * Whether the block is solid (standable). False outside the grid or vertical bounds.
   *
   * @param x block x
   * @param y block y
   * @param z block z
   * @return whether solid
   */
  public boolean solid(int x, int y, int z) {
    return cell(x, y, z) == SOLID;
  }

  /**
   * Whether the block is passable (air or marker). False for solid, fluid, and outside the grid or
   * vertical bounds.
   *
   * @param x block x
   * @param y block y
   * @param z block z
   * @return whether passable
   */
  public boolean passable(int x, int y, int z) {
    return cell(x, y, z) == AIR;
  }

  @Override
  public boolean walkable(int x, int y, int z) {
    return solid(x, y - 1, z) && passable(x, y, z) && passable(x, y + 1, z);
  }

  @Override
  public OptionalInt groundY(int x, int z) {
    for (int y = maxY; y >= minY; y--) {
      if (walkable(x, y, z)) {
        return OptionalInt.of(y);
      }
    }
    return OptionalInt.empty();
  }

  /**
   * Position of a marker letter: the cell where it was written, so its Y is that layer's Y.
   *
   * @param c marker letter
   * @return marker position
   * @throws NoSuchElementException if the marker is absent
   */
  public GridPos marker(char c) {
    GridPos p = markers.get(c);
    if (p == null) {
      throw new NoSuchElementException("no marker '" + c + "'");
    }
    return p;
  }

  /**
   * All markers.
   *
   * @return unmodifiable map from letter to position
   */
  public Map<Character, GridPos> markers() {
    return markers;
  }
}
