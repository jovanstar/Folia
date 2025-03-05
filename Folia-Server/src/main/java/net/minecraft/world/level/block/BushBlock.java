package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;

public abstract class BushBlock extends Block {

    protected BushBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    protected abstract MapCodec<? extends BushBlock> codec();

    protected boolean mayPlaceOn(BlockState floor, BlockGetter world, BlockPos pos) {
        return floor.is(BlockTags.DIRT) || floor.is(Blocks.FARMLAND);
    }

    @Override
    protected BlockState updateShape(BlockState state, LevelReader world, ScheduledTickAccess tickView, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
        // CraftBukkit start
        if (!state.canSurvive(world, pos)) {
            // Suppress during worldgen
            if (!(world instanceof net.minecraft.server.level.ServerLevel world1 && world1.getCurrentWorldData().hasPhysicsEvent) || !org.bukkit.craftbukkit.event.CraftEventFactory.callBlockPhysicsEvent(world1, pos).isCancelled()) { // Paper // Folia - region threading
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, world, tickView, pos, direction, neighborPos, neighborState, random);
        // CraftBukkit end
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        BlockPos blockposition1 = pos.below();

        return this.mayPlaceOn(world.getBlockState(blockposition1), world, blockposition1);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return state.getFluidState().isEmpty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return type == PathComputationType.AIR && !this.hasCollision ? true : super.isPathfindable(state, type);
    }
}
