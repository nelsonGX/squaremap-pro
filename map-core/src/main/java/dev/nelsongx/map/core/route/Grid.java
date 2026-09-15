package dev.nelsongx.map.core.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * Immutable uniform-grid spatial index of int item ids (points or segments).
 *
 * <p>Segments are inserted into the cells of sample points spaced at most {@code cellSize / 2}
 * apart, so every point of a segment lies within {@code cellSize / 4} of an indexed cell; queries
 * therefore widen their cell range by one cell and are conservative (never miss an item; may
 * report an item more than once, or items outside the range). Segments needing more than {@link
 * #MAX_SEGMENT_SAMPLES} samples go to an overflow list that every query reports.
 *
 * <p>THREADING: immutable after construction, safe for concurrent queries.
 */
final class Grid {
  static final int CELL_SIZE = 64;
  static final int MAX_SEGMENT_SAMPLES = 4096;

  private final Map<Long, int[]> cells;
  private final int[] overflow;

  private Grid(Map<Long, int[]> cells, int[] overflow) {
    this.cells = cells;
    this.overflow = overflow;
  }

  /** Reports every item that may lie within the given (inclusive) bounds. */
  void query(double minX, double minZ, double maxX, double maxZ, IntConsumer out) {
    for (int item : overflow) {
      out.accept(item);
    }
    if (cells.isEmpty()) {
      return;
    }
    boolean finite =
        Double.isFinite(minX) && Double.isFinite(minZ) && Double.isFinite(maxX) && Double.isFinite(maxZ);
    long cx0 = 0;
    long cz0 = 0;
    long cx1 = 0;
    long cz1 = 0;
    boolean scanAll = !finite;
    if (finite) {
      cx0 = cell(minX) - 1;
      cz0 = cell(minZ) - 1;
      cx1 = cell(maxX) + 1;
      cz1 = cell(maxZ) + 1;
      double count = (double) (cx1 - cx0 + 1) * (double) (cz1 - cz0 + 1);
      scanAll = count > cells.size();
    }
    if (scanAll) {
      for (int[] items : cells.values()) {
        for (int item : items) {
          out.accept(item);
        }
      }
      return;
    }
    for (long cx = cx0; cx <= cx1; cx++) {
      for (long cz = cz0; cz <= cz1; cz++) {
        int[] items = cells.get(key(cx, cz));
        if (items != null) {
          for (int item : items) {
            out.accept(item);
          }
        }
      }
    }
  }

  private static long cell(double coord) {
    return (long) Math.floor(coord / CELL_SIZE);
  }

  private static long key(long cx, long cz) {
    return (cx << 32) ^ (cz & 0xffffffffL);
  }

  static final class Builder {
    private final Map<Long, List<Integer>> cells = new HashMap<>();
    private final List<Integer> overflow = new ArrayList<>();

    Builder addPoint(int item, double x, double z) {
      cells.computeIfAbsent(key(cell(x), cell(z)), k -> new ArrayList<>()).add(item);
      return this;
    }

    Builder addSegment(int item, double ax, double az, double bx, double bz) {
      double len = Math.hypot(bx - ax, bz - az);
      long samples = Math.max(1, (long) Math.ceil(len / (CELL_SIZE / 2.0)));
      if (samples > MAX_SEGMENT_SAMPLES) {
        overflow.add(item);
        return this;
      }
      Set<Long> keys = new HashSet<>();
      for (long k = 0; k <= samples; k++) {
        double t = (double) k / samples;
        long key = key(cell(ax + (bx - ax) * t), cell(az + (bz - az) * t));
        if (keys.add(key)) {
          cells.computeIfAbsent(key, x -> new ArrayList<>()).add(item);
        }
      }
      return this;
    }

    Grid build() {
      Map<Long, int[]> frozen = new HashMap<>(cells.size() * 2);
      cells.forEach((k, v) -> frozen.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
      return new Grid(frozen, overflow.stream().mapToInt(Integer::intValue).toArray());
    }
  }
}
