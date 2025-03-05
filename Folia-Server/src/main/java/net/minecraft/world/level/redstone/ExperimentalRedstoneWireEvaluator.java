package net.minecraft.world.level.redstone;

import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.block.BlockRedstoneEvent;
// CraftBukkit end

public class ExperimentalRedstoneWireEvaluator extends RedstoneWireEvaluator {

    private final Deque<BlockPos> wiresToTurnOff = new ArrayDeque();
    private final Deque<BlockPos> wiresToTurnOn = new ArrayDeque();
    private final Object2IntMap<BlockPos> updatedWires = new Object2IntLinkedOpenHashMap();

    public ExperimentalRedstoneWireEvaluator(RedStoneWireBlock wire) {
        super(wire);
    }

    @Override
    public void updatePowerStrength(Level world, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
        Orientation orientation1 = ExperimentalRedstoneWireEvaluator.getInitialOrientation(world, orientation);

        this.calculateCurrentChanges(world, pos, orientation1);
        ObjectIterator<Entry<BlockPos>> objectiterator = this.updatedWires.object2IntEntrySet().iterator();

        for (boolean flag1 = true; objectiterator.hasNext(); flag1 = false) {
            Entry<BlockPos> entry = (Entry) objectiterator.next();
            BlockPos blockposition1 = (BlockPos) entry.getKey();
            int i = entry.getIntValue();
            int j = ExperimentalRedstoneWireEvaluator.unpackPower(i);
            BlockState iblockdata1 = world.getBlockState(blockposition1);

            // CraftBukkit start
            int oldPower = iblockdata1.getValue(RedStoneWireBlock.POWER); // Paper - Call BlockRedstoneEvent properly; get the previous power from the right state
            if (oldPower != j) {
                BlockRedstoneEvent event = new BlockRedstoneEvent(CraftBlock.at(world, blockposition1), oldPower, j);
                world.getCraftServer().getPluginManager().callEvent(event);

                j = event.getNewCurrent();
            }
            if (iblockdata1.is((Block) this.wireBlock) && oldPower != j) {
                // CraftBukkit end
                int k = 2;

                if (!blockAdded || !flag1) {
                    k |= 128;
                }

                world.setBlock(blockposition1, (BlockState) iblockdata1.setValue(RedStoneWireBlock.POWER, j), k);
            } else {
                objectiterator.remove();
            }
        }

        this.causeNeighborUpdates(world);
    }

    private void causeNeighborUpdates(Level world) {
        this.updatedWires.forEach((blockposition, integer) -> {
            Orientation orientation = ExperimentalRedstoneWireEvaluator.unpackOrientation(integer);
            BlockState iblockdata = world.getBlockState(blockposition);
            Iterator iterator = orientation.getDirections().iterator();

            while (iterator.hasNext()) {
                Direction enumdirection = (Direction) iterator.next();

                if (ExperimentalRedstoneWireEvaluator.isConnected(iblockdata, enumdirection)) {
                    BlockPos blockposition1 = blockposition.relative(enumdirection);
                    BlockState iblockdata1 = world.getBlockState(blockposition1);
                    Orientation orientation1 = orientation.withFrontPreserveUp(enumdirection);

                    world.neighborChanged(iblockdata1, blockposition1, this.wireBlock, orientation1, false);
                    if (iblockdata1.isRedstoneConductor(world, blockposition1)) {
                        Iterator iterator1 = orientation1.getDirections().iterator();

                        while (iterator1.hasNext()) {
                            Direction enumdirection1 = (Direction) iterator1.next();

                            if (enumdirection1 != enumdirection.getOpposite()) {
                                world.neighborChanged(blockposition1.relative(enumdirection1), this.wireBlock, orientation1.withFrontPreserveUp(enumdirection1));
                            }
                        }
                    }
                }
            }

        });
    }

    private static boolean isConnected(BlockState wireState, Direction direction) {
        EnumProperty<RedstoneSide> blockstateenum = (EnumProperty) RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction);

