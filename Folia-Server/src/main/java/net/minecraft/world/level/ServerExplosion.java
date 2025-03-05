package net.minecraft.world.level;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.Location;
import org.bukkit.event.block.BlockExplodeEvent;
// CraftBukkit end

public class ServerExplosion implements Explosion {

    private static final ExplosionDamageCalculator EXPLOSION_DAMAGE_CALCULATOR = new ExplosionDamageCalculator();
    private static final int MAX_DROPS_PER_COMBINED_STACK = 16;
    private static final float LARGE_EXPLOSION_RADIUS = 2.0F;
    private final boolean fire;
    private final Explosion.BlockInteraction blockInteraction;
    private final ServerLevel level;
    private final Vec3 center;
    @Nullable
    private final Entity source;
    private final float radius;
    private final DamageSource damageSource;
    private final ExplosionDamageCalculator damageCalculator;
    private final Map<Player, Vec3> hitPlayers = new HashMap();
    // CraftBukkit - add field
    public boolean wasCanceled = false;
    public float yield;
    // CraftBukkit end
    public boolean excludeSourceFromDamage = true; // Paper - Allow explosions to damage source
    // Paper start - collisions optimisations
    private static final double[] CACHED_RAYS;
    static {
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList rayCoords = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();

        for (int x = 0; x <= 15; ++x) {
            for (int y = 0; y <= 15; ++y) {
                for (int z = 0; z <= 15; ++z) {
                    if ((x == 0 || x == 15) || (y == 0 || y == 15) || (z == 0 || z == 15)) {
                        double xDir = (double)((float)x / 15.0F * 2.0F - 1.0F);
                        double yDir = (double)((float)y / 15.0F * 2.0F - 1.0F);
                        double zDir = (double)((float)z / 15.0F * 2.0F - 1.0F);

                        double mag = Math.sqrt(
                                xDir * xDir + yDir * yDir + zDir * zDir
                        );

                        rayCoords.add((xDir / mag) * (double)0.3F);
                        rayCoords.add((yDir / mag) * (double)0.3F);
                        rayCoords.add((zDir / mag) * (double)0.3F);
                    }
                }
            }
        }

        CACHED_RAYS = rayCoords.toDoubleArray();
    }

    private static final int CHUNK_CACHE_SHIFT = 2;
    private static final int CHUNK_CACHE_MASK = (1 << CHUNK_CACHE_SHIFT) - 1;
    private static final int CHUNK_CACHE_WIDTH = 1 << CHUNK_CACHE_SHIFT;

    private static final int BLOCK_EXPLOSION_CACHE_SHIFT = 3;
    private static final int BLOCK_EXPLOSION_CACHE_MASK = (1 << BLOCK_EXPLOSION_CACHE_SHIFT) - 1;
    private static final int BLOCK_EXPLOSION_CACHE_WIDTH = 1 << BLOCK_EXPLOSION_CACHE_SHIFT;

