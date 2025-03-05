package net.minecraft.world.level.portal;

import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.mutable.MutableInt;

// CraftBukkit start
import org.bukkit.craftbukkit.util.BlockStateListPopulator;
import org.bukkit.event.world.PortalCreateEvent;
// CraftBukkit end

public class PortalShape {

    private static final int MIN_WIDTH = 2;
    public static final int MAX_WIDTH = 21;
    private static final int MIN_HEIGHT = 3;
    public static final int MAX_HEIGHT = 21;
    private static final BlockBehaviour.StatePredicate FRAME = (iblockdata, iblockaccess, blockposition) -> {
        return iblockdata.is(Blocks.OBSIDIAN);
    };
    private static final float SAFE_TRAVEL_MAX_ENTITY_XY = 4.0F;
    private static final double SAFE_TRAVEL_MAX_VERTICAL_DELTA = 1.0D;
    private final Direction.Axis axis;
    private final Direction rightDir;
    private final int numPortalBlocks;
    private final BlockPos bottomLeft;
    private final int height;
    private final int width;
    // CraftBukkit start - add field
    private final BlockStateListPopulator blocks;

    private PortalShape(Direction.Axis enumdirection_enumaxis, int i, Direction enumdirection, BlockPos blockposition, int j, int k, BlockStateListPopulator blocks) {
        this.blocks = blocks;
        // CraftBukkit end
        this.axis = enumdirection_enumaxis;
        this.numPortalBlocks = i;
        this.rightDir = enumdirection;
        this.bottomLeft = blockposition;
        this.width = j;
        this.height = k;
    }

    public static Optional<PortalShape> findEmptyPortalShape(LevelAccessor world, BlockPos pos, Direction.Axis firstCheckedAxis) {
        return PortalShape.findPortalShape(world, pos, (blockportalshape) -> {
            return blockportalshape.isValid() && blockportalshape.numPortalBlocks == 0;
        }, firstCheckedAxis);
    }

    public static Optional<PortalShape> findPortalShape(LevelAccessor world, BlockPos pos, Predicate<PortalShape> validator, Direction.Axis firstCheckedAxis) {
        Optional<PortalShape> optional = Optional.of(PortalShape.findAnyShape(world, pos, firstCheckedAxis)).filter(validator);

        if (optional.isPresent()) {
            return optional;
        } else {
            Direction.Axis enumdirection_enumaxis1 = firstCheckedAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;

            return Optional.of(PortalShape.findAnyShape(world, pos, enumdirection_enumaxis1)).filter(validator);
        }
    }

    public static PortalShape findAnyShape(BlockGetter world, BlockPos pos, Direction.Axis axis) {
        BlockStateListPopulator blocks = new BlockStateListPopulator(((LevelAccessor) world).getMinecraftWorld()); // CraftBukkit
        Direction enumdirection = axis == Direction.Axis.X ? Direction.WEST : Direction.SOUTH;
        BlockPos blockposition1 = PortalShape.calculateBottomLeft(world, enumdirection, pos, blocks); // CraftBukkit

        if (blockposition1 == null) {
            return new PortalShape(axis, 0, enumdirection, pos, 0, 0, blocks); // CraftBukkit
        } else {
            int i = PortalShape.calculateWidth(world, blockposition1, enumdirection, blocks); // CraftBukkit

            if (i == 0) {
                return new PortalShape(axis, 0, enumdirection, blockposition1, 0, 0, blocks); // CraftBukkit
            } else {
                MutableInt mutableint = new MutableInt();
                int j = PortalShape.calculateHeight(world, blockposition1, enumdirection, i, mutableint, blocks); // CraftBukkit

                return new PortalShape(axis, mutableint.getValue(), enumdirection, blockposition1, i, j, blocks); // CraftBukkit
            }
        }
    }

    @Nullable
    private static BlockPos calculateBottomLeft(BlockGetter iblockaccess, Direction enumdirection, BlockPos blockposition, BlockStateListPopulator blocks) { // CraftBukkit
        for (int i = Math.max(iblockaccess.getMinY(), blockposition.getY() - 21); blockposition.getY() > i && PortalShape.isEmpty(iblockaccess.getBlockState(blockposition.below())); blockposition = blockposition.below()) {
            ;
        }

        Direction enumdirection1 = enumdirection.getOpposite();
        int j = PortalShape.getDistanceUntilEdgeAboveFrame(iblockaccess, blockposition, enumdirection1, blocks) - 1; // CraftBukkit

        return j < 0 ? null : blockposition.relative(enumdirection1, j);
    }