        return blockstateenum == null ? direction == Direction.DOWN : ((RedstoneSide) wireState.getValue(blockstateenum)).isConnected();
    }

    private static Orientation getInitialOrientation(Level world, @Nullable Orientation orientation) {
        Orientation orientation1;

        if (orientation != null) {
            orientation1 = orientation;
        } else {
            orientation1 = Orientation.random(world.random);
        }

        return orientation1.withUp(Direction.UP).withSideBias(Orientation.SideBias.LEFT);
    }

    private void calculateCurrentChanges(Level world, BlockPos pos, Orientation orientation) {
        BlockState iblockdata = world.getBlockState(pos);

        if (iblockdata.is((Block) this.wireBlock)) {
            this.setPower(pos, (Integer) iblockdata.getValue(RedStoneWireBlock.POWER), orientation);
            this.wiresToTurnOff.add(pos);
        } else {
            this.propagateChangeToNeighbors(world, pos, 0, orientation, true);
        }

        BlockPos blockposition1;
        int i;
        Orientation orientation1;
        int j;
        int k;
        int l;
        int i1;
        int j1;

        for (; !this.wiresToTurnOff.isEmpty(); this.propagateChangeToNeighbors(world, blockposition1, j1, orientation1, j > i1)) {
            blockposition1 = (BlockPos) this.wiresToTurnOff.removeFirst();
            i = this.updatedWires.getInt(blockposition1);
            orientation1 = ExperimentalRedstoneWireEvaluator.unpackOrientation(i);
            j = ExperimentalRedstoneWireEvaluator.unpackPower(i);
            k = this.getBlockSignal(world, blockposition1);
            l = this.getIncomingWireSignal(world, blockposition1);
            i1 = Math.max(k, l);
            if (i1 < j) {
                if (k > 0 && !this.wiresToTurnOn.contains(blockposition1)) {
                    this.wiresToTurnOn.add(blockposition1);
                }

                j1 = 0;
            } else {
                j1 = i1;
            }

            if (j1 != j) {
                this.setPower(blockposition1, j1, orientation1);
            }
        }

        Orientation orientation2;

        for (; !this.wiresToTurnOn.isEmpty(); this.propagateChangeToNeighbors(world, blockposition1, l, orientation2, false)) {
            blockposition1 = (BlockPos) this.wiresToTurnOn.removeFirst();
            i = this.updatedWires.getInt(blockposition1);
            int k1 = ExperimentalRedstoneWireEvaluator.unpackPower(i);

            j = this.getBlockSignal(world, blockposition1);
            k = this.getIncomingWireSignal(world, blockposition1);
            l = Math.max(j, k);
            orientation2 = ExperimentalRedstoneWireEvaluator.unpackOrientation(i);
            if (l > k1) {
                this.setPower(blockposition1, l, orientation2);
            } else if (l < k1) {
                throw new IllegalStateException("Turning off wire while trying to turn it on. Should not happen.");
            }
        }

    }

    private static int packOrientationAndPower(Orientation orientation, int power) {
        return orientation.getIndex() << 4 | power;
    }

    private static Orientation unpackOrientation(int packed) {
        return Orientation.fromIndex(packed >> 4);
    }

    private static int unpackPower(int packed) {
        return packed & 15;
    }

    private void setPower(BlockPos pos, int power, Orientation defaultOrientation) {
        this.updatedWires.compute(pos, (blockposition1, integer) -> {
            return integer == null ? ExperimentalRedstoneWireEvaluator.packOrientationAndPower(defaultOrientation, power) : ExperimentalRedstoneWireEvaluator.packOrientationAndPower(ExperimentalRedstoneWireEvaluator.unpackOrientation(integer), power);
        });
    }

    private void propagateChangeToNeighbors(Level world, BlockPos pos, int power, Orientation orientation, boolean canIncreasePower) {
        Iterator iterator = orientation.getHorizontalDirections().iterator();

        Direction enumdirection;
        BlockPos blockposition1;

        while (iterator.hasNext()) {
            enumdirection = (Direction) iterator.next();
            blockposition1 = pos.relative(enumdirection);
            this.enqueueNeighborWire(world, blockposition1, power, orientation.withFront(enumdirection), canIncreasePower);
        }

        iterator = orientation.getVerticalDirections().iterator();

        while (iterator.hasNext()) {
            enumdirection = (Direction) iterator.next();
            blockposition1 = pos.relative(enumdirection);
            boolean flag1 = world.getBlockState(blockposition1).isRedstoneConductor(world, blockposition1);
            Iterator iterator1 = orientation.getHorizontalDirections().iterator();

            while (iterator1.hasNext()) {
                Direction enumdirection1 = (Direction) iterator1.next();
                BlockPos blockposition2 = pos.relative(enumdirection1);
                BlockPos blockposition3;

                if (enumdirection == Direction.UP && !flag1) {
                    blockposition3 = blockposition1.relative(enumdirection1);
                    this.enqueueNeighborWire(world, blockposition3, power, orientation.withFront(enumdirection1), canIncreasePower);
                } else if (enumdirection == Direction.DOWN && !world.getBlockState(blockposition2).isRedstoneConductor(world, blockposition2)) {
                    blockposition3 = blockposition1.relative(enumdirection1);
                    this.enqueueNeighborWire(world, blockposition3, power, orientation.withFront(enumdirection1), canIncreasePower);
                }
            }
        }

    }

    private void enqueueNeighborWire(Level world, BlockPos neighborPos, int power, Orientation orientation, boolean canIncreasePower) {
        BlockState iblockdata = world.getBlockState(neighborPos);

        if (iblockdata.is((Block) this.wireBlock)) {
            int j = this.getWireSignal(neighborPos, iblockdata);

            if (j < power - 1 && !this.wiresToTurnOn.contains(neighborPos)) {
                this.wiresToTurnOn.add(neighborPos);
                this.setPower(neighborPos, j, orientation);
            }

            if (canIncreasePower && j > power && !this.wiresToTurnOff.contains(neighborPos)) {
                this.wiresToTurnOff.add(neighborPos);
                this.setPower(neighborPos, j, orientation);
            }
        }

    }

    @Override
    protected int getWireSignal(BlockPos world, BlockState pos) {
        int i = this.updatedWires.getOrDefault(world, -1);

        return i != -1 ? ExperimentalRedstoneWireEvaluator.unpackPower(i) : super.getWireSignal(world, pos);
    }
}
