package net.minecraft.world.level.material;

import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanMap;
import it.unimi.dsi.fastutil.shorts.Short2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
// CraftBukkit start
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
// CraftBukkit end

public abstract class FlowingFluid extends Fluid {

    public static final BooleanProperty FALLING = BlockStateProperties.FALLING;
    public static final IntegerProperty LEVEL = BlockStateProperties.LEVEL_FLOWING;
    private static final int CACHE_SIZE = 200;
    private static final ThreadLocal<Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>> OCCLUSION_CACHE = ThreadLocal.withInitial(() -> {
        Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey> object2bytelinkedopenhashmap = new Object2ByteLinkedOpenHashMap<FlowingFluid.BlockStatePairKey>(200) {
            protected void rehash(int i) {}
        };

        object2bytelinkedopenhashmap.defaultReturnValue((byte) 127);
        return object2bytelinkedopenhashmap;
    });
    private final Map<FluidState, VoxelShape> shapes = Maps.newIdentityHashMap();

    // Paper start - fluid method optimisations
    private FluidState sourceFalling;
    private FluidState sourceNotFalling;

    private static final int TOTAL_FLOWING_STATES = FALLING.getPossibleValues().size() * LEVEL.getPossibleValues().size();
    private static final int MIN_LEVEL = LEVEL.getPossibleValues().stream().sorted().findFirst().get().intValue();

    // index = (falling ? 1 : 0) + level*2
    private FluidState[] flowingLookUp;
    private volatile boolean init;

    private static final int COLLISION_OCCLUSION_CACHE_SIZE = 2048;
    private static final ThreadLocal<ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[]> COLLISION_OCCLUSION_CACHE = ThreadLocal.withInitial(() -> new ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[COLLISION_OCCLUSION_CACHE_SIZE]);


    /**
     * Due to init order, we need to use callbacks to initialise our state
     */
    private void init() {
        synchronized (this) {
            if (this.init) {
                return;
            }
            this.flowingLookUp = new FluidState[TOTAL_FLOWING_STATES];
            final FluidState defaultFlowState = this.getFlowing().defaultFluidState();
            for (int i = 0; i < TOTAL_FLOWING_STATES; ++i) {
                final int falling = i & 1;
                final int level = (i >>> 1) + MIN_LEVEL;

                this.flowingLookUp[i] = defaultFlowState.setValue(FALLING, falling == 1 ? Boolean.TRUE : Boolean.FALSE)
                    .setValue(LEVEL, Integer.valueOf(level));
            }

            final FluidState defaultFallState = this.getSource().defaultFluidState();
            this.sourceFalling = defaultFallState.setValue(FALLING, Boolean.TRUE);
            this.sourceNotFalling = defaultFallState.setValue(FALLING, Boolean.FALSE);

            this.init = true;
        }
    }
    // Paper end - fluid method optimisations

    public FlowingFluid() {}

