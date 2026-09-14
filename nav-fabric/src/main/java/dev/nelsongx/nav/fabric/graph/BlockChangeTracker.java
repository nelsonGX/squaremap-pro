package dev.nelsongx.nav.fabric.graph;

import dev.nelsongx.nav.core.region.SectorPos;
import dev.nelsongx.nav.fabric.world.NavServices;
import dev.nelsongx.nav.fabric.world.SnapshotCache;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Reacts to block changes and chunk load/unload: drops stale chunk snapshots and marks built sectors
 * dirty for the periodic rebuild in {@link NavGraphServices#tick}.
 *
 * <h2>Neighbour chunks</h2>
 *
 * Only the changed chunk's snapshot is invalidated. A {@code ChunkSnapshot} stores a per-block class that
 * depends on the block state alone, and walkability reads {@code y-1..y+1} of the same column, so a change
 * never alters another chunk's snapshot. Likewise only the changed sector is marked dirty:
 * {@code RegionGraph.withSectorsRebuilt} itself recomputes the links of the 8 neighbouring sectors, and
 * the rebuild's view covers {@link RegionGraphManager#VIEW_MARGIN} chunks around it.
 */
// THREADING: onBlockChanged() is invoked from the LevelChunk#setBlockState mixin wherever that runs.
// Level#setBlock on a ServerLevel runs on the server thread, but world-generation code can reach
// LevelChunk#setBlockState from worker threads (WorldGenRegion#setBlock -> ChunkAccess#setBlockState,
// ImposterProtoChunk#setBlockState -> wrapped LevelChunk). Those calls are ignored cheaply with
// level.getServer().isSameThread(); everything after that check is SERVER THREAD only and non-blocking
// (concurrent-map removes/puts). onChunkLoad()/onChunkUnload() are Fabric ServerChunkEvents callbacks,
// fired on the server thread (also guarded).
public final class BlockChangeTracker {

  private BlockChangeTracker() {
  }

  /**
   * Block at {@code (x, y, z)} of {@code level} changed. Allocation-light; must stay cheap.
   *
   * @param level the level
   * @param x block x
   * @param y block y
   * @param z block z
   */
  public static void onBlockChanged(ServerLevel level, int x, int y, int z) {
    NavServices nav = NavServices.current();
    if (nav == null || !level.getServer().isSameThread()) {
      return;
    }
    ResourceKey<Level> dimension = level.dimension();
    int cx = x >> 4;
    int cz = z >> 4;
    SnapshotCache cache = nav.existingCache(dimension);
    if (cache != null) {
      cache.invalidate(cx, cz);
    }
    NavGraphServices graphs = NavGraphServices.current();
    if (graphs == null) {
      return;
    }
    RegionGraphManager manager = graphs.manager(dimension);
    if (manager != null && manager.acceptsChange(cx, cz)) {
      manager.markChanged(new SectorPos(cx, cz), System.nanoTime());
    }
  }

  /**
   * {@code ServerChunkEvents.CHUNK_LOAD}: drop any leftover snapshot and wake sectors waiting for this
   * chunk.
   *
   * @param level the level
   * @param chunk the loaded chunk
   */
  public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
    NavServices nav = NavServices.current();
    if (nav == null || !level.getServer().isSameThread()) {
      return;
    }
    int cx = chunk.getPos().x;
    int cz = chunk.getPos().z;
    SnapshotCache cache = nav.existingCache(level.dimension());
    if (cache != null) {
      cache.invalidate(cx, cz);
    }
    NavGraphServices graphs = NavGraphServices.current();
    RegionGraphManager manager = graphs == null ? null : graphs.manager(level.dimension());
    if (manager != null) {
      manager.onChunkLoaded(cx, cz);
    }
  }

  /**
   * {@code ServerChunkEvents.CHUNK_UNLOAD}: drop the snapshot so an unloaded chunk is not served from
   * cache.
   *
   * @param level the level
   * @param chunk the unloading chunk
   */
  public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
    NavServices nav = NavServices.current();
    if (nav == null || !level.getServer().isSameThread()) {
      return;
    }
    SnapshotCache cache = nav.existingCache(level.dimension());
    if (cache != null) {
      cache.invalidate(chunk.getPos().x, chunk.getPos().z);
    }
  }
}
