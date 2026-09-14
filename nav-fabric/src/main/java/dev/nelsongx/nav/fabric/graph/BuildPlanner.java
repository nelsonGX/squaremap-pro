package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Splits a chunk rectangle into build batches. Minecraft-free, deterministic.
 *
 * <p>The rectangle is tiled into sub-rectangles of {@code tileW x tileH} chunks where
 * {@code tileW = min(width, batchSize)} and {@code tileH = max(1, batchSize / tileW)}, visited row-major
 * (z outer, x inner). Every chunk is in exactly one batch and every batch has at most {@code batchSize}
 * chunks. Tiles (rather than cutting the row-major sequence every {@code batchSize} chunks) keep each
 * batch's snapshot box compact: a batch that wrapped around a row end would need a snapshot of the whole
 * rectangle width.
 *
 * <p>Each batch's snapshot box is its bounds expanded by {@code margin} chunks.
 * {@link #DEFAULT_MARGIN} is 2, not 1: {@code RegionGraph.withSectorsRebuilt} recomputes the
 * cross-sector links <em>out of</em> every built neighbour of a rebuilt sector, and resolving those links
 * flood-fills the neighbour's own neighbours — up to two sectors away from the batch.
 */
// THREADING: pure static functions; any thread (used on the server thread when a job starts).
public final class BuildPlanner {

  /** Default chunks per batch. */
  public static final int DEFAULT_BATCH_SIZE = 64;
  /** Default snapshot margin in chunks around a batch (see class doc). */
  public static final int DEFAULT_MARGIN = 2;

  /**
   * One batch.
   *
   * @param index 0-based batch index
   * @param chunks the chunks (== sectors) rebuilt by this batch
   * @param prepareBox snapshot rectangle: {@code chunks} expanded by the margin
   */
  public record Batch(int index, ChunkBox chunks, ChunkBox prepareBox) {

    /** @return the batch's sectors in row-major order (z outer, x inner) */
    public List<SectorPos> sectors() {
      List<SectorPos> out = new ArrayList<>((int) chunks.chunkCount());
      for (int cz = chunks.minChunkZ(); cz <= chunks.maxChunkZ(); cz++) {
        for (int cx = chunks.minChunkX(); cx <= chunks.maxChunkX(); cx++) {
          out.add(new SectorPos(cx, cz));
        }
      }
      return out;
    }
  }

  private BuildPlanner() {
  }

  /**
   * Plans with {@link #DEFAULT_BATCH_SIZE} and {@link #DEFAULT_MARGIN}.
   *
   * @param area inclusive chunk rectangle
   * @return batches
   */
  public static List<Batch> plan(ChunkBox area) {
    return plan(area, DEFAULT_BATCH_SIZE, DEFAULT_MARGIN);
  }

  /**
   * @param area inclusive chunk rectangle (non-empty)
   * @param batchSize max chunks per batch, &gt;= 1
   * @param margin snapshot margin, &gt;= 0
   * @return batches covering {@code area} exactly once, in row-major tile order
   * @throws IllegalArgumentException on an empty area or invalid sizes
   */
  public static List<Batch> plan(ChunkBox area, int batchSize, int margin) {
    Objects.requireNonNull(area, "area");
    if (batchSize < 1 || margin < 0) {
      throw new IllegalArgumentException("batchSize must be >= 1 and margin >= 0");
    }
    long width = (long) area.maxChunkX() - area.minChunkX() + 1;
    long depth = (long) area.maxChunkZ() - area.minChunkZ() + 1;
    if (width <= 0 || depth <= 0) {
      throw new IllegalArgumentException("empty area: " + area);
    }
    long tileW = Math.min(width, batchSize);
    long tileH = Math.max(1, batchSize / tileW);
    List<Batch> out = new ArrayList<>();
    for (long tz = area.minChunkZ(); tz <= area.maxChunkZ(); tz += tileH) {
      for (long tx = area.minChunkX(); tx <= area.maxChunkX(); tx += tileW) {
        int x0 = (int) tx;
        int z0 = (int) tz;
        int x1 = (int) Math.min(tx + tileW - 1, area.maxChunkX());
        int z1 = (int) Math.min(tz + tileH - 1, area.maxChunkZ());
        ChunkBox chunks = new ChunkBox(x0, z0, x1, z1);
        ChunkBox prepare = new ChunkBox(x0 - margin, z0 - margin, x1 + margin, z1 + margin);
        out.add(new Batch(out.size(), chunks, prepare));
      }
    }
    return out;
  }
}
