package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.region.RegionGraph;
import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.route.ChunkBox;
import java.util.Set;

/**
 * Decides whether a route query may use the region graph. Minecraft-free.
 *
 * <p>The hierarchical search only knows about built sectors, so a graph with holes inside the query's
 * chunk box could report a false NO_PATH. The graph is therefore used only when every chunk of the
 * prepared box is a built sector (sector size 16, so sector == chunk) and its Y range matches the
 * view; otherwise the flat search runs.
 */
// THREADING: pure static function over immutable values; any thread (called on NavExecutor workers).
public final class GraphUsePolicy {

  private GraphUsePolicy() {
  }

  /**
   * @param graph current graph, may be null
   * @param box prepared chunk box of the query
   * @param minY view min Y
   * @param maxY view max Y
   * @return {@code graph} if it covers every chunk of {@code box} with matching sector size and Y range,
   *     otherwise {@code null}
   */
  public static RegionGraph usableFor(RegionGraph graph, ChunkBox box, int minY, int maxY) {
    if (graph == null || graph.sectorSize() != RegionGraphManager.SECTOR_SIZE
        || graph.minY() != minY || graph.maxY() != maxY) {
      return null;
    }
    Set<SectorPos> sectors = graph.sectors();
    if (sectors.size() < box.chunkCount()) {
      return null;
    }
    for (int cz = box.minChunkZ(); cz <= box.maxChunkZ(); cz++) {
      for (int cx = box.minChunkX(); cx <= box.maxChunkX(); cx++) {
        if (!sectors.contains(new SectorPos(cx, cz))) {
          return null;
        }
      }
    }
    return graph;
  }
}
