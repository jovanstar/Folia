package net.minecraft.world.level.block;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.serialization.MapCodec;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.ExperimentalRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class RedStoneWireBlock extends Block {
    public static final MapCodec<RedStoneWireBlock> CODEC = simpleCodec(RedStoneWireBlock::new);
    public static final EnumProperty<RedstoneSide> NORTH = BlockStateProperties.NORTH_REDSTONE;
    public static final EnumProperty<RedstoneSide> EAST = BlockStateProperties.EAST_REDSTONE;
    public static final EnumProperty<RedstoneSide> SOUTH = BlockStateProperties.SOUTH_REDSTONE;
    public static final EnumProperty<RedstoneSide> WEST = BlockStateProperties.WEST_REDSTONE;
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    public static final Map<Direction, EnumProperty<RedstoneSide>> PROPERTY_BY_DIRECTION = Maps.newEnumMap(
        ImmutableMap.of(Direction.NORTH, NORTH, Direction.EAST, EAST, Direction.SOUTH, SOUTH, Direction.WEST, WEST)
    );
    protected static final int H = 1;
    protected static final int W = 3;
    protected static final int E = 13;
    protected static final int N = 3;
    protected static final int S = 13;
    private static final VoxelShape SHAPE_DOT = Block.box(3.0, 0.0, 3.0, 13.0, 1.0, 13.0);
    private static final Map<Direction, VoxelShape> SHAPES_FLOOR = Maps.newEnumMap(
        ImmutableMap.of(
            Direction.NORTH,
            Block.box(3.0, 0.0, 0.0, 13.0, 1.0, 13.0),
            Direction.SOUTH,
            Block.box(3.0, 0.0, 3.0, 13.0, 1.0, 16.0),
            Direction.EAST,
            Block.box(3.0, 0.0, 3.0, 16.0, 1.0, 13.0),
            Direction.WEST,
            Block.box(0.0, 0.0, 3.0, 13.0, 1.0, 13.0)
        )
    );
    private static final Map<Direction, VoxelShape> SHAPES_UP = Maps.newEnumMap(
        ImmutableMap.of(
            Direction.NORTH,
            Shapes.or(SHAPES_FLOOR.get(Direction.NORTH), Block.box(3.0, 0.0, 0.0, 13.0, 16.0, 1.0)),
            Direction.SOUTH,
            Shapes.or(SHAPES_FLOOR.get(Direction.SOUTH), Block.box(3.0, 0.0, 15.0, 13.0, 16.0, 16.0)),
            Direction.EAST,
            Shapes.or(SHAPES_FLOOR.get(Direction.EAST), Block.box(15.0, 0.0, 3.0, 16.0, 16.0, 13.0)),
            Direction.WEST,
            Shapes.or(SHAPES_FLOOR.get(Direction.WEST), Block.box(0.0, 0.0, 3.0, 1.0, 16.0, 13.0))
        )
    );
    private static final Map<BlockState, VoxelShape> SHAPES_CACHE = Maps.newHashMap();
    private static final int[] COLORS = Util.make(new int[16], colors -> {
        for (int i = 0; i <= 15; i++) {
            float f = (float)i / 15.0F;
            float g = f * 0.6F + (f > 0.0F ? 0.4F : 0.3F);
            float h = Mth.clamp(f * f * 0.7F - 0.5F, 0.0F, 1.0F);
            float j = Mth.clamp(f * f * 0.6F - 0.7F, 0.0F, 1.0F);
            colors[i] = ARGB.colorFromFloat(1.0F, g, h, j);
        }
    });
    private static final float PARTICLE_DENSITY = 0.2F;
    private final BlockState crossState;
    private final RedstoneWireEvaluator evaluator = new DefaultRedstoneWireEvaluator(this);
    //public boolean shouldSignal = true; // Folia - region threading - move to regionised world data

    @Override
    public MapCodec<RedStoneWireBlock> codec() {
        return CODEC;
    }

    public RedStoneWireBlock(BlockBehaviour.Properties settings) {
        super(settings);
        this.registerDefaultState(
            this.stateDefinition
                .any()
                .setValue(NORTH, RedstoneSide.NONE)
                .setValue(EAST, RedstoneSide.NONE)
                .setValue(SOUTH, RedstoneSide.NONE)
                .setValue(WEST, RedstoneSide.NONE)
                .setValue(POWER, Integer.valueOf(0))
        );
        this.crossState = this.defaultBlockState()
            .setValue(NORTH, RedstoneSide.SIDE)
            .setValue(EAST, RedstoneSide.SIDE)
            .setValue(SOUTH, RedstoneSide.SIDE)
            .setValue(WEST, RedstoneSide.SIDE);

        for (BlockState blockState : this.getStateDefinition().getPossibleStates()) {
            if (blockState.getValue(POWER) == 0) {
                SHAPES_CACHE.put(blockState, this.calculateShape(blockState));
            }
        }
    }

    private VoxelShape calculateShape(BlockState state) {
        VoxelShape voxelShape = SHAPE_DOT;

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            RedstoneSide redstoneSide = state.getValue(PROPERTY_BY_DIRECTION.get(direction));
            if (redstoneSide == RedstoneSide.SIDE) {
                voxelShape = Shapes.or(voxelShape, SHAPES_FLOOR.get(direction));
            } else if (redstoneSide == RedstoneSide.UP) {
                voxelShape = Shapes.or(voxelShape, SHAPES_UP.get(direction));
            }
        }

        return voxelShape;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPES_CACHE.get(state.setValue(POWER, Integer.valueOf(0)));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return this.getConnectionState(ctx.getLevel(), this.crossState, ctx.getClickedPos());
    }

    private BlockState getConnectionState(BlockGetter world, BlockState state, BlockPos pos) {
        boolean bl = isDot(state);
        state = this.getMissingConnections(world, this.defaultBlockState().setValue(POWER, state.getValue(POWER)), pos);
        if (bl && isDot(state)) {
            return state;
        } else {
            boolean bl2 = state.getValue(NORTH).isConnected();
            boolean bl3 = state.getValue(SOUTH).isConnected();
            boolean bl4 = state.getValue(EAST).isConnected();
            boolean bl5 = state.getValue(WEST).isConnected();
            boolean bl6 = !bl2 && !bl3;
            boolean bl7 = !bl4 && !bl5;
            if (!bl5 && bl6) {
                state = state.setValue(WEST, RedstoneSide.SIDE);
            }

            if (!bl4 && bl6) {
                state = state.setValue(EAST, RedstoneSide.SIDE);
            }

            if (!bl2 && bl7) {
                state = state.setValue(NORTH, RedstoneSide.SIDE);
            }

            if (!bl3 && bl7) {
                state = state.setValue(SOUTH, RedstoneSide.SIDE);
            }

            return state;
        }
    }

    private BlockState getMissingConnections(BlockGetter world, BlockState state, BlockPos pos) {
        boolean bl = !world.getBlockState(pos.above()).isRedstoneConductor(world, pos);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!state.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected()) {
                RedstoneSide redstoneSide = this.getConnectingSide(world, pos, direction, bl);
                state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), redstoneSide);
            }
        }

        return state;
    }

    @Override
    protected BlockState updateShape(
        BlockState state,
        LevelReader world,
        ScheduledTickAccess tickView,
        BlockPos pos,
        Direction direction,
        BlockPos neighborPos,
        BlockState neighborState,
        RandomSource random
    ) {
        if (direction == Direction.DOWN) {
            return !this.canSurviveOn(world, neighborPos, neighborState) ? Blocks.AIR.defaultBlockState() : state;
        } else if (direction == Direction.UP) {
            return this.getConnectionState(world, state, pos);
        } else {
            RedstoneSide redstoneSide = this.getConnectingSide(world, pos, direction);
            return redstoneSide.isConnected() == state.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected() && !isCross(state)
                ? state.setValue(PROPERTY_BY_DIRECTION.get(direction), redstoneSide)
                : this.getConnectionState(
                    world, this.crossState.setValue(POWER, state.getValue(POWER)).setValue(PROPERTY_BY_DIRECTION.get(direction), redstoneSide), pos
                );
        }
    }

    private static boolean isCross(BlockState state) {
        return state.getValue(NORTH).isConnected()
            && state.getValue(SOUTH).isConnected()
            && state.getValue(EAST).isConnected()
            && state.getValue(WEST).isConnected();
    }

    private static boolean isDot(BlockState state) {
        return !state.getValue(NORTH).isConnected()
            && !state.getValue(SOUTH).isConnected()
            && !state.getValue(EAST).isConnected()
            && !state.getValue(WEST).isConnected();
    }

    @Override
    protected void updateIndirectNeighbourShapes(BlockState state, LevelAccessor world, BlockPos pos, int flags, int maxUpdateDepth) {
        BlockPos.MutableBlockPos mutableBlockPos = new BlockPos.MutableBlockPos();

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            RedstoneSide redstoneSide = state.getValue(PROPERTY_BY_DIRECTION.get(direction));
            BlockState currState; mutableBlockPos.setWithOffset(pos, direction); // Folia - block updates in unloaded chunks
            if (redstoneSide != RedstoneSide.NONE && (currState = (world instanceof net.minecraft.server.level.ServerLevel serverLevel && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(serverLevel, pos) ? null : world.getBlockStateIfLoaded(mutableBlockPos.setWithOffset(pos, direction)))) != null && !currState.is(this)) { // Folia - block updates in unloaded chunks
                mutableBlockPos.move(Direction.DOWN);
                BlockState blockState = world.getBlockState(mutableBlockPos);
                if (blockState.is(this)) {
                    BlockPos blockPos = mutableBlockPos.relative(direction.getOpposite());
                    world.neighborShapeChanged(direction.getOpposite(), mutableBlockPos, blockPos, world.getBlockState(blockPos), flags, maxUpdateDepth);
                }

                mutableBlockPos.setWithOffset(pos, direction).move(Direction.UP);
                BlockState blockState2 = world.getBlockState(mutableBlockPos);
                if (blockState2.is(this)) {
                    BlockPos blockPos2 = mutableBlockPos.relative(direction.getOpposite());
                    world.neighborShapeChanged(direction.getOpposite(), mutableBlockPos, blockPos2, world.getBlockState(blockPos2), flags, maxUpdateDepth);
                }
            }
        }
    }

    private RedstoneSide getConnectingSide(BlockGetter world, BlockPos pos, Direction direction) {
        return this.getConnectingSide(world, pos, direction, !world.getBlockState(pos.above()).isRedstoneConductor(world, pos));
    }

    private RedstoneSide getConnectingSide(BlockGetter world, BlockPos pos, Direction direction, boolean bl) {
        BlockPos blockPos = pos.relative(direction);
        BlockState blockState = world.getBlockState(blockPos);
        if (bl) {
            boolean bl2 = blockState.getBlock() instanceof TrapDoorBlock || this.canSurviveOn(world, blockPos, blockState);
            if (bl2 && shouldConnectTo(world.getBlockState(blockPos.above()))) {
                if (blockState.isFaceSturdy(world, blockPos, direction.getOpposite())) {
                    return RedstoneSide.UP;
                }

                return RedstoneSide.SIDE;
            }
        }

        return !shouldConnectTo(blockState, direction)
                && (blockState.isRedstoneConductor(world, blockPos) || !shouldConnectTo(world.getBlockState(blockPos.below())))
            ? RedstoneSide.NONE
            : RedstoneSide.SIDE;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader world, BlockPos pos) {
        BlockPos blockPos = pos.below();
        BlockState blockState = world.getBlockState(blockPos);
        return this.canSurviveOn(world, blockPos, blockState);
    }

    private boolean canSurviveOn(BlockGetter world, BlockPos pos, BlockState floor) {
        return floor.isFaceSturdy(world, pos, Direction.UP) || floor.is(Blocks.HOPPER);
    }

    // Paper start - Optimize redstone (Eigencraft)
    // The bulk of the new functionality is found in RedstoneWireTurbo.java
    // Folia start - region threading
    private com.destroystokyo.paper.util.RedstoneWireTurbo getTurbo(Level world) {
        return world.getCurrentWorldData().turbo;
    }
    // Folia end - region threading

    /*
     * Modified version of pre-existing updateSurroundingRedstone, which is called from
     * this.neighborChanged and a few other methods in this class.
     * Note: Added 'source' argument so as to help determine direction of information flow
     */
    private void updateSurroundingRedstone(Level worldIn, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
        if (worldIn.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.EIGENCRAFT) {
            // since 24w33a the source pos is no longer given, but instead an Orientation parameter
            // when this is not null, it can be used to find the source pos, which the turbo uses
            // to find the direction of information flow
            BlockPos source = null;
            if (orientation != null) {
                source = pos.relative(orientation.getFront().getOpposite());
            }
            getTurbo(worldIn).updateSurroundingRedstone(worldIn, pos, state, source); // Folia - region threading
            return;
        }
        updatePowerStrength(worldIn, pos, state, orientation, blockAdded);
    }

    /*
     * This method computes a wire's target strength and updates the given block state.
     * It uses the DefaultRedstoneWireEvaluator for this, which is identical to code
     * that was present in this class prior to the introduction of the experimental redstone
     * changes in 24w33a.
     * The previous implementation of this method in this patch had optimizations that have
     * not been relevant since 1.13, thus it has been greatly simplified.
     */
    public BlockState calculateCurrentChanges(Level level, BlockPos pos, BlockState state) {
        int oldPower = state.getValue(POWER);
        int newPower = ((DefaultRedstoneWireEvaluator) evaluator).calculateTargetStrength(level, pos);
        if (oldPower != newPower) {
            org.bukkit.event.block.BlockRedstoneEvent event = new org.bukkit.event.block.BlockRedstoneEvent(org.bukkit.craftbukkit.block.CraftBlock.at(level, pos), oldPower, newPower);
            level.getCraftServer().getPluginManager().callEvent(event);

            newPower = event.getNewCurrent();

            if (level.getBlockState(pos) == state) {
                state = state.setValue(POWER, newPower);
                // [Space Walker] suppress shape updates and emit those manually to
                // bypass the new neighbor update stack.
                if (level.setBlock(pos, state, Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_CLIENTS)) {
                    this.getTurbo(level).updateNeighborShapes(level, pos, state); // Folia - region threading
                }
            }
        }
        return state;
    }
    // Paper end

    private void updatePowerStrength(Level world, BlockPos pos, BlockState state, @Nullable Orientation orientation, boolean blockAdded) {
        if (useExperimentalEvaluator(world)) {
            new ExperimentalRedstoneWireEvaluator(this).updatePowerStrength(world, pos, state, orientation, blockAdded);
        } else {
            this.evaluator.updatePowerStrength(world, pos, state, orientation, blockAdded);
        }
    }

    public int getBlockSignal(Level world, BlockPos pos) {
        io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal = false; // Folia - region threading
        int i = world.getBestNeighborSignal(pos);
        io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal = true; // Folia - region threading
        return i;
    }

    private void checkCornerChangeAt(Level world, BlockPos pos) {
        if (world.getBlockState(pos).is(this)) {
            world.updateNeighborsAt(pos, this);

            for (Direction direction : Direction.values()) {
                world.updateNeighborsAt(pos.relative(direction), this);
            }
        }
    }

    @Override
    protected void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean notify) {
        if (!oldState.is(state.getBlock()) && !world.isClientSide) {
            // Paper start - optimize redstone - replace call to updatePowerStrength
            if (world.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                world.getWireHandler().onWireAdded(pos, state); // Alternate Current
            } else {
                this.updateSurroundingRedstone(world, pos, state, null, true); // Vanilla/Eigencraft
            }
            // Paper end

            for (Direction direction : Direction.Plane.VERTICAL) {
                world.updateNeighborsAt(pos.relative(direction), this);
            }

            this.updateNeighborsOfNeighboringWires(world, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean moved) {
        if (!moved && !state.is(newState.getBlock())) {
            super.onRemove(state, world, pos, newState, moved);
            if (!world.isClientSide) {
                for (Direction direction : Direction.values()) {
                    world.updateNeighborsAt(pos.relative(direction), this);
                }

                // Paper start - optimize redstone - replace call to updatePowerStrength
                if (world.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                    world.getWireHandler().onWireRemoved(pos, state); // Alternate Current
                } else {
                    this.updateSurroundingRedstone(world, pos, state, null, false); // Vanilla/Eigencraft
                }
                this.updateNeighborsOfNeighboringWires(world, pos);
            }
        }
    }

    private void updateNeighborsOfNeighboringWires(Level world, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            this.checkCornerChangeAt(world, pos.relative(direction));
        }

        for (Direction direction2 : Direction.Plane.HORIZONTAL) {
            BlockPos blockPos = pos.relative(direction2);
            if (world.getBlockState(blockPos).isRedstoneConductor(world, blockPos)) {
                this.checkCornerChangeAt(world, blockPos.above());
            } else {
                this.checkCornerChangeAt(world, blockPos.below());
            }
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level world, BlockPos pos, Block sourceBlock, @Nullable Orientation wireOrientation, boolean notify) {
        if (!world.isClientSide) {
            // Paper start - optimize redstone (Alternate Current)
            // Alternate Current handles breaking of redstone wires in the WireHandler.
            if (world.paperConfig().misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                world.getWireHandler().onWireUpdated(pos, state, wireOrientation);
            } else
            // Paper end - optimize redstone (Alternate Current)
            if (sourceBlock != this || !useExperimentalEvaluator(world)) {
                if (state.canSurvive(world, pos)) {
                    this.updateSurroundingRedstone(world, pos, state, wireOrientation, false); // Paper - Optimize redstone (Eigencraft)
                } else {
                    dropResources(state, world, pos);
                    world.removeBlock(pos, false);
                }
            }
        }
    }

    private static boolean useExperimentalEvaluator(Level world) {
        return world.enabledFeatures().contains(FeatureFlags.REDSTONE_EXPERIMENTS);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
        return !io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal ? 0 : state.getSignal(world, pos, direction); // Folia - region threading
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
        if (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().shouldSignal && direction != Direction.DOWN) { // Folia - region threading
            int i = state.getValue(POWER);
            if (i == 0) {
                return 0;
            } else {
                return direction != Direction.UP
                        && !this.getConnectionState(world, state, pos).getValue(PROPERTY_BY_DIRECTION.get(direction.getOpposite())).isConnected()
                    ? 0
                    : i;
            }
        } else {
            return 0;
        }
    }

    protected static boolean shouldConnectTo(BlockState state) {
        return shouldConnectTo(state, null);
    }

    protected static boolean shouldConnectTo(BlockState state, @Nullable Direction dir) {
        if (state.is(Blocks.REDSTONE_WIRE)) {
            return true;
        } else if (state.is(Blocks.REPEATER)) {
            Direction direction = state.getValue(RepeaterBlock.FACING);
            return direction == dir || direction.getOpposite() == dir;
        } else {
            return state.is(Blocks.OBSERVER) ? dir == state.getValue(ObserverBlock.FACING) : state.isSignalSource() && dir != null;
        }
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData();
        return worldData == null || worldData.shouldSignal;
        // Folia end - region threading
    }

    public static int getColorForPower(int powerLevel) {
        return COLORS[powerLevel];
    }

    private static void spawnParticlesAlongLine(
        Level world, RandomSource random, BlockPos pos, int color, Direction perpendicular, Direction direction, float minOffset, float maxOffset
    ) {
        float f = maxOffset - minOffset;
        if (!(random.nextFloat() >= 0.2F * f)) {
            float g = 0.4375F;
            float h = minOffset + f * random.nextFloat();
            double d = 0.5 + (double)(0.4375F * (float)perpendicular.getStepX()) + (double)(h * (float)direction.getStepX());
            double e = 0.5 + (double)(0.4375F * (float)perpendicular.getStepY()) + (double)(h * (float)direction.getStepY());
            double i = 0.5 + (double)(0.4375F * (float)perpendicular.getStepZ()) + (double)(h * (float)direction.getStepZ());
            world.addParticle(new DustParticleOptions(color, 1.0F), (double)pos.getX() + d, (double)pos.getY() + e, (double)pos.getZ() + i, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        int i = state.getValue(POWER);
        if (i != 0) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                RedstoneSide redstoneSide = state.getValue(PROPERTY_BY_DIRECTION.get(direction));
                switch (redstoneSide) {
                    case UP:
                        spawnParticlesAlongLine(world, random, pos, COLORS[i], direction, Direction.UP, -0.5F, 0.5F);
                    case SIDE:
                        spawnParticlesAlongLine(world, random, pos, COLORS[i], Direction.DOWN, direction, 0.0F, 0.5F);
                        break;
                    case NONE:
                    default:
                        spawnParticlesAlongLine(world, random, pos, COLORS[i], Direction.DOWN, direction, 0.0F, 0.3F);
                }
            }
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        switch (rotation) {
            case CLOCKWISE_180:
                return state.setValue(NORTH, state.getValue(SOUTH))
                    .setValue(EAST, state.getValue(WEST))
                    .setValue(SOUTH, state.getValue(NORTH))
                    .setValue(WEST, state.getValue(EAST));
            case COUNTERCLOCKWISE_90:
                return state.setValue(NORTH, state.getValue(EAST))
                    .setValue(EAST, state.getValue(SOUTH))
                    .setValue(SOUTH, state.getValue(WEST))
                    .setValue(WEST, state.getValue(NORTH));
            case CLOCKWISE_90:
                return state.setValue(NORTH, state.getValue(WEST))
                    .setValue(EAST, state.getValue(NORTH))
                    .setValue(SOUTH, state.getValue(EAST))
                    .setValue(WEST, state.getValue(SOUTH));
            default:
                return state;
        }
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        switch (mirror) {
            case LEFT_RIGHT:
                return state.setValue(NORTH, state.getValue(SOUTH)).setValue(SOUTH, state.getValue(NORTH));
            case FRONT_BACK:
                return state.setValue(EAST, state.getValue(WEST)).setValue(WEST, state.getValue(EAST));
            default:
                return super.mirror(state, mirror);
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, POWER);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.getAbilities().mayBuild) {
            return InteractionResult.PASS;
        } else {
            if (isCross(state) || isDot(state)) {
                BlockState blockState = isCross(state) ? this.defaultBlockState() : this.crossState;
                blockState = blockState.setValue(POWER, state.getValue(POWER));
                blockState = this.getConnectionState(world, blockState, pos);
                if (blockState != state) {
                    world.setBlock(pos, blockState, 3);
                    this.updatesOnShapeChange(world, pos, state, blockState);
                    return InteractionResult.SUCCESS;
                }
            }

            return InteractionResult.PASS;
        }
    }

    private void updatesOnShapeChange(Level world, BlockPos pos, BlockState oldState, BlockState newState) {
        Orientation orientation = ExperimentalRedstoneUtils.initialOrientation(world, null, Direction.UP);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos blockPos = pos.relative(direction);
            if (oldState.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected() != newState.getValue(PROPERTY_BY_DIRECTION.get(direction)).isConnected()
                && world.getBlockState(blockPos).isRedstoneConductor(world, blockPos)) {
                world.updateNeighborsAtExceptFromFacing(
                    blockPos, newState.getBlock(), direction.getOpposite(), ExperimentalRedstoneUtils.withFront(orientation, direction)
                );
            }
        }
    }
}