    // resistance = (res + 0.3F) * 0.3F;
    // so for resistance = 0, we need res = -0.3F
    private static final Float ZERO_RESISTANCE = Float.valueOf(-0.3f);
    private it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache> blockCache = null;
    private long[] chunkPosCache = null;
    private net.minecraft.world.level.chunk.LevelChunk[] chunkCache = null;
    private ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] directMappedBlockCache;
    private BlockPos.MutableBlockPos mutablePos;

    private ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache getOrCacheExplosionBlock(final int x, final int y, final int z,
                                                                                                    final long key, final boolean calculateResistance) {
        ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache ret = this.blockCache.get(key);
        if (ret != null) {
            return ret;
        }

        BlockPos pos = new BlockPos(x, y, z);

        if (!this.level.isInWorldBounds(pos)) {
            ret = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache(key, pos, null, null, 0.0f, true);
        } else {
            net.minecraft.world.level.chunk.LevelChunk chunk;
            long chunkKey = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(x >> 4, z >> 4);
            int chunkCacheKey = ((x >> 4) & CHUNK_CACHE_MASK) | (((z >> 4) << CHUNK_CACHE_SHIFT) & (CHUNK_CACHE_MASK << CHUNK_CACHE_SHIFT));
            if (this.chunkPosCache[chunkCacheKey] == chunkKey) {
                chunk = this.chunkCache[chunkCacheKey];
            } else {
                this.chunkPosCache[chunkCacheKey] = chunkKey;
                this.chunkCache[chunkCacheKey] = chunk = this.level.getChunk(x >> 4, z >> 4);
            }

            BlockState blockState = ((ca.spottedleaf.moonrise.patches.getblock.GetBlockChunk)chunk).moonrise$getBlock(x, y, z);
            FluidState fluidState = blockState.getFluidState();

            Optional<Float> resistance = !calculateResistance ? Optional.empty() : this.damageCalculator.getBlockExplosionResistance((Explosion)(Object)this, this.level, pos, blockState, fluidState);

            ret = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache(
                    key, pos, blockState, fluidState,
                    (resistance.orElse(ZERO_RESISTANCE).floatValue() + 0.3f) * 0.3f,
                    false
            );
        }

        this.blockCache.put(key, ret);

        return ret;
    }

    private boolean clipsAnything(final Vec3 from, final Vec3 to,
                                  final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context,
                                  final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache,
                                  final BlockPos.MutableBlockPos currPos) {
        // assume that context.delegated = false
        final double adjX = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.x - to.x);
        final double adjY = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.y - to.y);
        final double adjZ = ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.COLLISION_EPSILON * (from.z - to.z);

        if (adjX == 0.0 && adjY == 0.0 && adjZ == 0.0) {
            return false;
        }

        final double toXAdj = to.x - adjX;
        final double toYAdj = to.y - adjY;
        final double toZAdj = to.z - adjZ;
        final double fromXAdj = from.x + adjX;
        final double fromYAdj = from.y + adjY;
        final double fromZAdj = from.z + adjZ;

        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);

        final double diffX = toXAdj - fromXAdj;
        final double diffY = toYAdj - fromYAdj;
        final double diffZ = toZAdj - fromZAdj;

        final double dxDouble = Math.signum(diffX);
        final double dyDouble = Math.signum(diffY);
        final double dzDouble = Math.signum(diffZ);

        final int dx = (int)dxDouble;
        final int dy = (int)dyDouble;
        final int dz = (int)dzDouble;

        final double normalizedDiffX = diffX == 0.0 ? Double.MAX_VALUE : dxDouble / diffX;
        final double normalizedDiffY = diffY == 0.0 ? Double.MAX_VALUE : dyDouble / diffY;
        final double normalizedDiffZ = diffZ == 0.0 ? Double.MAX_VALUE : dzDouble / diffZ;

        double normalizedCurrX = normalizedDiffX * (diffX > 0.0 ? (1.0 - Mth.frac(fromXAdj)) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0 ? (1.0 - Mth.frac(fromYAdj)) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0 ? (1.0 - Mth.frac(fromZAdj)) : Mth.frac(fromZAdj));

        for (;;) {
            currPos.set(currX, currY, currZ);

            // ClipContext.Block.COLLIDER -> BlockBehaviour.BlockStateBase::getCollisionShape
            // ClipContext.Fluid.NONE -> ignore fluids

            // read block from cache
            final long key = BlockPos.asLong(currX, currY, currZ);

            final int cacheKey =
                    (currX & BLOCK_EXPLOSION_CACHE_MASK) |
                    (currY & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT) |
                    (currZ & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT + BLOCK_EXPLOSION_CACHE_SHIFT);
            ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache cachedBlock = blockCache[cacheKey];
            if (cachedBlock == null || cachedBlock.key != key) {
                blockCache[cacheKey] = cachedBlock = this.getOrCacheExplosionBlock(currX, currY, currZ, key, false);
            }

            final BlockState blockState = cachedBlock.blockState;
            if (blockState != null && !((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockState).moonrise$emptyContextCollisionShape()) {
                net.minecraft.world.phys.shapes.VoxelShape collision = cachedBlock.cachedCollisionShape;
                if (collision == null) {
                    collision = ((ca.spottedleaf.moonrise.patches.collisions.block.CollisionBlockState)blockState).moonrise$getConstantContextCollisionShape();
                    if (collision == null) {
                        collision = blockState.getCollisionShape(this.level, currPos, context);
                        if (!context.isDelegated()) {
                            // if it was not delegated during this call, assume that for any future ones it will not be delegated
                            // again, and cache the result
                            cachedBlock.cachedCollisionShape = collision;
                        }
                    } else {
                        cachedBlock.cachedCollisionShape = collision;
                    }
                }

                if (!collision.isEmpty() && collision.clip(from, to, currPos) != null) {
                    return true;
                }
            }

            if (normalizedCurrX > 1.0 && normalizedCurrY > 1.0 && normalizedCurrZ > 1.0) {
                return false;
            }

            // inc the smallest normalized coordinate

            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    // x < y && x >= z <--> z < y && z <= x
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                // y <= x && y < z
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                // y <= x && z <= y <--> z <= y && z <= x
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private float getSeenFraction(final Vec3 source, final Entity target,
                                   final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache,
                                   final BlockPos.MutableBlockPos blockPos) {
        final AABB boundingBox = target.getBoundingBox();
        final double diffX = boundingBox.maxX - boundingBox.minX;
        final double diffY = boundingBox.maxY - boundingBox.minY;
        final double diffZ = boundingBox.maxZ - boundingBox.minZ;

        final double incX = 1.0 / (diffX * 2.0 + 1.0);
        final double incY = 1.0 / (diffY * 2.0 + 1.0);
        final double incZ = 1.0 / (diffZ * 2.0 + 1.0);

        if (incX < 0.0 || incY < 0.0 || incZ < 0.0) {
            return 0.0f;
        }

        final double offX = (1.0 - Math.floor(1.0 / incX) * incX) * 0.5 + boundingBox.minX;
        final double offY = boundingBox.minY;
        final double offZ = (1.0 - Math.floor(1.0 / incZ) * incZ) * 0.5 + boundingBox.minZ;

        final ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext context = new ca.spottedleaf.moonrise.patches.collisions.CollisionUtil.LazyEntityCollisionContext(target);

        int totalRays = 0;
        int missedRays = 0;

        for (double dx = 0.0; dx <= 1.0; dx += incX) {
            final double fromX = Math.fma(dx, diffX, offX);
            for (double dy = 0.0; dy <= 1.0; dy += incY) {
                final double fromY = Math.fma(dy, diffY, offY);
                for (double dz = 0.0; dz <= 1.0; dz += incZ) {
                    ++totalRays;

                    final Vec3 from = new Vec3(
                            fromX,
                            fromY,
                            Math.fma(dz, diffZ, offZ)
                    );

                    if (!this.clipsAnything(from, source, context, blockCache, blockPos)) {
                        ++missedRays;
                    }
                }
            }
        }

        return (float)missedRays / (float)totalRays;
    }
    // Paper end - collisions optimisations

    public ServerExplosion(ServerLevel world, @Nullable Entity entity, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator behavior, Vec3 pos, float power, boolean createFire, Explosion.BlockInteraction destructionType) {
        this.level = world;
        this.source = entity;
        this.radius = (float) Math.max(power, 0.0); // CraftBukkit - clamp bad values
        this.center = pos;
        this.fire = createFire;
        this.blockInteraction = destructionType;
        this.damageSource = damageSource == null ? world.damageSources().explosion(this) : damageSource;
        this.damageCalculator = behavior == null ? this.makeDamageCalculator(entity) : behavior;
        this.yield = this.blockInteraction == Explosion.BlockInteraction.DESTROY_WITH_DECAY ? 1.0F / this.radius : 1.0F; // CraftBukkit
    }

    private ExplosionDamageCalculator makeDamageCalculator(@Nullable Entity entity) {
        return (ExplosionDamageCalculator) (entity == null ? ServerExplosion.EXPLOSION_DAMAGE_CALCULATOR : new EntityBasedExplosionDamageCalculator(entity));
    }

    public static float getSeenPercent(Vec3 pos, Entity entity) {
        AABB axisalignedbb = entity.getBoundingBox();
        double d0 = 1.0D / ((axisalignedbb.maxX - axisalignedbb.minX) * 2.0D + 1.0D);
        double d1 = 1.0D / ((axisalignedbb.maxY - axisalignedbb.minY) * 2.0D + 1.0D);
        double d2 = 1.0D / ((axisalignedbb.maxZ - axisalignedbb.minZ) * 2.0D + 1.0D);
        double d3 = (1.0D - Math.floor(1.0D / d0) * d0) / 2.0D;
        double d4 = (1.0D - Math.floor(1.0D / d2) * d2) / 2.0D;

        if (d0 >= 0.0D && d1 >= 0.0D && d2 >= 0.0D) {
            int i = 0;
            int j = 0;

            for (double d5 = 0.0D; d5 <= 1.0D; d5 += d0) {
                for (double d6 = 0.0D; d6 <= 1.0D; d6 += d1) {
                    for (double d7 = 0.0D; d7 <= 1.0D; d7 += d2) {
                        double d8 = Mth.lerp(d5, axisalignedbb.minX, axisalignedbb.maxX);
                        double d9 = Mth.lerp(d6, axisalignedbb.minY, axisalignedbb.maxY);
                        double d10 = Mth.lerp(d7, axisalignedbb.minZ, axisalignedbb.maxZ);
                        Vec3 vec3d1 = new Vec3(d8 + d3, d9, d10 + d4);

                        if (entity.level().clip(new ClipContext(vec3d1, pos, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity)).getType() == HitResult.Type.MISS) {
                            ++i;
                        }

                        ++j;
                    }
                }
            }

            return (float) i / (float) j;
        } else {
            return 0.0F;
        }
    }

    @Override
    public float radius() {
        return this.radius;
    }

    @Override
    public Vec3 center() {
        return this.center;
    }

    private List<BlockPos> calculateExplodedPositions() {
        // Paper start - collision optimisations
        final ObjectArrayList<BlockPos> ret = new ObjectArrayList<>();

        final Vec3 center = this.center;

        final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[] blockCache = this.directMappedBlockCache;

        // use initial cache value that is most likely to be used: the source position
        final ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache initialCache;
        {
            final int blockX = Mth.floor(center.x);
            final int blockY = Mth.floor(center.y);
            final int blockZ = Mth.floor(center.z);

            final long key = BlockPos.asLong(blockX, blockY, blockZ);

            initialCache = this.getOrCacheExplosionBlock(blockX, blockY, blockZ, key, true);
        }

        // only ~1/3rd of the loop iterations in vanilla will result in a ray, as it is iterating the perimeter of
        // a 16x16x16 cube
        // we can cache the rays and their normals as well, so that we eliminate the excess iterations / checks and
        // calculations in one go
        // additional aggressive caching of block retrieval is very significant, as at low power (i.e tnt) most
        // block retrievals are not unique
        for (int ray = 0, len = CACHED_RAYS.length; ray < len;) {
            ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache cachedBlock = initialCache;

            double currX = center.x;
            double currY = center.y;
            double currZ = center.z;

            final double incX = CACHED_RAYS[ray];
            final double incY = CACHED_RAYS[ray + 1];
            final double incZ = CACHED_RAYS[ray + 2];

            ray += 3;

            float power = this.radius * (0.7F + this.level.random.nextFloat() * 0.6F);

            do {
                final int blockX = Mth.floor(currX);
                final int blockY = Mth.floor(currY);
                final int blockZ = Mth.floor(currZ);

                final long key = BlockPos.asLong(blockX, blockY, blockZ);

                if (cachedBlock.key != key) {
                    final int cacheKey =
                        (blockX & BLOCK_EXPLOSION_CACHE_MASK) |
                            (blockY & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT) |
                            (blockZ & BLOCK_EXPLOSION_CACHE_MASK) << (BLOCK_EXPLOSION_CACHE_SHIFT + BLOCK_EXPLOSION_CACHE_SHIFT);
                    cachedBlock = blockCache[cacheKey];
                    if (cachedBlock == null || cachedBlock.key != key) {
                        blockCache[cacheKey] = cachedBlock = this.getOrCacheExplosionBlock(blockX, blockY, blockZ, key, true);
                    }
                }

                if (cachedBlock.outOfWorld) {
                    break;
                }
                final BlockState iblockdata = cachedBlock.blockState;

                power -= cachedBlock.resistance;

                if (power > 0.0f && cachedBlock.shouldExplode == null) {
                    // note: we expect shouldBlockExplode to be pure with respect to power, as Vanilla currently is.
                    // basically, it is unused, which allows us to cache the result
                    final boolean shouldExplode = iblockdata.isDestroyable() && this.damageCalculator.shouldBlockExplode((Explosion)(Object)this, this.level, cachedBlock.immutablePos, cachedBlock.blockState, power); // Paper - Protect Bedrock and End Portal/Frames from being destroyed
                    cachedBlock.shouldExplode = shouldExplode ? Boolean.TRUE : Boolean.FALSE;
                    if (shouldExplode) {
                        if (this.fire || !cachedBlock.blockState.isAir()) {
                            ret.add(cachedBlock.immutablePos);
                            // Paper start - prevent headless pistons from forming
                            if (!io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.allowHeadlessPistons && iblockdata.getBlock() == Blocks.MOVING_PISTON) {
                                net.minecraft.world.level.block.entity.BlockEntity extension = this.level.getBlockEntity(cachedBlock.immutablePos); // Paper - optimise collisions
                                if (extension instanceof net.minecraft.world.level.block.piston.PistonMovingBlockEntity blockEntity && blockEntity.isSourcePiston()) {
                                    net.minecraft.core.Direction direction = iblockdata.getValue(net.minecraft.world.level.block.piston.PistonHeadBlock.FACING);
                                    ret.add(cachedBlock.immutablePos.relative(direction.getOpposite())); // Paper - optimise collisions
                                }
                            }
                            // Paper end - prevent headless pistons from forming
                        }
                    }
                }

                power -= 0.22500001F;
                currX += incX;
                currY += incY;
                currZ += incZ;
            } while (power > 0.0f);
        }

        return ret;
        // Paper end - collision optimisations
    }

    private void hurtEntities() {
        float f = this.radius * 2.0F;
        int i = Mth.floor(this.center.x - (double) f - 1.0D);
        int j = Mth.floor(this.center.x + (double) f + 1.0D);
        int k = Mth.floor(this.center.y - (double) f - 1.0D);
        int l = Mth.floor(this.center.y + (double) f + 1.0D);
        int i1 = Mth.floor(this.center.z - (double) f - 1.0D);
        int j1 = Mth.floor(this.center.z + (double) f + 1.0D);
        List<Entity> list = this.level.getEntities(excludeSourceFromDamage ? this.source : null, new AABB((double) i, (double) k, (double) i1, (double) j, (double) l, (double) j1), (com.google.common.base.Predicate<Entity>) entity -> entity.isAlive() && !entity.isSpectator()); // Paper - Fix lag from explosions processing dead entities, Allow explosions to damage source
        Iterator iterator = list.iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();

            if (!entity.ignoreExplosion(this)) {
                double d0 = Math.sqrt(entity.distanceToSqr(this.center)) / (double) f;

                if (d0 <= 1.0D) {
                    double d1 = entity.getX() - this.center.x;
                    double d2 = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - this.center.y;
                    double d3 = entity.getZ() - this.center.z;
                    double d4 = Math.sqrt(d1 * d1 + d2 * d2 + d3 * d3);

                    if (d4 != 0.0D) {
                        d1 /= d4;
                        d2 /= d4;
                        d3 /= d4;
                        boolean flag = this.damageCalculator.shouldDamageEntity(this, entity);
                        float f1 = this.damageCalculator.getKnockbackMultiplier(entity);
                        float f2 = !flag && f1 == 0.0F ? 0.0F : this.getBlockDensity(this.center, entity); // Paper - Optimize explosions

                        if (flag) {
                            // CraftBukkit start

                            // Special case ender dragon only give knockback if no damage is cancelled
                            // Thinks to note:
                            // - Setting a velocity to a ComplexEntityPart is ignored (and therefore not needed)
                            // - Damaging ComplexEntityPart while forward the damage to EntityEnderDragon
                            // - Damaging EntityEnderDragon does nothing
                            // - EntityEnderDragon hitbock always covers the other parts and is therefore always present
                            if (entity instanceof EnderDragonPart) {
                                continue;
                            }

                            entity.lastDamageCancelled = false;

                            if (entity instanceof EnderDragon) {
                                for (EnderDragonPart entityComplexPart : ((EnderDragon) entity).subEntities) {
                                    // Calculate damage separately for each EntityComplexPart
                                    if (list.contains(entityComplexPart)) {
                                        entityComplexPart.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, entity, f2));
                                    }
                                }
                            } else {
                                entity.hurtServer(this.level, this.damageSource, this.damageCalculator.getEntityDamageAmount(this, entity, f2));
                            }

                            if (entity.lastDamageCancelled) { // SPIGOT-5339, SPIGOT-6252, SPIGOT-6777: Skip entity if damage event was cancelled
                                continue;
                            }
                            // CraftBukkit end
                        }

                        double d5 = (1.0D - d0) * (double) f2 * (double) f1;
                        double d6;

                        if (entity instanceof LivingEntity) {
                            LivingEntity entityliving = (LivingEntity) entity;

                            d6 = entity instanceof Player && this.level.paperConfig().environment.disableExplosionKnockback ? 0 : d5 * (1.0D - entityliving.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)); // Paper
                        } else {
                            d6 = d5;
                        }

                        d1 *= d6;
                        d2 *= d6;
                        d3 *= d6;
                        Vec3 vec3d = new Vec3(d1, d2, d3);

                        // CraftBukkit start - Call EntityKnockbackEvent
                        if (entity instanceof LivingEntity) {
                           // Paper start - knockback events
                           io.papermc.paper.event.entity.EntityKnockbackEvent event = CraftEventFactory.callEntityKnockbackEvent((org.bukkit.craftbukkit.entity.CraftLivingEntity) entity.getBukkitEntity(), this.source, this.damageSource.getEntity() != null ? this.damageSource.getEntity() : this.source, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.EXPLOSION, d6, vec3d);
                            vec3d = event.isCancelled() ? Vec3.ZERO : org.bukkit.craftbukkit.util.CraftVector.toNMS(event.getKnockback());
                           // Paper end - knockback events
                        }
                        // CraftBukkit end
                        entity.push(vec3d);
                        if (entity instanceof Player) {
                            Player entityhuman = (Player) entity;

                            if (!entityhuman.isSpectator() && (!entityhuman.isCreative() || !entityhuman.getAbilities().flying) && !level.paperConfig().environment.disableExplosionKnockback) { // Paper - Option to disable explosion knockback
                                this.hitPlayers.put(entityhuman, vec3d);
                            }
                        }

                        entity.onExplosionHit(this.source);
                    }
                }
            }
        }

    }

    private void interactWithBlocks(List<BlockPos> positions) {
        List<ServerExplosion.StackCollector> list1 = new ArrayList();

        Util.shuffle(positions, this.level.random);
        // CraftBukkit start
        org.bukkit.World bworld = this.level.getWorld();
        Location location = CraftLocation.toBukkit(this.center, bworld);

        List<org.bukkit.block.Block> blockList = new ObjectArrayList<>();
        for (int i1 = positions.size() - 1; i1 >= 0; i1--) {
            BlockPos cpos = positions.get(i1);
            org.bukkit.block.Block bblock = bworld.getBlockAt(cpos.getX(), cpos.getY(), cpos.getZ());
            if (!bblock.getType().isAir()) {
                blockList.add(bblock);
            }
        }

        List<org.bukkit.block.Block> bukkitBlocks;

        if (this.source != null) {
            EntityExplodeEvent event = CraftEventFactory.callEntityExplodeEvent(this.source, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        } else {
            org.bukkit.block.Block block = location.getBlock();
            org.bukkit.block.BlockState blockState = (this.damageSource.getDirectBlockState() != null) ? this.damageSource.getDirectBlockState() : block.getState();
            BlockExplodeEvent event = CraftEventFactory.callBlockExplodeEvent(block, blockState, blockList, this.yield, this.getBlockInteraction());
            this.wasCanceled = event.isCancelled();
            bukkitBlocks = event.blockList();
            this.yield = event.getYield();
        }

        positions.clear();

        for (org.bukkit.block.Block bblock : bukkitBlocks) {
            BlockPos coords = new BlockPos(bblock.getX(), bblock.getY(), bblock.getZ());
            positions.add(coords);
        }

        if (this.wasCanceled) {
            return;
        }
        // CraftBukkit end
        Iterator iterator = positions.iterator();

        while (iterator.hasNext()) {
            BlockPos blockposition = (BlockPos) iterator.next();
            // CraftBukkit start - TNTPrimeEvent
            BlockState iblockdata = this.level.getBlockState(blockposition);
            Block block = iblockdata.getBlock();
            if (block instanceof net.minecraft.world.level.block.TntBlock) {
                Entity sourceEntity = this.source == null ? null : this.source;
                BlockPos sourceBlock = sourceEntity == null ? BlockPos.containing(this.center) : null;
                if (!CraftEventFactory.callTNTPrimeEvent(this.level, blockposition, org.bukkit.event.block.TNTPrimeEvent.PrimeCause.EXPLOSION, sourceEntity, sourceBlock)) {
                    this.level.sendBlockUpdated(blockposition, Blocks.AIR.defaultBlockState(), iblockdata, 3); // Update the block on the client
                    continue;
                }
            }
            // CraftBukkit end

            this.level.getBlockState(blockposition).onExplosionHit(this.level, blockposition, this, (itemstack, blockposition1) -> {
                ServerExplosion.addOrAppendStack(list1, itemstack, blockposition1);
            });
        }

        iterator = list1.iterator();

        while (iterator.hasNext()) {
            ServerExplosion.StackCollector serverexplosion_a = (ServerExplosion.StackCollector) iterator.next();

            Block.popResource(this.level, serverexplosion_a.pos, serverexplosion_a.stack);
        }

    }

    private void createFire(List<BlockPos> positions) {
        Iterator iterator = positions.iterator();

        while (iterator.hasNext()) {
            BlockPos blockposition = (BlockPos) iterator.next();

            if (this.level.random.nextInt(3) == 0 && this.level.getBlockState(blockposition).isAir() && this.level.getBlockState(blockposition.below()).isSolidRender()) {
                // CraftBukkit start - Ignition by explosion
                if (!org.bukkit.craftbukkit.event.CraftEventFactory.callBlockIgniteEvent(this.level, blockposition, this).isCancelled()) {
                    this.level.setBlockAndUpdate(blockposition, BaseFireBlock.getState(this.level, blockposition));
                }
                // CraftBukkit end
            }
        }

    }

    public void explode() {
        // CraftBukkit start
        if (this.radius < 0.1F) {
            return;
        }
        // CraftBukkit end
        // Paper start - collision optimisations
        this.blockCache = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        this.chunkPosCache = new long[CHUNK_CACHE_WIDTH * CHUNK_CACHE_WIDTH];
        java.util.Arrays.fill(this.chunkPosCache, ChunkPos.INVALID_CHUNK_POS);
        this.chunkCache = new net.minecraft.world.level.chunk.LevelChunk[CHUNK_CACHE_WIDTH * CHUNK_CACHE_WIDTH];
        this.directMappedBlockCache = new ca.spottedleaf.moonrise.patches.collisions.ExplosionBlockCache[BLOCK_EXPLOSION_CACHE_WIDTH * BLOCK_EXPLOSION_CACHE_WIDTH * BLOCK_EXPLOSION_CACHE_WIDTH];
        this.mutablePos = new BlockPos.MutableBlockPos();
        // Paper end - collision optimisations
        this.level.gameEvent(this.source, (Holder) GameEvent.EXPLODE, this.center);
        List<BlockPos> list = this.calculateExplodedPositions();

        this.hurtEntities();
        if (this.interactsWithBlocks()) {
            ProfilerFiller gameprofilerfiller = Profiler.get();

            gameprofilerfiller.push("explosion_blocks");
            this.interactWithBlocks(list);
            gameprofilerfiller.pop();
        }

        if (this.fire) {
            this.createFire(list);
        }
        // Paper start - collision optimisations
        this.blockCache = null;
        this.chunkPosCache = null;
        this.chunkCache = null;
        this.directMappedBlockCache = null;
        this.mutablePos = null;
        // Paper end - collision optimisations

    }

    private static void addOrAppendStack(List<ServerExplosion.StackCollector> droppedItemsOut, ItemStack item, BlockPos pos) {
        if (item.isEmpty()) return; // CraftBukkit - SPIGOT-5425
        Iterator iterator = droppedItemsOut.iterator();

        do {
            if (!iterator.hasNext()) {
                droppedItemsOut.add(new ServerExplosion.StackCollector(pos, item));
                return;
            }

            ServerExplosion.StackCollector serverexplosion_a = (ServerExplosion.StackCollector) iterator.next();

            serverexplosion_a.tryMerge(item);
        } while (!item.isEmpty());

    }

    private boolean interactsWithBlocks() {
        return this.blockInteraction != Explosion.BlockInteraction.KEEP;
    }

    public Map<Player, Vec3> getHitPlayers() {
        return this.hitPlayers;
    }

    @Override
    public ServerLevel level() {
        return this.level;
    }

    @Nullable
    @Override
    public LivingEntity getIndirectSourceEntity() {
        return Explosion.getIndirectSourceEntity(this.source);
    }

    @Nullable
    @Override
    public Entity getDirectSourceEntity() {
        return this.source;
    }

    public DamageSource getDamageSource() {
        return this.damageSource;
    }

    @Override
    public Explosion.BlockInteraction getBlockInteraction() {
        return this.blockInteraction;
    }

    @Override
    public boolean canTriggerBlocks() {
        return this.blockInteraction != Explosion.BlockInteraction.TRIGGER_BLOCK ? false : (this.source != null && this.source.getType() == EntityType.BREEZE_WIND_CHARGE ? this.level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) : true);
    }

    @Override
    public boolean shouldAffectBlocklikeEntities() {
        boolean flag = this.level.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
        boolean flag1 = this.source == null || !this.source.isInWater();
        boolean flag2 = this.source == null || this.source.getType() != EntityType.BREEZE_WIND_CHARGE && this.source.getType() != EntityType.WIND_CHARGE;

        return flag ? flag1 && flag2 : this.blockInteraction.shouldAffectBlocklikeEntities() && flag1 && flag2;
    }

    public boolean isSmall() {
        return this.radius < 2.0F || !this.interactsWithBlocks();
    }

    private static class StackCollector {

        final BlockPos pos;
        ItemStack stack;

        StackCollector(BlockPos pos, ItemStack item) {
            this.pos = pos;
            this.stack = item;
        }

        public void tryMerge(ItemStack other) {
            if (ItemEntity.areMergable(this.stack, other)) {
                this.stack = ItemEntity.merge(this.stack, other, 16);
            }

        }
    }

    // Paper start - Optimize explosions
    private float getBlockDensity(Vec3 vec3d, Entity entity) {
        if (!this.level.paperConfig().environment.optimizeExplosions) {
            return this.getSeenFraction(vec3d, entity, this.directMappedBlockCache, this.mutablePos); // Paper - collision optimisations
        }
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.level.getCurrentWorldData(); // Folia - region threading
        CacheKey key = new CacheKey(this, entity.getBoundingBox());
        Float blockDensity = worldData.explosionDensityCache.get(key); // Folia - region threading
        if (blockDensity == null) {
            blockDensity = this.getSeenFraction(vec3d, entity, this.directMappedBlockCache, this.mutablePos); // Paper - collision optimisations
            worldData.explosionDensityCache.put(key, blockDensity); // Folia - region threading
        }

        return blockDensity;
    }

    public static class CacheKey { // Folia - region threading - public
        private final Level world;
        private final double posX, posY, posZ;
        private final double minX, minY, minZ;
        private final double maxX, maxY, maxZ;

        public CacheKey(Explosion explosion, AABB aabb) {
            this.world = explosion.level();
            this.posX = explosion.center().x;
            this.posY = explosion.center().y;
            this.posZ = explosion.center().z;
            this.minX = aabb.minX;
            this.minY = aabb.minY;
            this.minZ = aabb.minZ;
            this.maxX = aabb.maxX;
            this.maxY = aabb.maxY;
            this.maxZ = aabb.maxZ;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            CacheKey cacheKey = (CacheKey) o;

            if (Double.compare(cacheKey.posX, posX) != 0) return false;
            if (Double.compare(cacheKey.posY, posY) != 0) return false;
            if (Double.compare(cacheKey.posZ, posZ) != 0) return false;
            if (Double.compare(cacheKey.minX, minX) != 0) return false;
            if (Double.compare(cacheKey.minY, minY) != 0) return false;
            if (Double.compare(cacheKey.minZ, minZ) != 0) return false;
            if (Double.compare(cacheKey.maxX, maxX) != 0) return false;
            if (Double.compare(cacheKey.maxY, maxY) != 0) return false;
            if (Double.compare(cacheKey.maxZ, maxZ) != 0) return false;
            return world.equals(cacheKey.world);
        }

        @Override
        public int hashCode() {
            int result;
            long temp;
            result = world.hashCode();
            temp = Double.doubleToLongBits(posX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(posZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(minZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxX);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxY);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(maxZ);
            result = 31 * result + (int) (temp ^ (temp >>> 32));
            return result;
        }
    }
    // Paper end
}
