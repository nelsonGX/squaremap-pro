package dev.nelsongx.nav.fabric.mixin;

import dev.nelsongx.nav.fabric.graph.BlockChangeTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notifies {@link BlockChangeTracker} after a successful {@code LevelChunk#setBlockState} on a server
 * level. {@code setBlockState} returns the previous state, or {@code null} when nothing changed.
 */
// THREADING: runs on whatever thread calls LevelChunk#setBlockState (server thread for Level#setBlock;
// possibly a world-gen worker). The hook filters non-server-thread calls itself; this injector only does
// an instanceof check and a static call.
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {

  @Inject(
      method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
      at = @At("RETURN"))
  private void squaremapPro$onSetBlockState(BlockPos pos, BlockState state, int flags,
      CallbackInfoReturnable<BlockState> cir) {
    if (cir.getReturnValue() != null
        && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
      BlockChangeTracker.onBlockChanged(level, pos.getX(), pos.getY(), pos.getZ());
    }
  }
}
