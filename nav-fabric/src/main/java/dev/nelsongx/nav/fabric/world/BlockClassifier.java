package dev.nelsongx.nav.fabric.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.tags.FluidTags;

/**
 * Maps a {@link BlockState} to a {@link BlockClass} byte.
 *
 * <h2>Rules (position-independent)</h2>
 *
 * <ol>
 *   <li><b>Hazard</b> &rarr; {@code BLOCKED}. Mirrors vanilla {@code WalkNodeEvaluator
 *       #getPathTypeFromState} for the types a land mob refuses or is hurt by: powder snow
 *       ({@code POWDER_SNOW}), cactus and sweet berry bush ({@code DAMAGE_OTHER}), wither rose and
 *       pointed dripstone ({@code DAMAGE_CAUTIOUS}), lava fluid ({@code LAVA}) and
 *       {@link NodeEvaluator#isBurningBlock} (fire tag, lava, magma block, lit campfire, lava
 *       cauldron; {@code DAMAGE_FIRE}). {@code Blocks.FIRE}/{@code SOUL_FIRE} are also checked
 *       directly so the result does not depend on tag contents.
 *   <li><b>Passable</b>: {@code isPathfindable(PathComputationType.LAND)} and the fluid state is
 *       empty (no wading through water, waterlogged plants, or bubble columns).
 *   <li><b>Standable</b>: not pathfindable for LAND, and the top face is a usable floor, evaluated
 *       against {@code EmptyBlockGetter.INSTANCE} at {@code BlockPos.ZERO} exactly like vanilla's
 *       own per-state {@code BlockStateBase.Cache}: either {@code isFaceSturdy(.., Direction.UP)}
 *       (full top support face, e.g. stone, top slabs, soul sand, mud), or a collision shape that
 *       spans the full x/z footprint with its top in {@code [14/16, 1]} (dirt path, farmland). Fluid
 *       in a standable block (e.g. waterlogged top slab) is allowed since it is not occupied.
 *   <li>Otherwise {@code BLOCKED} (fences/walls, bottom slabs, stairs, closed doors/trapdoors, chests,
 *       water source blocks, snow layers 5-7, honey block).
 * </ol>
 *
 * <p><b>Known position-independence exceptions</b>: blocks with {@code dynamicShape} (bamboo,
 * scaffolding, moving pistons, ...) use their empty-getter shape; closed wooden doors are
 * {@code BLOCKED} although players can open them (vanilla LAND semantics).
 *
 * <p><b>Cache</b>: a {@code byte[]} indexed by {@code Block.getId(state)} precomputed over
 * {@code Block.BLOCK_STATE_REGISTRY} by {@link #rebuild()} on the server thread at
 * {@code SERVER_STARTED} and after each data-pack reload (tags such as {@code minecraft:fire} may
 * change). The table reference is volatile and never mutated after publication.
 */
// THREADING: SERVER THREAD ONLY. rebuild() and classify() evaluate BlockState tags/shapes and are
// only called from server lifecycle events and ChunkSnapshotter (server thread).
public final class BlockClassifier {

  private static final double MIN_FLOOR_TOP = 14.0 / 16.0;

  private volatile byte[] table = new byte[0];

  /**
   * Recomputes the classification table for every registered block state.
   *
   * <p>SERVER THREAD ONLY.
   */
  public void rebuild() {
    int maxId = 0;
    for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
      maxId = Math.max(maxId, Block.getId(state));
    }
    byte[] t = new byte[maxId + 1];
    java.util.Arrays.fill(t, BlockClass.BLOCKED);
    for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
      t[Block.getId(state)] = compute(state);
    }
    table = t;
  }

  /**
   * Cached classification.
   *
   * <p>SERVER THREAD ONLY.
   *
   * @param state block state
   * @return a {@link BlockClass} value
   */
  public byte classify(BlockState state) {
    int id = Block.getId(state);
    byte[] t = table;
    return id >= 0 && id < t.length ? t[id] : compute(state);
  }

  /**
   * Uncached classification (see class Javadoc for rules).
   *
   * <p>SERVER THREAD ONLY.
   *
   * @param state block state
   * @return a {@link BlockClass} value
   */
  public static byte compute(BlockState state) {
    try {
      if (state.isAir()) {
        return BlockClass.PASSABLE;
      }
      if (isHazard(state)) {
        return BlockClass.BLOCKED;
      }
      FluidState fluid = state.getFluidState();
      if (state.isPathfindable(PathComputationType.LAND)) {
        return fluid.isEmpty() ? BlockClass.PASSABLE : BlockClass.BLOCKED;
      }
      if (isFloor(state)) {
        return BlockClass.STANDABLE;
      }
      return BlockClass.BLOCKED;
    } catch (RuntimeException e) {
      // Some modded/dynamic shapes may misbehave against an empty getter; be conservative.
      return BlockClass.BLOCKED;
    }
  }

  private static boolean isHazard(BlockState state) {
    return state.is(Blocks.POWDER_SNOW)
        || state.is(Blocks.CACTUS)
        || state.is(Blocks.SWEET_BERRY_BUSH)
        || state.is(Blocks.WITHER_ROSE)
        || state.is(Blocks.POINTED_DRIPSTONE)
        || state.is(Blocks.FIRE)
        || state.is(Blocks.SOUL_FIRE)
        || state.getFluidState().is(FluidTags.LAVA)
        || NodeEvaluator.isBurningBlock(state);
  }

  private static boolean isFloor(BlockState state) {
    if (state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP)) {
      return true;
    }
    VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    if (shape.isEmpty()) {
      return false;
    }
    double top = shape.max(Direction.Axis.Y);
    return top >= MIN_FLOOR_TOP && top <= 1.0
        && shape.min(Direction.Axis.X) <= 0.0 && shape.max(Direction.Axis.X) >= 1.0
        && shape.min(Direction.Axis.Z) <= 0.0 && shape.max(Direction.Axis.Z) >= 1.0;
  }
}