    private static int calculateWidth(BlockGetter iblockaccess, BlockPos blockposition, Direction enumdirection, BlockStateListPopulator blocks) { // CraftBukkit
        int i = PortalShape.getDistanceUntilEdgeAboveFrame(iblockaccess, blockposition, enumdirection, blocks); // CraftBukkit

        return i >= 2 && i <= 21 ? i : 0;
    }

    private static int getDistanceUntilEdgeAboveFrame(BlockGetter iblockaccess, BlockPos blockposition, Direction enumdirection, BlockStateListPopulator blocks) { // CraftBukkit
        BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();

        for (int i = 0; i <= 21; ++i) {
            blockposition_mutableblockposition.set(blockposition).move(enumdirection, i);
            BlockState iblockdata = iblockaccess.getBlockState(blockposition_mutableblockposition);

            if (!PortalShape.isEmpty(iblockdata)) {
                if (PortalShape.FRAME.test(iblockdata, iblockaccess, blockposition_mutableblockposition)) {
                    blocks.setBlock(blockposition_mutableblockposition, iblockdata, 18); // CraftBukkit - lower left / right
                    return i;
                }
                break;
            }

            BlockState iblockdata1 = iblockaccess.getBlockState(blockposition_mutableblockposition.move(Direction.DOWN));

            if (!PortalShape.FRAME.test(iblockdata1, iblockaccess, blockposition_mutableblockposition)) {
                break;
            }
            blocks.setBlock(blockposition_mutableblockposition, iblockdata1, 18); // CraftBukkit - bottom row
        }

        return 0;
    }

    private static int calculateHeight(BlockGetter iblockaccess, BlockPos blockposition, Direction enumdirection, int i, MutableInt mutableint, BlockStateListPopulator blocks) { // CraftBukkit
        BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();
        int j = PortalShape.getDistanceUntilTop(iblockaccess, blockposition, enumdirection, blockposition_mutableblockposition, i, mutableint, blocks); // CraftBukkit

        return j >= 3 && j <= 21 && PortalShape.hasTopFrame(iblockaccess, blockposition, enumdirection, blockposition_mutableblockposition, i, j, blocks) ? j : 0; // CraftBukkit
    }

    private static boolean hasTopFrame(BlockGetter iblockaccess, BlockPos blockposition, Direction enumdirection, BlockPos.MutableBlockPos blockposition_mutableblockposition, int i, int j, BlockStateListPopulator blocks) { // CraftBukkit
        for (int k = 0; k < i; ++k) {
            BlockPos.MutableBlockPos blockposition_mutableblockposition1 = blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, k);

            if (!PortalShape.FRAME.test(iblockaccess.getBlockState(blockposition_mutableblockposition1), iblockaccess, blockposition_mutableblockposition1)) {
                return false;
            }
            blocks.setBlock(blockposition_mutableblockposition1, iblockaccess.getBlockState(blockposition_mutableblockposition1), 18); // CraftBukkit - upper row
        }

