package net.minecraft.world.level.redstone;

import com.google.common.collect.Sets;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.block.BlockRedstoneEvent;
// CraftBukkit end

public class DefaultRedstoneWireEvaluator extends RedstoneWireEvaluator {

    public DefaultRedstoneWireEvaluator(RedStoneWireBlock wire) {
        super(wire);
    }

    @Override
    public void updatePowerStrength(Level world, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
        int i = this.calculateTargetStrength(world, pos);

        // CraftBukkit start
        int oldPower = state.getValue(RedStoneWireBlock.POWER);
        if (oldPower != i) {
            BlockRedstoneEvent event = new BlockRedstoneEvent(CraftBlock.at(world, pos), oldPower, i);
            world.getCraftServer().getPluginManager().callEvent(event);

            i = event.getNewCurrent();
        }
        if (oldPower != i) {
            // CraftBukkit end
            if (world.getBlockState(pos) == state) {
                world.setBlock(pos, (BlockState) state.setValue(RedStoneWireBlock.POWER, i), 2);
            }

            Set<BlockPos> set = Sets.newHashSet();

            set.add(pos);
            Direction[] aenumdirection = Direction.values();
            int j = aenumdirection.length;

            for (int k = 0; k < j; ++k) {
                Direction enumdirection = aenumdirection[k];

                set.add(pos.relative(enumdirection));
            }

            Iterator iterator = set.iterator();

            while (iterator.hasNext()) {
                BlockPos blockposition1 = (BlockPos) iterator.next();

                world.updateNeighborsAt(blockposition1, this.wireBlock);
            }
        }

    }

    public int calculateTargetStrength(Level world, BlockPos pos) { // Paper - Optimize redstone
        int i = this.getBlockSignal(world, pos);

        return i == 15 ? i : Math.max(i, this.getIncomingWireSignal(world, pos));
    }
}
