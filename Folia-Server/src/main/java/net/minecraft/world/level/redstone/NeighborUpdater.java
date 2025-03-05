package net.minecraft.world.level.redstone;

import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.event.block.BlockPhysicsEvent;
// CraftBukkit end

public interface NeighborUpdater {

    Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int flags, int maxUpdateDepth);

    void neighborChanged(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation);

    void neighborChanged(BlockState state, BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, boolean notify);

    default void updateNeighborsAtExceptFromFacing(BlockPos pos, Block sourceBlock, @Nullable Direction except, @Nullable Orientation orientation) {
        Direction[] aenumdirection = NeighborUpdater.UPDATE_ORDER;
        int i = aenumdirection.length;

        for (int j = 0; j < i; ++j) {
            Direction enumdirection1 = aenumdirection[j];

            if (enumdirection1 != except) {
                this.neighborChanged(pos.relative(enumdirection1), sourceBlock, (Orientation) null);
            }
        }

    }

    static void executeShapeUpdate(LevelAccessor world, Direction direction, BlockPos pos, BlockPos neighborPos, BlockState neighborState, int flags, int maxUpdateDepth) {
        BlockState iblockdata1 = world.getBlockState(pos);

        if ((flags & 128) == 0 || !iblockdata1.is(Blocks.REDSTONE_WIRE)) {
            BlockState iblockdata2 = iblockdata1.updateShape(world, world, pos, direction, neighborPos, neighborState, world.getRandom());

            Block.updateOrDestroy(iblockdata1, iblockdata2, world, pos, flags, maxUpdateDepth);
        }
    }

    static void executeUpdate(Level world, BlockState state, BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, boolean notify) {
        // Paper start - Add source block to BlockPhysicsEvent
        executeUpdate(world, state, pos, sourceBlock, orientation, notify, pos);
    }

    static void executeUpdate(Level world, BlockState state, BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, boolean notify, BlockPos sourcePos) {
        // Paper end - Add source block to BlockPhysicsEvent
        try {
            // CraftBukkit start
            CraftWorld cworld = ((ServerLevel) world).getWorld();
            if (cworld != null) {
                BlockPhysicsEvent event = new BlockPhysicsEvent(CraftBlock.at(world, pos), CraftBlockData.fromData(state), CraftBlock.at(world, sourcePos)); // Paper - Add source block to BlockPhysicsEvent
                ((ServerLevel) world).getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    return;
                }
            }
            // CraftBukkit end
            state.handleNeighborChanged(world, pos, sourceBlock, orientation, notify);
            // Spigot Start
        } catch (StackOverflowError ex) {
            world.lastPhysicsProblem = new BlockPos(pos);
            // Spigot End
        } catch (Throwable throwable) {
            CrashReport crashreport = CrashReport.forThrowable(throwable, "Exception while updating neighbours");
            CrashReportCategory crashreportsystemdetails = crashreport.addCategory("Block being updated");

            crashreportsystemdetails.setDetail("Source block type", () -> {
                try {
                    return String.format(Locale.ROOT, "ID #%s (%s // %s)", BuiltInRegistries.BLOCK.getKey(sourceBlock), sourceBlock.getDescriptionId(), sourceBlock.getClass().getCanonicalName());
                } catch (Throwable throwable1) {
                    return "ID #" + String.valueOf(BuiltInRegistries.BLOCK.getKey(sourceBlock));
                }
            });
            CrashReportCategory.populateBlockDetails(crashreportsystemdetails, world, pos, state);
            throw new ReportedException(crashreport);
        }
    }
}