        return true;
    }

    private static int getDistanceUntilTop(BlockGetter iblockaccess, BlockPos blockposition, Direction enumdirection, BlockPos.MutableBlockPos blockposition_mutableblockposition, int i, MutableInt mutableint, BlockStateListPopulator blocks) { // CraftBukkit
        for (int j = 0; j < 21; ++j) {
            blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, -1);
            if (!PortalShape.FRAME.test(iblockaccess.getBlockState(blockposition_mutableblockposition), iblockaccess, blockposition_mutableblockposition)) {
                return j;
            }

            blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, i);
            if (!PortalShape.FRAME.test(iblockaccess.getBlockState(blockposition_mutableblockposition), iblockaccess, blockposition_mutableblockposition)) {
                return j;
            }

            for (int k = 0; k < i; ++k) {
                blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, k);
                BlockState iblockdata = iblockaccess.getBlockState(blockposition_mutableblockposition);

                if (!PortalShape.isEmpty(iblockdata)) {
                    return j;
                }

                if (iblockdata.is(Blocks.NETHER_PORTAL)) {
                    mutableint.increment();
                }
            }
            // CraftBukkit start - left and right
            blocks.setBlock(blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, -1), iblockaccess.getBlockState(blockposition_mutableblockposition), 18);
            blocks.setBlock(blockposition_mutableblockposition.set(blockposition).move(Direction.UP, j).move(enumdirection, i), iblockaccess.getBlockState(blockposition_mutableblockposition), 18);
            // CraftBukkit end
        }

        return 21;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(BlockTags.FIRE) || state.is(Blocks.NETHER_PORTAL);
    }

    public boolean isValid() {
        return this.width >= 2 && this.width <= 21 && this.height >= 3 && this.height <= 21;
    }

    // CraftBukkit start - return boolean, add entity
    public boolean createPortalBlocks(LevelAccessor generatoraccess, Entity entity) {
        org.bukkit.World bworld = generatoraccess.getMinecraftWorld().getWorld();

        // Copy below for loop
        BlockState iblockdata = (BlockState) Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, this.axis);

        BlockPos.betweenClosed(this.bottomLeft, this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1)).forEach((blockposition) -> {
            this.blocks.setBlock(blockposition, iblockdata, 18);
        });

        PortalCreateEvent event = new PortalCreateEvent((java.util.List<org.bukkit.block.BlockState>) (java.util.List) this.blocks.getList(), bworld, (entity == null) ? null : entity.getBukkitEntity(), PortalCreateEvent.CreateReason.FIRE);
        generatoraccess.getMinecraftWorld().getServer().server.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            return false;
        }
        // CraftBukkit end
        BlockPos.betweenClosed(this.bottomLeft, this.bottomLeft.relative(Direction.UP, this.height - 1).relative(this.rightDir, this.width - 1)).forEach((blockposition) -> {
            generatoraccess.setBlock(blockposition, iblockdata, 18);
        });
        return true; // CraftBukkit
    }

    public boolean isComplete() {
        return this.isValid() && this.numPortalBlocks == this.width * this.height;
    }

    public static Vec3 getRelativePosition(BlockUtil.FoundRectangle portalRect, Direction.Axis portalAxis, Vec3 entityPos, EntityDimensions entityDimensions) {
        double d0 = (double) portalRect.axis1Size - (double) entityDimensions.width();
        double d1 = (double) portalRect.axis2Size - (double) entityDimensions.height();
        BlockPos blockposition = portalRect.minCorner;
        double d2;
        double d3;

        if (d0 > 0.0D) {
            d2 = (double) blockposition.get(portalAxis) + (double) entityDimensions.width() / 2.0D;
            d3 = Mth.clamp(Mth.inverseLerp(entityPos.get(portalAxis) - d2, 0.0D, d0), 0.0D, 1.0D);
        } else {
            d3 = 0.5D;
        }

        Direction.Axis enumdirection_enumaxis1;

        if (d1 > 0.0D) {
            enumdirection_enumaxis1 = Direction.Axis.Y;
            d2 = Mth.clamp(Mth.inverseLerp(entityPos.get(enumdirection_enumaxis1) - (double) blockposition.get(enumdirection_enumaxis1), 0.0D, d1), 0.0D, 1.0D);
        } else {
            d2 = 0.0D;
        }

        enumdirection_enumaxis1 = portalAxis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        double d4 = entityPos.get(enumdirection_enumaxis1) - ((double) blockposition.get(enumdirection_enumaxis1) + 0.5D);

        return new Vec3(d3, d2, d4);
    }

    public static Vec3 findCollisionFreePosition(Vec3 fallback, ServerLevel world, Entity entity, EntityDimensions dimensions) {
        if (dimensions.width() <= 4.0F && dimensions.height() <= 4.0F) {
            double d0 = (double) dimensions.height() / 2.0D;
            Vec3 vec3d1 = fallback.add(0.0D, d0, 0.0D);
            VoxelShape voxelshape = Shapes.create(AABB.ofSize(vec3d1, (double) dimensions.width(), 0.0D, (double) dimensions.width()).expandTowards(0.0D, 1.0D, 0.0D).inflate(1.0E-6D));
            Optional<Vec3> optional = world.findFreePosition(entity, voxelshape, vec3d1, (double) dimensions.width(), (double) dimensions.height(), (double) dimensions.width());
            Optional<Vec3> optional1 = optional.map((vec3d2) -> {
                return vec3d2.subtract(0.0D, d0, 0.0D);
            });

            return (Vec3) optional1.orElse(fallback);
        } else {
            return fallback;
        }
    }
}
