package net.minecraft.world.level.redstone;

import com.mojang.logging.LogUtils;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

public class CollectingNeighborUpdater implements NeighborUpdater {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Level level;
    private final int maxChainedNeighborUpdates;
    private final ArrayDeque<CollectingNeighborUpdater.NeighborUpdates> stack = new ArrayDeque<>();
    private final List<CollectingNeighborUpdater.NeighborUpdates> addedThisLayer = new ArrayList<>();
    private int count = 0;

    public CollectingNeighborUpdater(Level world, int maxChainDepth) {
        this.level = world;
        this.maxChainedNeighborUpdates = maxChainDepth;
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int flags, int maxUpdateDepth) {
        this.addAndRun(
            pos, new CollectingNeighborUpdater.ShapeUpdate(direction, neighborState, pos.immutable(), neighborPos.immutable(), flags, maxUpdateDepth)
        );
    }

    @Override
    public void neighborChanged(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation) {
        this.addAndRun(pos, new CollectingNeighborUpdater.SimpleNeighborUpdate(pos, sourceBlock, orientation));
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, boolean notify) {
        this.addAndRun(pos, new CollectingNeighborUpdater.FullNeighborUpdate(state, pos.immutable(), sourceBlock, orientation, notify));
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block sourceBlock, @Nullable Direction except, @Nullable Orientation orientation) {
        this.addAndRun(pos, new CollectingNeighborUpdater.MultiNeighborUpdate(pos.immutable(), sourceBlock, orientation, except));
    }

    private void addAndRun(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates entry) {
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread((net.minecraft.server.level.ServerLevel)this.level, pos, "Adding block without owning region"); // Folia - region threading
        boolean bl = this.count > 0;
        boolean bl2 = this.maxChainedNeighborUpdates >= 0 && this.count >= this.maxChainedNeighborUpdates;
        this.count++;
        if (!bl2) {
            if (bl) {
                this.addedThisLayer.add(entry);
            } else {
                this.stack.push(entry);
            }
        } else if (this.count - 1 == this.maxChainedNeighborUpdates) {
            LOGGER.error("Too many chained neighbor updates. Skipping the rest. First skipped position: " + pos.toShortString());
        }

        if (!bl) {
            this.runUpdates();
        }
    }

    private void runUpdates() {
        try {
            while (!this.stack.isEmpty() || !this.addedThisLayer.isEmpty()) {
                for (int i = this.addedThisLayer.size() - 1; i >= 0; i--) {
                    this.stack.push(this.addedThisLayer.get(i));
                }

                this.addedThisLayer.clear();
                CollectingNeighborUpdater.NeighborUpdates neighborUpdates = this.stack.peek();

                while (this.addedThisLayer.isEmpty()) {
                    if (!neighborUpdates.runNext(this.level)) {
                        this.stack.pop();
                        break;
                    }
                }
            }
        } finally {
            this.stack.clear();
            this.addedThisLayer.clear();
            this.count = 0;
        }
    }

    static record FullNeighborUpdate(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston)
        implements CollectingNeighborUpdater.NeighborUpdates {
        @Override
        public boolean runNext(Level world) {
            NeighborUpdater.executeUpdate(world, this.state, this.pos, this.block, this.orientation, this.movedByPiston);
            return false;
        }
    }

    static final class MultiNeighborUpdate implements CollectingNeighborUpdater.NeighborUpdates {
        private final BlockPos sourcePos;
        private final Block sourceBlock;
        @Nullable
        private Orientation orientation;
        @Nullable
        private final Direction skipDirection;
        private int idx = 0;

        MultiNeighborUpdate(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, @Nullable Direction except) {
            this.sourcePos = pos;
            this.sourceBlock = sourceBlock;
            this.orientation = orientation;
            this.skipDirection = except;
            if (NeighborUpdater.UPDATE_ORDER[this.idx] == except) {
                this.idx++;
            }
        }

        @Override
        public boolean runNext(Level world) {
            Direction direction = NeighborUpdater.UPDATE_ORDER[this.idx++];
            BlockPos blockPos = this.sourcePos.relative(direction);
            BlockState blockState = !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((net.minecraft.server.level.ServerLevel)world, blockPos) ? null : world.getBlockStateIfLoaded(blockPos); // Folia - block updates in unloaded chunks
            if (blockState != null) { // Folia - block updates in unloaded chunks
            Orientation orientation = null;
            if (world.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS)) {
                if (this.orientation == null) {
                    this.orientation = ExperimentalRedstoneUtils.initialOrientation(
                        world, this.skipDirection == null ? null : this.skipDirection.getOpposite(), null
                    );
                }

                orientation = this.orientation.withFront(direction);
            }

            NeighborUpdater.executeUpdate(world, blockState, blockPos, this.sourceBlock, orientation, false, this.sourcePos); // Paper - Add source block to BlockPhysicsEvent
            } // Folia - block updates in unloaded chunks
            if (this.idx < NeighborUpdater.UPDATE_ORDER.length && NeighborUpdater.UPDATE_ORDER[this.idx] == this.skipDirection) {
                this.idx++;
            }

            return this.idx < NeighborUpdater.UPDATE_ORDER.length;
        }
    }

    interface NeighborUpdates {
        boolean runNext(Level world);
    }

    static record ShapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos, int updateFlags, int updateLimit)
        implements CollectingNeighborUpdater.NeighborUpdates {
        @Override
        public boolean runNext(Level world) {
            if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((net.minecraft.server.level.ServerLevel)world, this.pos) && world.getChunkIfLoaded(this.pos) != null) { // Folia - block updates in unloaded chunks
            NeighborUpdater.executeShapeUpdate(world, this.direction, this.pos, this.neighborPos, this.neighborState, this.updateFlags, this.updateLimit);
            } // Folia - block updates in unloaded chunks
            return false;
        }
    }

    static record SimpleNeighborUpdate(BlockPos pos, Block block, @Nullable Orientation orientation) implements CollectingNeighborUpdater.NeighborUpdates {
        @Override
        public boolean runNext(Level world) {
            BlockState blockState = !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((net.minecraft.server.level.ServerLevel)world, this.pos) ? null : world.getBlockStateIfLoaded(this.pos); // Folia - block updates in unloaded chunks
            if (blockState != null) NeighborUpdater.executeUpdate(world, blockState, this.pos, this.block, this.orientation, false); // Folia - block updates in unloaded chunks
            return false;
        }
    }
}
