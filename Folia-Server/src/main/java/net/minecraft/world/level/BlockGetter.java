package net.minecraft.world.level;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public interface BlockGetter extends LevelHeightAccessor {

    int MAX_BLOCK_ITERATIONS_ALONG_TRAVEL = 16;

    @Nullable
    BlockEntity getBlockEntity(BlockPos pos);

    default <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
        BlockEntity tileentity = this.getBlockEntity(pos);

        return tileentity != null && tileentity.getType() == type ? (Optional<T>) Optional.of(tileentity) : Optional.empty(); // CraftBukkit - decompile error
    }

    BlockState getBlockState(BlockPos pos);
    // Paper start - if loaded util
    @Nullable BlockState getBlockStateIfLoaded(BlockPos blockposition);

    default @Nullable Block getBlockIfLoaded(BlockPos blockposition) {
        BlockState type = this.getBlockStateIfLoaded(blockposition);
        return type == null ? null : type.getBlock();
    }
    @Nullable FluidState getFluidIfLoaded(BlockPos blockposition);
    // Paper end

    FluidState getFluidState(BlockPos pos);

    default int getLightEmission(BlockPos pos) {
        return this.getBlockState(pos).getLightEmission();
    }

    default Stream<BlockState> getBlockStates(AABB box) {
        return BlockPos.betweenClosedStream(box).map(this::getBlockState);
    }

    default BlockHitResult isBlockInLine(ClipBlockStateContext context) {
        return (BlockHitResult) BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (clipblockstatecontext1, blockposition) -> {
            BlockState iblockdata = this.getBlockState(blockposition);
            Vec3 vec3d = clipblockstatecontext1.getFrom().subtract(clipblockstatecontext1.getTo());

            return clipblockstatecontext1.isTargetBlock().test(iblockdata) ? new BlockHitResult(clipblockstatecontext1.getTo(), Direction.getApproximateNearest(vec3d.x, vec3d.y, vec3d.z), BlockPos.containing(clipblockstatecontext1.getTo()), false) : null;
        }, (clipblockstatecontext1) -> {
            Vec3 vec3d = clipblockstatecontext1.getFrom().subtract(clipblockstatecontext1.getTo());

            return BlockHitResult.miss(clipblockstatecontext1.getTo(), Direction.getApproximateNearest(vec3d.x, vec3d.y, vec3d.z), BlockPos.containing(clipblockstatecontext1.getTo()));
        });
    }

    // CraftBukkit start - moved block handling into separate method for use by Block#rayTrace
    default BlockHitResult clip(ClipContext raytrace1, BlockPos blockposition) {
        // Paper start - Add predicate for blocks when raytracing
        return clip(raytrace1, blockposition, null);
    }

    default BlockHitResult clip(ClipContext raytrace1, BlockPos blockposition, java.util.function.Predicate<? super org.bukkit.block.Block> canCollide) {
            // Paper end - Add predicate for blocks when raytracing
            // Paper start - Prevent raytrace from loading chunks
            BlockState iblockdata = this.getBlockStateIfLoaded(blockposition);
            if (iblockdata == null) {
                // copied the last function parameter (listed below)
                Vec3 vec3d = raytrace1.getFrom().subtract(raytrace1.getTo());

                return BlockHitResult.miss(raytrace1.getTo(), Direction.getApproximateNearest(vec3d.x, vec3d.y, vec3d.z), BlockPos.containing(raytrace1.getTo()));
            }
            // Paper end - Prevent raytrace from loading chunks
            if (iblockdata.isAir() || (canCollide != null && this instanceof LevelAccessor levelAccessor && !canCollide.test(org.bukkit.craftbukkit.block.CraftBlock.at(levelAccessor, blockposition)))) return null; // Paper - Perf: optimise air cases & check canCollide predicate
            FluidState fluid = iblockdata.getFluidState(); // Paper - Perf: don't need to go to world state again
            Vec3 vec3d = raytrace1.getFrom();
            Vec3 vec3d1 = raytrace1.getTo();
            VoxelShape voxelshape = raytrace1.getBlockShape(iblockdata, this, blockposition);
            BlockHitResult movingobjectpositionblock = this.clipWithInteractionOverride(vec3d, vec3d1, blockposition, voxelshape, iblockdata);
            VoxelShape voxelshape1 = raytrace1.getFluidShape(fluid, this, blockposition);
            BlockHitResult movingobjectpositionblock1 = voxelshape1.clip(vec3d, vec3d1, blockposition);
            double d0 = movingobjectpositionblock == null ? Double.MAX_VALUE : raytrace1.getFrom().distanceToSqr(movingobjectpositionblock.getLocation());
            double d1 = movingobjectpositionblock1 == null ? Double.MAX_VALUE : raytrace1.getFrom().distanceToSqr(movingobjectpositionblock1.getLocation());

            return d0 <= d1 ? movingobjectpositionblock : movingobjectpositionblock1;
    }
    // CraftBukkit end

    default BlockHitResult clip(ClipContext context) {
        // Paper start - Add predicate for blocks when raytracing
        return clip(context, (java.util.function.Predicate<org.bukkit.block.Block>) null);
    }

    default BlockHitResult clip(ClipContext context, java.util.function.Predicate<? super org.bukkit.block.Block> canCollide) {
        // Paper end - Add predicate for blocks when raytracing
        return (BlockHitResult) BlockGetter.traverseBlocks(context.getFrom(), context.getTo(), context, (raytrace1, blockposition) -> {
            return this.clip(raytrace1, blockposition, canCollide); // CraftBukkit - moved into separate method // Paper - Add predicate for blocks when raytracing
        }, (raytrace1) -> {
            Vec3 vec3d = raytrace1.getFrom().subtract(raytrace1.getTo());

            return BlockHitResult.miss(raytrace1.getTo(), Direction.getApproximateNearest(vec3d.x, vec3d.y, vec3d.z), BlockPos.containing(raytrace1.getTo()));
        });
    }

    @Nullable
    default BlockHitResult clipWithInteractionOverride(Vec3 start, Vec3 end, BlockPos pos, VoxelShape shape, BlockState state) {
        BlockHitResult movingobjectpositionblock = shape.clip(start, end, pos);

        if (movingobjectpositionblock != null) {
            BlockHitResult movingobjectpositionblock1 = state.getInteractionShape(this, pos).clip(start, end, pos);

            if (movingobjectpositionblock1 != null && movingobjectpositionblock1.getLocation().subtract(start).lengthSqr() < movingobjectpositionblock.getLocation().subtract(start).lengthSqr()) {
                return movingobjectpositionblock.withDirection(movingobjectpositionblock1.getDirection());
            }
        }

        return movingobjectpositionblock;
    }

    default double getBlockFloorHeight(VoxelShape blockCollisionShape, Supplier<VoxelShape> belowBlockCollisionShapeGetter) {
        if (!blockCollisionShape.isEmpty()) {
            return blockCollisionShape.max(Direction.Axis.Y);
        } else {
            double d0 = ((VoxelShape) belowBlockCollisionShapeGetter.get()).max(Direction.Axis.Y);

            return d0 >= 1.0D ? d0 - 1.0D : Double.NEGATIVE_INFINITY;
        }
    }

    default double getBlockFloorHeight(BlockPos pos) {
        return this.getBlockFloorHeight(this.getBlockState(pos).getCollisionShape(this, pos), () -> {
            BlockPos blockposition1 = pos.below();

            return this.getBlockState(blockposition1).getCollisionShape(this, blockposition1);
        });
    }

    static <T, C> T traverseBlocks(Vec3 start, Vec3 end, C context, BiFunction<C, BlockPos, T> blockHitFactory, Function<C, T> missFactory) {
        if (start.equals(end)) {
            return missFactory.apply(context);
        } else {
            double d0 = Mth.lerp(-1.0E-7D, end.x, start.x);
            double d1 = Mth.lerp(-1.0E-7D, end.y, start.y);
            double d2 = Mth.lerp(-1.0E-7D, end.z, start.z);
            double d3 = Mth.lerp(-1.0E-7D, start.x, end.x);
            double d4 = Mth.lerp(-1.0E-7D, start.y, end.y);
            double d5 = Mth.lerp(-1.0E-7D, start.z, end.z);
            int i = Mth.floor(d3);
            int j = Mth.floor(d4);
            int k = Mth.floor(d5);
            BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos(i, j, k);
            T t0 = blockHitFactory.apply(context, blockposition_mutableblockposition);

            if (t0 != null) {
                return t0;
            } else {
                double d6 = d0 - d3;
                double d7 = d1 - d4;
                double d8 = d2 - d5;
                int l = Mth.sign(d6);
                int i1 = Mth.sign(d7);
                int j1 = Mth.sign(d8);
                double d9 = l == 0 ? Double.MAX_VALUE : (double) l / d6;
                double d10 = i1 == 0 ? Double.MAX_VALUE : (double) i1 / d7;
                double d11 = j1 == 0 ? Double.MAX_VALUE : (double) j1 / d8;
                double d12 = d9 * (l > 0 ? 1.0D - Mth.frac(d3) : Mth.frac(d3));
                double d13 = d10 * (i1 > 0 ? 1.0D - Mth.frac(d4) : Mth.frac(d4));
                double d14 = d11 * (j1 > 0 ? 1.0D - Mth.frac(d5) : Mth.frac(d5));

                T object; // CraftBukkit - decompile error

                do {
                    if (d12 > 1.0D && d13 > 1.0D && d14 > 1.0D) {
                        return missFactory.apply(context);
                    }

                    if (d12 < d13) {
                        if (d12 < d14) {
                            i += l;
                            d12 += d9;
                        } else {
                            k += j1;
                            d14 += d11;
                        }
                    } else if (d13 < d14) {
                        j += i1;
                        d13 += d10;
                    } else {
                        k += j1;
                        d14 += d11;
                    }

                    object = blockHitFactory.apply(context, blockposition_mutableblockposition.set(i, j, k));
                } while (object == null);

                return object;
            }
        }
    }

    static Iterable<BlockPos> boxTraverseBlocks(Vec3 oldPos, Vec3 newPos, AABB boundingBox) {
        Vec3 vec3d2 = newPos.subtract(oldPos);
        Iterable<BlockPos> iterable = BlockPos.betweenClosed(boundingBox);

        if (vec3d2.lengthSqr() < (double) Mth.square(0.99999F)) {
            return iterable;
        } else {
            Set<BlockPos> set = new ObjectLinkedOpenHashSet();
            Vec3 vec3d3 = boundingBox.getMinPosition();
            Vec3 vec3d4 = vec3d3.subtract(vec3d2);

            BlockGetter.addCollisionsAlongTravel(set, vec3d4, vec3d3, boundingBox);
            Iterator iterator = iterable.iterator();

            while (iterator.hasNext()) {
                BlockPos blockposition = (BlockPos) iterator.next();

                set.add(blockposition.immutable());
            }

            return set;
        }
    }

    private static void addCollisionsAlongTravel(Set<BlockPos> result, Vec3 oldPos, Vec3 newPos, AABB boundingBox) {
        Vec3 vec3d2 = newPos.subtract(oldPos);
        int i = Mth.floor(oldPos.x);
        int j = Mth.floor(oldPos.y);
        int k = Mth.floor(oldPos.z);
        int l = Mth.sign(vec3d2.x);
        int i1 = Mth.sign(vec3d2.y);
        int j1 = Mth.sign(vec3d2.z);
        double d0 = l == 0 ? Double.MAX_VALUE : (double) l / vec3d2.x;
        double d1 = i1 == 0 ? Double.MAX_VALUE : (double) i1 / vec3d2.y;
        double d2 = j1 == 0 ? Double.MAX_VALUE : (double) j1 / vec3d2.z;
        double d3 = d0 * (l > 0 ? 1.0D - Mth.frac(oldPos.x) : Mth.frac(oldPos.x));
        double d4 = d1 * (i1 > 0 ? 1.0D - Mth.frac(oldPos.y) : Mth.frac(oldPos.y));
        double d5 = d2 * (j1 > 0 ? 1.0D - Mth.frac(oldPos.z) : Mth.frac(oldPos.z));
        int k1 = 0;

        while (d3 <= 1.0D || d4 <= 1.0D || d5 <= 1.0D) {
            if (d3 < d4) {
                if (d3 < d5) {
                    i += l;
                    d3 += d0;
                } else {
                    k += j1;
                    d5 += d2;
                }
            } else if (d4 < d5) {
                j += i1;
                d4 += d1;
            } else {
                k += j1;
                d5 += d2;
            }

            if (k1++ > 16) {
                break;
            }

            Optional<Vec3> optional = AABB.clip((double) i, (double) j, (double) k, (double) (i + 1), (double) (j + 1), (double) (k + 1), oldPos, newPos);

            if (!optional.isEmpty()) {
                Vec3 vec3d3 = (Vec3) optional.get();
                double d6 = Mth.clamp(vec3d3.x, (double) i + 9.999999747378752E-6D, (double) i + 1.0D - 9.999999747378752E-6D);
                double d7 = Mth.clamp(vec3d3.y, (double) j + 9.999999747378752E-6D, (double) j + 1.0D - 9.999999747378752E-6D);
                double d8 = Mth.clamp(vec3d3.z, (double) k + 9.999999747378752E-6D, (double) k + 1.0D - 9.999999747378752E-6D);
                int l1 = Mth.floor(d6 + boundingBox.getXsize());
                int i2 = Mth.floor(d7 + boundingBox.getYsize());
                int j2 = Mth.floor(d8 + boundingBox.getZsize());

                for (int k2 = i; k2 <= l1; ++k2) {
                    for (int l2 = j; l2 <= i2; ++l2) {
                        for (int i3 = k; i3 <= j2; ++i3) {
                            result.add(new BlockPos(k2, l2, i3));
                        }
                    }
                }
            }
        }

    }
}
