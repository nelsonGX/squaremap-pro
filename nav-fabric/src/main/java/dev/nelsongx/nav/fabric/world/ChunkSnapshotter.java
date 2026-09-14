package dev.nelsongx.nav.fabric.world;

import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Copies a loaded chunk's blocks into an immutable {@link ChunkSnapshot}.
 *
 * <p>Uses {@code ServerChunkCache#getChunkNow(int, int)}, which only returns chunks already at
 * {@code FULL} status and never loads or generates.
 */
// THREADING: SERVER THREAD ONLY. snapshot() reads a live LevelChunk and asserts
// level.getServer().isSameThread() at entry. The returned ChunkSnapshot may be handed to any thread.
public final class ChunkSnapshotter {

  private final BlockClassifier classifier;

  /**
   * Creates a snapshotter.
   *
   * @param classifier block classifier (its table must have been built on the server thread)
   */
  public ChunkSnapshotter(BlockClassifier classifier) {
    this.classifier = Objects.requireNonNull(classifier, "classifier");
  }

  /**
   * Snapshots a chunk if it is already loaded.
   *
   * <p>SERVER THREAD ONLY.
   *
   * @param level the level
   * @param chunkX chunk x
   * @param chunkZ chunk z
   * @return the snapshot, or null if the chunk is not loaded at FULL status
   * @throws IllegalStateException if not called on the server thread
   */
  public ChunkSnapshot snapshot(ServerLevel level, int chunkX, int chunkZ) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException(
          "ChunkSnapshotter.snapshot must run on the server thread, not "
              + Thread.currentThread().getName());
    }
    // getChunkNow: returns null (never loads) if the chunk is not present at FULL status.
    LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (chunk == null) {
      return null;
    }
    int minY = level.getMinY();
    int height = level.getHeight();
    ChunkSnapshot.Builder builder = ChunkSnapshot.builder(chunkX, chunkZ, minY, height);
    LevelChunkSection[] sections = chunk.getSections();
    // Level min_y/height are multiples of 16 (DimensionType validation), so snapshot section i is
    // exactly chunk section i.
    int count = Math.min(sections.length, builder.sectionCount());
    for (int s = 0; s < count; s++) {
      LevelChunkSection section = sections[s];
      if (section == null || section.hasOnlyAir()) {
        builder.setUniformSection(s, BlockClass.PASSABLE);
        continue;
      }
      PalettedContainer<BlockState> states = section.getStates();
      byte uniform = uniformClass(states);
      if (uniform >= 0) {
        builder.setUniformSection(s, uniform);
        continue;
      }
      byte[] data = new byte[ChunkSnapshot.SECTION_VOLUME];
      int i = 0;
      for (int ly = 0; ly < 16; ly++) {
        for (int lz = 0; lz < 16; lz++) {
          for (int lx = 0; lx < 16; lx++) {
            data[i++] = classifier.classify(states.get(lx, ly, lz));
          }
        }
      }
      builder.adoptSection(s, data);
    }
    return builder.build();
  }

  /**
   * If the section palette proves every state has the same class, returns it; otherwise -1.
   * {@code maybeHas} is exact for small palettes and conservatively true for the global palette.
   */
  private byte uniformClass(PalettedContainer<BlockState> states) {
    for (byte c = 0; c <= BlockClass.BLOCKED; c++) {
      final byte target = c;
      if (!states.maybeHas(st -> classifier.classify(st) != target)) {
        return target;
      }
    }
    return -1;
  }
}