    @Override
    protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> builder) {
        builder.add(FlowingFluid.FALLING);
    }

    @Override
    public Vec3 getFlow(BlockGetter world, BlockPos pos, FluidState state) {
        double d0 = 0.0D;
        double d1 = 0.0D;
        BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();
        Iterator iterator = Direction.Plane.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            Direction enumdirection = (Direction) iterator.next();

            blockposition_mutableblockposition.setWithOffset(pos, enumdirection);
            FluidState fluid1 = world.getFluidState(blockposition_mutableblockposition);

            if (this.affectsFlow(fluid1)) {
                float f = fluid1.getOwnHeight();
                float f1 = 0.0F;

                if (f == 0.0F) {
                    if (!world.getBlockState(blockposition_mutableblockposition).blocksMotion()) {
                        BlockPos blockposition1 = blockposition_mutableblockposition.below();
                        FluidState fluid2 = world.getFluidState(blockposition1);

                        if (this.affectsFlow(fluid2)) {
                            f = fluid2.getOwnHeight();
                            if (f > 0.0F) {
                                f1 = state.getOwnHeight() - (f - 0.8888889F);
                            }
                        }
                    }
                } else if (f > 0.0F) {
                    f1 = state.getOwnHeight() - f;
                }

                if (f1 != 0.0F) {
                    d0 += (double) ((float) enumdirection.getStepX() * f1);
                    d1 += (double) ((float) enumdirection.getStepZ() * f1);
                }
            }
        }

        Vec3 vec3d = new Vec3(d0, 0.0D, d1);

        if ((Boolean) state.getValue(FlowingFluid.FALLING)) {
            Iterator iterator1 = Direction.Plane.HORIZONTAL.iterator();

            while (iterator1.hasNext()) {
                Direction enumdirection1 = (Direction) iterator1.next();

                blockposition_mutableblockposition.setWithOffset(pos, enumdirection1);
                if (this.isSolidFace(world, blockposition_mutableblockposition, enumdirection1) || this.isSolidFace(world, blockposition_mutableblockposition.above(), enumdirection1)) {
                    vec3d = vec3d.normalize().add(0.0D, -6.0D, 0.0D);
                    break;
                }
            }
        }

        return vec3d.normalize();
    }

    private boolean affectsFlow(FluidState state) {
        return state.isEmpty() || state.getType().isSame(this);
    }

    protected boolean isSolidFace(BlockGetter world, BlockPos pos, Direction direction) {
        BlockState iblockdata = world.getBlockState(pos);
        FluidState fluid = world.getFluidState(pos);

        return fluid.getType().isSame(this) ? false : (direction == Direction.UP ? true : (iblockdata.getBlock() instanceof IceBlock ? false : iblockdata.isFaceSturdy(world, pos, direction)));
    }

    protected void spread(ServerLevel world, BlockPos fluidPos, BlockState blockState, FluidState fluidState) {
        if (!fluidState.isEmpty()) {
            BlockPos blockposition1 = fluidPos.below();
            BlockState iblockdata1 = world.getBlockState(blockposition1);
            FluidState fluid1 = iblockdata1.getFluidState();

            if (this.canMaybePassThrough(world, fluidPos, blockState, Direction.DOWN, blockposition1, iblockdata1, fluid1)) {
                FluidState fluid2 = this.getNewLiquid(world, blockposition1, iblockdata1);
                Fluid fluidtype = fluid2.getType();

                if (fluid1.canBeReplacedWith(world, blockposition1, fluidtype, Direction.DOWN) && FlowingFluid.canHoldSpecificFluid(world, blockposition1, iblockdata1, fluidtype)) {
                    // CraftBukkit start
                    org.bukkit.block.Block source = CraftBlock.at(world, fluidPos);
                    BlockFromToEvent event = new BlockFromToEvent(source, BlockFace.DOWN);
                    world.getCraftServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return;
                    }
                    // CraftBukkit end
                    this.spreadTo(world, blockposition1, iblockdata1, Direction.DOWN, fluid2);
                    if (this.sourceNeighborCount(world, fluidPos) >= 3) {
                        this.spreadToSides(world, fluidPos, fluidState, blockState);
                    }

                    return;
                }
            }

            if (fluidState.isSource() || !this.isWaterHole(world, fluidPos, blockState, blockposition1, iblockdata1)) {
                this.spreadToSides(world, fluidPos, fluidState, blockState);
            }

        }
    }

    private void spreadToSides(ServerLevel world, BlockPos pos, FluidState fluidState, BlockState blockState) {
        int i = fluidState.getAmount() - this.getDropOff(world);

        if ((Boolean) fluidState.getValue(FlowingFluid.FALLING)) {
            i = 7;
        }

        if (i > 0) {
            Map<Direction, FluidState> map = this.getSpread(world, pos, blockState);
            Iterator iterator = map.entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<Direction, FluidState> entry = (Entry) iterator.next();
                Direction enumdirection = (Direction) entry.getKey();
                FluidState fluid1 = (FluidState) entry.getValue();
                BlockPos blockposition1 = pos.relative(enumdirection);
                final BlockState blockStateIfLoaded = world.getBlockStateIfLoaded(blockposition1); // Paper - Prevent chunk loading from fluid flowing
                if (blockStateIfLoaded == null) continue; // Paper - Prevent chunk loading from fluid flowing

                // CraftBukkit start
                org.bukkit.block.Block source = CraftBlock.at(world, pos);
                BlockFromToEvent event = new BlockFromToEvent(source, org.bukkit.craftbukkit.block.CraftBlock.notchToBlockFace(enumdirection));
                world.getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    continue;
                }
                // CraftBukkit end
                this.spreadTo(world, blockposition1, blockStateIfLoaded, enumdirection, fluid1); // Paper - Prevent chunk loading from fluid flowing
            }

        }
    }

    protected FluidState getNewLiquid(ServerLevel world, BlockPos pos, BlockState state) {
        int i = 0;
        int j = 0;
        BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();
        Iterator iterator = Direction.Plane.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            Direction enumdirection = (Direction) iterator.next();
            BlockPos.MutableBlockPos blockposition_mutableblockposition1 = blockposition_mutableblockposition.setWithOffset(pos, enumdirection);
            BlockState iblockdata1 = world.getBlockStateIfLoaded(blockposition_mutableblockposition1); // Paper - Prevent chunk loading from fluid flowing
            if (iblockdata1 == null) continue; // Paper - Prevent chunk loading from fluid flowing
            FluidState fluid = iblockdata1.getFluidState();

            if (fluid.getType().isSame(this) && FlowingFluid.canPassThroughWall(enumdirection, world, pos, state, blockposition_mutableblockposition1, iblockdata1)) {
                if (fluid.isSource()) {
                    ++j;
                }

                i = Math.max(i, fluid.getAmount());
            }
        }

        if (j >= 2 && this.canConvertToSource(world)) {
            BlockState iblockdata2 = world.getBlockState(blockposition_mutableblockposition.setWithOffset(pos, Direction.DOWN));
            FluidState fluid1 = iblockdata2.getFluidState();

            if (iblockdata2.isSolid() || this.isSourceBlockOfThisType(fluid1)) {
                return this.getSource(false);
            }
        }

        BlockPos.MutableBlockPos blockposition_mutableblockposition2 = blockposition_mutableblockposition.setWithOffset(pos, Direction.UP);
        BlockState iblockdata3 = world.getBlockState(blockposition_mutableblockposition2);
        FluidState fluid2 = iblockdata3.getFluidState();

        if (!fluid2.isEmpty() && fluid2.getType().isSame(this) && FlowingFluid.canPassThroughWall(Direction.UP, world, pos, state, blockposition_mutableblockposition2, iblockdata3)) {
            return this.getFlowing(8, true);
        } else {
            int k = i - this.getDropOff(world);

            return k <= 0 ? Fluids.EMPTY.defaultFluidState() : this.getFlowing(k, false);
        }
    }

    // Paper start - fluid method optimisations
    private static boolean canPassThroughWall(final Direction direction, final BlockGetter level,
                                             final BlockPos fromPos, final BlockState fromState,
                                             final BlockPos toPos, final BlockState toState) {
        if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$emptyCollisionShape() & ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$emptyCollisionShape()) {
            // don't even try to cache simple cases
            return true;
        }

        if (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$occludesFullBlock() | ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$occludesFullBlock()) {
            // don't even try to cache simple cases
            return false;
        }

        final ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey[] cache = ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$hasCache() & ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$hasCache() ?
            COLLISION_OCCLUSION_CACHE.get() : null;

        final int keyIndex
            = (((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)fromState).moonrise$uniqueId1() ^ ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)toState).moonrise$uniqueId2() ^ ((ca.spottedleaf.moonrise.patches.collisions.util.CollisionDirection)(Object)direction).moonrise$uniqueId())
            & (COLLISION_OCCLUSION_CACHE_SIZE - 1);

        if (cache != null) {
            final ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey cached = cache[keyIndex];
            if (cached != null && cached.first() == fromState && cached.second() == toState && cached.direction() == direction) {
                return cached.result();
            }
        }

        final VoxelShape shape1 = fromState.getCollisionShape(level, fromPos);
        final VoxelShape shape2 = toState.getCollisionShape(level, toPos);

        final boolean result = !Shapes.mergedFaceOccludes(shape1, shape2, direction);

        if (cache != null) {
            // we can afford to replace in-use keys more often due to the excessive caching the collision patch does in mergedFaceOccludes
            cache[keyIndex] = new ca.spottedleaf.moonrise.patches.collisions.util.FluidOcclusionCacheKey(fromState, toState, direction, result);
        }

        return result;
    }
    // Paper end - fluid method optimisations

    public abstract Fluid getFlowing();

    public FluidState getFlowing(int level, boolean falling) {
        // Paper start - fluid method optimisations
        final int amount = level;
        if (!this.init) {
            this.init();
        }
        final int index = (falling ? 1 : 0) | ((amount - MIN_LEVEL) << 1);
        return this.flowingLookUp[index];
        // Paper end - fluid method optimisations
    }

    public abstract Fluid getSource();

    public FluidState getSource(boolean falling) {
        // Paper start - fluid method optimisations
        if (!this.init) {
            this.init();
        }
        return falling ? this.sourceFalling : this.sourceNotFalling;
        // Paper end - fluid method optimisations
    }

    protected abstract boolean canConvertToSource(ServerLevel world);

    protected void spreadTo(LevelAccessor world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState) {
        Block block = state.getBlock();

        if (block instanceof LiquidBlockContainer ifluidcontainer) {
            ifluidcontainer.placeLiquid(world, pos, state, fluidState);
        } else {
            if (!state.isAir()) {
                this.beforeDestroyingBlock(world, pos, state, pos.relative(direction.getOpposite())); // Paper - Add BlockBreakBlockEvent
            }

            world.setBlock(pos, fluidState.createLegacyBlock(), 3);
        }

    }

    protected void beforeDestroyingBlock(LevelAccessor world, BlockPos pos, BlockState state, BlockPos source) { beforeDestroyingBlock(world, pos, state); } // Paper - Add BlockBreakBlockEvent
    protected abstract void beforeDestroyingBlock(LevelAccessor world, BlockPos pos, BlockState state);

    protected int getSlopeDistance(LevelReader world, BlockPos pos, int i, Direction direction, BlockState state, FlowingFluid.SpreadContext spreadCache) {
        int j = 1000;
        Iterator iterator = Direction.Plane.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            Direction enumdirection1 = (Direction) iterator.next();

            if (enumdirection1 != direction) {
                BlockPos blockposition1 = pos.relative(enumdirection1);
                BlockState iblockdata1 = spreadCache.getBlockStateIfLoaded(blockposition1); // Paper - Prevent chunk loading from fluid flowing
                if (iblockdata1 == null) continue; // Paper - Prevent chunk loading from fluid flowing
                FluidState fluid = iblockdata1.getFluidState();

                if (this.canPassThrough(world, this.getFlowing(), pos, state, enumdirection1, blockposition1, iblockdata1, fluid)) {
                    if (spreadCache.isHole(blockposition1)) {
                        return i;
                    }

                    if (i < this.getSlopeFindDistance(world)) {
                        int k = this.getSlopeDistance(world, blockposition1, i + 1, enumdirection1.getOpposite(), iblockdata1, spreadCache);

                        if (k < j) {
                            j = k;
                        }
                    }
                }
            }
        }

        return j;
    }

    boolean isWaterHole(BlockGetter world, BlockPos pos, BlockState state, BlockPos fromPos, BlockState fromState) {
        return !FlowingFluid.canPassThroughWall(Direction.DOWN, world, pos, state, fromPos, fromState) ? false : (fromState.getFluidState().getType().isSame(this) ? true : FlowingFluid.canHoldFluid(world, fromPos, fromState, this.getFlowing()));
    }

    private boolean canPassThrough(BlockGetter world, Fluid fluid, BlockPos pos, BlockState state, Direction face, BlockPos fromPos, BlockState fromState, FluidState fluidState) {
        return this.canMaybePassThrough(world, pos, state, face, fromPos, fromState, fluidState) && FlowingFluid.canHoldSpecificFluid(world, fromPos, fromState, fluid);
    }

    private boolean canMaybePassThrough(BlockGetter world, BlockPos pos, BlockState state, Direction face, BlockPos fromPos, BlockState fromState, FluidState fluidState) {
        return !this.isSourceBlockOfThisType(fluidState) && FlowingFluid.canHoldAnyFluid(fromState) && FlowingFluid.canPassThroughWall(face, world, pos, state, fromPos, fromState);
    }

    private boolean isSourceBlockOfThisType(FluidState state) {
        return state.getType().isSame(this) && state.isSource();
    }

    protected abstract int getSlopeFindDistance(LevelReader world);

    private int sourceNeighborCount(LevelReader world, BlockPos pos) {
        int i = 0;
        Iterator iterator = Direction.Plane.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            Direction enumdirection = (Direction) iterator.next();
            BlockPos blockposition1 = pos.relative(enumdirection);
            FluidState fluid = world.getFluidState(blockposition1);

            if (this.isSourceBlockOfThisType(fluid)) {
                ++i;
            }
        }

        return i;
    }

    protected Map<Direction, FluidState> getSpread(ServerLevel world, BlockPos pos, BlockState state) {
        int i = 1000;
        Map<Direction, FluidState> map = Maps.newEnumMap(Direction.class);
        FlowingFluid.SpreadContext fluidtypeflowing_b = null;
        Iterator iterator = Direction.Plane.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            Direction enumdirection = (Direction) iterator.next();
            BlockPos blockposition1 = pos.relative(enumdirection);
            BlockState iblockdata1 = world.getBlockStateIfLoaded(blockposition1); // Paper - Prevent chunk loading from fluid flowing
            if (iblockdata1 == null) continue; // Paper - Prevent chunk loading from fluid flowing
            FluidState fluid = iblockdata1.getFluidState();

            if (this.canMaybePassThrough(world, pos, state, enumdirection, blockposition1, iblockdata1, fluid)) {
                FluidState fluid1 = this.getNewLiquid(world, blockposition1, iblockdata1);

                if (FlowingFluid.canHoldSpecificFluid(world, blockposition1, iblockdata1, fluid1.getType())) {
                    if (fluidtypeflowing_b == null) {
                        fluidtypeflowing_b = new FlowingFluid.SpreadContext(world, pos);
                    }

                    int j;

                    if (fluidtypeflowing_b.isHole(blockposition1)) {
                        j = 0;
                    } else {
                        j = this.getSlopeDistance(world, blockposition1, 1, enumdirection.getOpposite(), iblockdata1, fluidtypeflowing_b);
                    }

                    if (j < i) {
                        map.clear();
                    }

                    if (j <= i) {
                        if (fluid.canBeReplacedWith(world, blockposition1, fluid1.getType(), enumdirection)) {
                            map.put(enumdirection, fluid1);
                        }

                        i = j;
                    }
                }
            }
        }

        return map;
    }

    private static boolean canHoldAnyFluid(BlockState state) {
        Block block = state.getBlock();

        return block instanceof LiquidBlockContainer ? true : (state.blocksMotion() ? false : !(block instanceof DoorBlock) && !state.is(BlockTags.SIGNS) && !state.is(Blocks.LADDER) && !state.is(Blocks.SUGAR_CANE) && !state.is(Blocks.BUBBLE_COLUMN) && !state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL) && !state.is(Blocks.END_GATEWAY) && !state.is(Blocks.STRUCTURE_VOID));
    }

    private static boolean canHoldFluid(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
        return FlowingFluid.canHoldAnyFluid(state) && FlowingFluid.canHoldSpecificFluid(world, pos, state, fluid);
    }

    private static boolean canHoldSpecificFluid(BlockGetter world, BlockPos pos, BlockState state, Fluid fluid) {
        Block block = state.getBlock();

        if (block instanceof LiquidBlockContainer ifluidcontainer) {
            return ifluidcontainer.canPlaceLiquid((Player) null, world, pos, state, fluid);
        } else {
            return true;
        }
    }

    protected abstract int getDropOff(LevelReader world);

    protected int getSpreadDelay(Level world, BlockPos pos, FluidState oldState, FluidState newState) {
        return this.getTickDelay(world);
    }

    @Override
    public void tick(ServerLevel world, BlockPos pos, BlockState blockState, FluidState fluidState) {
        if (!fluidState.isSource()) {
            FluidState fluid1 = this.getNewLiquid(world, pos, world.getBlockState(pos));
            int i = this.getSpreadDelay(world, pos, fluidState, fluid1);

            if (fluid1.isEmpty()) {
                fluidState = fluid1;
                blockState = Blocks.AIR.defaultBlockState();
                // CraftBukkit start
                FluidLevelChangeEvent event = CraftEventFactory.callFluidLevelChangeEvent(world, pos, blockState);
                if (event.isCancelled()) {
                    return;
                }
                blockState = ((CraftBlockData) event.getNewData()).getState();
                // CraftBukkit end
                world.setBlock(pos, blockState, 3);
            } else if (!fluid1.equals(fluidState)) {
                fluidState = fluid1;
                blockState = fluid1.createLegacyBlock();
                // CraftBukkit start
                FluidLevelChangeEvent event = CraftEventFactory.callFluidLevelChangeEvent(world, pos, blockState);
                if (event.isCancelled()) {
                    return;
                }
                blockState = ((CraftBlockData) event.getNewData()).getState();
                // CraftBukkit end
                world.setBlock(pos, blockState, 3);
                world.scheduleTick(pos, fluid1.getType(), i);
            }
        }

        this.spread(world, pos, blockState, fluidState);
    }

    protected static int getLegacyLevel(FluidState state) {
        return state.isSource() ? 0 : 8 - Math.min(state.getAmount(), 8) + ((Boolean) state.getValue(FlowingFluid.FALLING) ? 8 : 0);
    }

    private static boolean hasSameAbove(FluidState state, BlockGetter world, BlockPos pos) {
        return state.getType().isSame(world.getFluidState(pos.above()).getType());
    }

    @Override
    public float getHeight(FluidState state, BlockGetter world, BlockPos pos) {
        return FlowingFluid.hasSameAbove(state, world, pos) ? 1.0F : state.getOwnHeight();
    }

    @Override
    public float getOwnHeight(FluidState state) {
        return (float) state.getAmount() / 9.0F;
    }

    @Override
    public abstract int getAmount(FluidState state);

    @Override
    public VoxelShape getShape(FluidState state, BlockGetter world, BlockPos pos) {
        return state.getAmount() == 9 && FlowingFluid.hasSameAbove(state, world, pos) ? Shapes.block() : (VoxelShape) this.shapes.computeIfAbsent(state, (fluid1) -> {
            return Shapes.box(0.0D, 0.0D, 0.0D, 1.0D, (double) fluid1.getHeight(world, pos), 1.0D);
        });
    }

    private static record BlockStatePairKey(BlockState first, BlockState second, Direction direction) {

        public boolean equals(Object object) {
            boolean flag;

            if (object instanceof FlowingFluid.BlockStatePairKey fluidtypeflowing_a) {
                if (this.first == fluidtypeflowing_a.first && this.second == fluidtypeflowing_a.second && this.direction == fluidtypeflowing_a.direction) {
                    flag = true;
                    return flag;
                }
            }

            flag = false;
            return flag;
        }

        public int hashCode() {
            int i = System.identityHashCode(this.first);

            i = 31 * i + System.identityHashCode(this.second);
            i = 31 * i + this.direction.hashCode();
            return i;
        }
    }

    protected class SpreadContext {

        private final BlockGetter level;
        private final BlockPos origin;
        private final Short2ObjectMap<BlockState> stateCache = new Short2ObjectOpenHashMap();
        private final Short2BooleanMap holeCache = new Short2BooleanOpenHashMap();

        SpreadContext(final BlockGetter iblockaccess, final BlockPos blockposition) {
            this.level = iblockaccess;
            this.origin = blockposition;
        }

        public BlockState getBlockState(BlockPos pos) {
            return this.getBlockState(pos, this.getCacheKey(pos));
        }
        // Paper start - Prevent chunk loading from fluid flowing
        public @javax.annotation.Nullable BlockState getBlockStateIfLoaded(BlockPos pos) {
            return this.getBlockState(pos, this.getCacheKey(pos), false);
        }
        // Paper end - Prevent chunk loading from fluid flowing

        private BlockState getBlockState(BlockPos pos, short packed) {
        // Paper start - Prevent chunk loading from fluid flowing
            return getBlockState(pos, packed, true);
        }
        private @javax.annotation.Nullable BlockState getBlockState(BlockPos pos, short packed, boolean load) {
            BlockState blockState = this.stateCache.get(packed);
            if (blockState == null) {
                blockState = load ? level.getBlockState(pos) : level.getBlockStateIfLoaded(pos);
                if (blockState != null) {
                    this.stateCache.put(packed, blockState);
                }
            }
            return blockState;
        // Paper end - Prevent chunk loading from fluid flowing
        }

        public boolean isHole(BlockPos pos) {
            return this.holeCache.computeIfAbsent(this.getCacheKey(pos), (short0) -> {
                BlockState iblockdata = this.getBlockState(pos, short0);
                BlockPos blockposition1 = pos.below();
                BlockState iblockdata1 = this.level.getBlockState(blockposition1);

                return FlowingFluid.this.isWaterHole(this.level, pos, iblockdata, blockposition1, iblockdata1);
            });
        }

        private short getCacheKey(BlockPos pos) {
            int i = pos.getX() - this.origin.getX();
            int j = pos.getZ() - this.origin.getZ();

            return (short) ((i + 128 & 255) << 8 | j + 128 & 255);
        }
    }
}
