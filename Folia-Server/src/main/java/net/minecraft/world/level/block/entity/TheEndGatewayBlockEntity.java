package net.minecraft.world.level.block.entity;

import com.mojang.logging.LogUtils;
import java.util.Iterator;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.EndGatewayConfiguration;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class TheEndGatewayBlockEntity extends TheEndPortalBlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SPAWN_TIME = 200;
    private static final int COOLDOWN_TIME = 40;
    private static final int ATTENTION_INTERVAL = 2400;
    private static final int EVENT_COOLDOWN = 1;
    private static final int GATEWAY_HEIGHT_ABOVE_SURFACE = 10;
    public long age;
    private int teleportCooldown;
    @Nullable
    public volatile BlockPos exitPortal; // Folia - region threading - volatile
    public boolean exactTeleport;

    private static final java.util.concurrent.atomic.AtomicLong SEARCHING_FOR_EXIT_ID_GENERATOR = new java.util.concurrent.atomic.AtomicLong(); // Folia - region threading
    private Long searchingForExitId; // Folia - region threading

    public TheEndGatewayBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityType.END_GATEWAY, pos, state);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        nbt.putLong("Age", this.age);
        if (this.exitPortal != null) {
            nbt.put("exit_portal", NbtUtils.writeBlockPos(this.exitPortal));
        }

        if (this.exactTeleport) {
            nbt.putBoolean("ExactTeleport", true);
        }

    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        this.age = nbt.getLong("Age");
        NbtUtils.readBlockPos(nbt, "exit_portal").filter(Level::isInSpawnableBounds).ifPresent((blockposition) -> {
            this.exitPortal = blockposition;
        });
        this.exactTeleport = nbt.getBoolean("ExactTeleport");
    }

    public static void beamAnimationTick(Level world, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        ++blockEntity.age;
        if (blockEntity.isCoolingDown()) {
            --blockEntity.teleportCooldown;
        }

    }

    public static void portalTick(Level world, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        boolean flag = blockEntity.isSpawning();
        boolean flag1 = blockEntity.isCoolingDown();

        ++blockEntity.age;
        if (flag1) {
            --blockEntity.teleportCooldown;
        } else if (blockEntity.age % 2400L == 0L) {
            TheEndGatewayBlockEntity.triggerCooldown(world, pos, state, blockEntity);
        }

        if (flag != blockEntity.isSpawning() || flag1 != blockEntity.isCoolingDown()) {
            setChanged(world, pos, state);
        }

    }

    public boolean isSpawning() {
        return this.age < 200L;
    }

    public boolean isCoolingDown() {
        return this.teleportCooldown > 0;
    }

    public float getSpawnPercent(float tickDelta) {
        return Mth.clamp(((float) this.age + tickDelta) / 200.0F, 0.0F, 1.0F);
    }

    public float getCooldownPercent(float tickDelta) {
        return 1.0F - Mth.clamp(((float) this.teleportCooldown - tickDelta) / 40.0F, 0.0F, 1.0F);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return this.saveCustomOnly(registries);
    }

    public static void triggerCooldown(Level world, BlockPos pos, BlockState state, TheEndGatewayBlockEntity blockEntity) {
        if (!world.isClientSide) {
            blockEntity.teleportCooldown = 40;
            world.blockEvent(pos, state.getBlock(), 1, 0);
            setChanged(world, pos, state);
        }

    }

    @Override
    public boolean triggerEvent(int type, int data) {
        if (type == 1) {
            this.teleportCooldown = 40;
            return true;
        } else {
            return super.triggerEvent(type, data);
        }
    }

    // Folia start - region threading
    private void trySearchForExit(ServerLevel world, BlockPos fromPos) {
        if (this.searchingForExitId != null) {
            return;
        }
        this.searchingForExitId = Long.valueOf(SEARCHING_FOR_EXIT_ID_GENERATOR.getAndIncrement());
        int chunkX = fromPos.getX() >> 4;
        int chunkZ = fromPos.getZ() >> 4;
        world.moonrise$getChunkTaskScheduler().chunkHolderManager.addTicketAtLevel(
            net.minecraft.server.level.TicketType.END_GATEWAY_EXIT_SEARCH,
            chunkX, chunkZ,
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.BLOCK_TICKING_TICKET_LEVEL,
            this.searchingForExitId
        );

        ca.spottedleaf.concurrentutil.completable.CallbackCompletable<BlockPos> complete = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();

        complete.addWaiter((tpLoc, throwable) -> {
            // create the exit portal
            TheEndGatewayBlockEntity.LOGGER.debug("Creating portal at {}", tpLoc);
            TheEndGatewayBlockEntity.spawnGatewayPortal(world, tpLoc, EndGatewayConfiguration.knownExit(fromPos, false));

            // need to go onto the tick thread to avoid saving issues
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                world, chunkX, chunkZ,
                () -> {
                    // update the exit portal location
                    TheEndGatewayBlockEntity.this.exitPortal = tpLoc;

                    // remove ticket keeping the gateway loaded
                    world.moonrise$getChunkTaskScheduler().chunkHolderManager.removeTicketAtLevel(
                        net.minecraft.server.level.TicketType.END_GATEWAY_EXIT_SEARCH,
                        chunkX, chunkZ,
                        ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.BLOCK_TICKING_TICKET_LEVEL,
                        this.searchingForExitId
                    );
                    TheEndGatewayBlockEntity.this.searchingForExitId = null;
                }
            );
        });

        findOrCreateValidTeleportPosRegionThreading(world, fromPos, complete);
    }

    public static boolean teleportRegionThreading(ServerLevel portalWorld, BlockPos portalPos,
                                                  net.minecraft.world.entity.Entity toTeleport,
                                                  TheEndGatewayBlockEntity portalTile,
                                                  net.minecraft.world.level.portal.TeleportTransition.PostTeleportTransition post) {
        // can we even teleport in this dimension?
        if (portalTile.exitPortal == null && portalWorld.getTypeKey() != LevelStem.END) {
            return false;
        }

        // First, find the position we are trying to teleport to
        BlockPos teleportPos = portalTile.exitPortal;
        boolean isExactTeleport = portalTile.exactTeleport;

        if (teleportPos == null) {
            portalTile.trySearchForExit(portalWorld, portalPos);
            return false;
        }

        // note: we handle the position from the TeleportTransition
        net.minecraft.world.level.portal.TeleportTransition teleport = net.minecraft.world.level.block.EndGatewayBlock.getTeleportTransition(
            portalWorld, toTeleport, Vec3.atCenterOf(teleportPos)
        );


        if (isExactTeleport) {
            // blind teleport
            return toTeleport.teleportAsync(
                teleport, net.minecraft.world.entity.Entity.TELEPORT_FLAG_LOAD_CHUNK | net.minecraft.world.entity.Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
                post == null ? null : (net.minecraft.world.entity.Entity teleportedEntity) -> {
                    post.onTransition(teleportedEntity);
                }
            );
        } else {
            // we could hack around by first loading the chunks, then calling back to here and checking if the entity
            // should be teleported, something something else...
            // however, we know the target location cannot differ by one region section: so we can
            // just teleport and adjust the position after
            return toTeleport.teleportAsync(
                teleport, net.minecraft.world.entity.Entity.TELEPORT_FLAG_LOAD_CHUNK | net.minecraft.world.entity.Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS,
                (net.minecraft.world.entity.Entity teleportedEntity) -> {
                    // adjust to the final exit position
                    Vec3 adjusted = Vec3.atCenterOf(TheEndGatewayBlockEntity.findExitPosition(portalWorld, teleportPos));
                    // teleportTo will adjust rider positions
                    teleportedEntity.teleportTo(adjusted.x, adjusted.y, adjusted.z);

                    if (post != null) {
                        post.onTransition(teleportedEntity);
                    }
                }
            );
        }
    }
    // Folia end - region threading

    @Nullable
    public Vec3 getPortalPosition(ServerLevel world, BlockPos pos) {
        BlockPos blockposition1;

        if (this.exitPortal == null && world.getTypeKey() == LevelStem.END) { // CraftBukkit - work in alternate worlds
            blockposition1 = TheEndGatewayBlockEntity.findOrCreateValidTeleportPos(world, pos);
            blockposition1 = blockposition1.above(10);
            TheEndGatewayBlockEntity.LOGGER.debug("Creating portal at {}", blockposition1);
            TheEndGatewayBlockEntity.spawnGatewayPortal(world, blockposition1, EndGatewayConfiguration.knownExit(pos, false));
            this.setExitPosition(blockposition1, this.exactTeleport);
        }

        if (this.exitPortal != null) {
            blockposition1 = this.exactTeleport ? this.exitPortal : TheEndGatewayBlockEntity.findExitPosition(world, this.exitPortal);
            return blockposition1.getBottomCenter();
        } else {
            return null;
        }
    }

    private static BlockPos findExitPosition(Level world, BlockPos pos) {
        BlockPos blockposition1 = TheEndGatewayBlockEntity.findTallestBlock(world, pos.offset(0, 2, 0), 5, false);

        TheEndGatewayBlockEntity.LOGGER.debug("Best exit position for portal at {} is {}", pos, blockposition1);
        return blockposition1.above();
    }

    private static BlockPos findOrCreateValidTeleportPos(ServerLevel world, BlockPos pos) {
        Vec3 vec3d = TheEndGatewayBlockEntity.findExitPortalXZPosTentative(world, pos);
        LevelChunk chunk = TheEndGatewayBlockEntity.getChunk(world, vec3d);
        BlockPos blockposition1 = TheEndGatewayBlockEntity.findValidSpawnInChunk(chunk);

        if (blockposition1 == null) {
            BlockPos blockposition2 = BlockPos.containing(vec3d.x + 0.5D, 75.0D, vec3d.z + 0.5D);

            TheEndGatewayBlockEntity.LOGGER.debug("Failed to find a suitable block to teleport to, spawning an island on {}", blockposition2);
            world.registryAccess().lookup(Registries.CONFIGURED_FEATURE).flatMap((iregistry) -> {
                return iregistry.get(EndFeatures.END_ISLAND);
            }).ifPresent((holder_c) -> {
                ((ConfiguredFeature) holder_c.value()).place(world, world.getChunkSource().getGenerator(), RandomSource.create(blockposition2.asLong()), blockposition2);
            });
            blockposition1 = blockposition2;
        } else {
            TheEndGatewayBlockEntity.LOGGER.debug("Found suitable block to teleport to: {}", blockposition1);
        }

        return TheEndGatewayBlockEntity.findTallestBlock(world, blockposition1, 16, true);
    }

    // Folia start - region threading
    private static void findOrCreateValidTeleportPosRegionThreading(ServerLevel world, BlockPos pos,
                                                                    ca.spottedleaf.concurrentutil.completable.CallbackCompletable<BlockPos> complete) {
        ca.spottedleaf.concurrentutil.completable.CallbackCompletable<Vec3> tentativeSelection = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();

        tentativeSelection.addWaiter((vec3d, throwable) -> {
            LevelChunk chunk = TheEndGatewayBlockEntity.getChunk(world, vec3d);
            BlockPos blockposition1 = TheEndGatewayBlockEntity.findValidSpawnInChunk(chunk);
            if (blockposition1 == null) {
                BlockPos blockposition2 = BlockPos.containing(vec3d.x + 0.5D, 75.0D, vec3d.z + 0.5D);

                TheEndGatewayBlockEntity.LOGGER.debug("Failed to find a suitable block to teleport to, spawning an island on {}", blockposition2);
                world.registryAccess().lookup(Registries.CONFIGURED_FEATURE).flatMap((iregistry) -> {
                    return iregistry.get(EndFeatures.END_ISLAND);
                }).ifPresent((holder_c) -> {
                    ((ConfiguredFeature) holder_c.value()).place(world, world.getChunkSource().getGenerator(), RandomSource.create(blockposition2.asLong()), blockposition2);
                });
                blockposition1 = blockposition2;
            } else {
                TheEndGatewayBlockEntity.LOGGER.debug("Found suitable block to teleport to: {}", blockposition1);
            }

            // Here, there is no guarantee the chunks in 1 radius are in this region due to the fact that we just chained
            // possibly 16x chunk loads along an axis (findExitPortalXZPosTentativeRegionThreading) using the chunk queue
            // (regioniser only guarantees at least 8 chunks along a single axis)
            // so, we need to schedule for the next tick
            int posX = blockposition1.getX();
            int posZ = blockposition1.getZ();
            int radius = 16;

            BlockPos finalBlockPosition1 = blockposition1;
            world.moonrise$loadChunksAsync(blockposition1, radius,
                ca.spottedleaf.concurrentutil.util.Priority.NORMAL,
                (java.util.List<net.minecraft.world.level.chunk.ChunkAccess> chunks) -> {
                    // make sure chunks are kept loaded
                    for (net.minecraft.world.level.chunk.ChunkAccess access : chunks) {
                        world.chunkSource.addTicketAtLevel(
                            net.minecraft.server.level.TicketType.DELAYED, access.getPos(),
                            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.FULL_LOADED_TICKET_LEVEL,
                            net.minecraft.util.Unit.INSTANCE
                        );
                    }
                    // now after the chunks are loaded, we can delay by one tick
                    io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                        world, posX >> 4, posZ >> 4, () -> {
                            // find final location
                            BlockPos tpLoc = TheEndGatewayBlockEntity.findTallestBlock(world, finalBlockPosition1, radius, true).above(GATEWAY_HEIGHT_ABOVE_SURFACE);

                            // done
                            complete.complete(tpLoc);
                        }
                    );
                }
            );
        });

        // fire off chain
        findExitPortalXZPosTentativeRegionThreading(world, pos, tentativeSelection);
    }

    private static void findExitPortalXZPosTentativeRegionThreading(ServerLevel world, BlockPos pos,
                                                                    ca.spottedleaf.concurrentutil.completable.CallbackCompletable<Vec3> complete) {
        Vec3 posDirFromOrigin = new Vec3(pos.getX(), 0.0D, pos.getZ()).normalize();
        Vec3 posDirExtruded = posDirFromOrigin.scale(1024.0D);

        class Vars {
            int i = 16;
            boolean mode = false;
            Vec3 currPos = posDirExtruded;
        }
        Vars vars = new Vars();

        Runnable handle = new Runnable() {
            @Override
            public void run() {
                if (vars.mode != TheEndGatewayBlockEntity.isChunkEmpty(world, vars.currPos)) {
                    vars.i = 0; // fall back to completing
                }

                // try to load next chunk
                if (vars.i-- <= 0) {
                    if (vars.mode) {
                        complete.complete(vars.currPos);
                        return;
                    }
                    vars.mode = true;
                    vars.i = 16;
                }

                vars.currPos = vars.currPos.add(posDirFromOrigin.scale(vars.mode ? 16.0 : -16.0));
                // schedule next iteration
                world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(
                    ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(vars.currPos),
                    ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(vars.currPos),
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
                    true,
                    ca.spottedleaf.concurrentutil.util.Priority.NORMAL,
                    (chunk) -> {
                        this.run();
                    }
                );
            }
        };

        // kick off first chunk load
        world.moonrise$getChunkTaskScheduler().scheduleChunkLoad(
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(posDirExtruded),
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(posDirExtruded),
            net.minecraft.world.level.chunk.status.ChunkStatus.FULL,
            true,
            ca.spottedleaf.concurrentutil.util.Priority.NORMAL,
            (chunk) -> {
                handle.run();
            }
        );
    }
    // Folia end - region threading

    private static Vec3 findExitPortalXZPosTentative(ServerLevel world, BlockPos pos) {
        Vec3 vec3d = (new Vec3((double) pos.getX(), 0.0D, (double) pos.getZ())).normalize();
        boolean flag = true;
        Vec3 vec3d1 = vec3d.scale(1024.0D);

        int i;

        for (i = 16; !TheEndGatewayBlockEntity.isChunkEmpty(world, vec3d1) && i-- > 0; vec3d1 = vec3d1.add(vec3d.scale(-16.0D))) {
            TheEndGatewayBlockEntity.LOGGER.debug("Skipping backwards past nonempty chunk at {}", vec3d1);
        }

        for (i = 16; TheEndGatewayBlockEntity.isChunkEmpty(world, vec3d1) && i-- > 0; vec3d1 = vec3d1.add(vec3d.scale(16.0D))) {
            TheEndGatewayBlockEntity.LOGGER.debug("Skipping forward past empty chunk at {}", vec3d1);
        }

        TheEndGatewayBlockEntity.LOGGER.debug("Found chunk at {}", vec3d1);
        return vec3d1;
    }

    private static boolean isChunkEmpty(ServerLevel world, Vec3 pos) {
        return TheEndGatewayBlockEntity.getChunk(world, pos).getHighestFilledSectionIndex() == -1;
    }

    private static BlockPos findTallestBlock(BlockGetter world, BlockPos pos, int searchRadius, boolean force) {
        BlockPos blockposition1 = null;

        for (int j = -searchRadius; j <= searchRadius; ++j) {
            for (int k = -searchRadius; k <= searchRadius; ++k) {
                if (j != 0 || k != 0 || force) {
                    for (int l = world.getMaxY(); l > (blockposition1 == null ? world.getMinY() : blockposition1.getY()); --l) {
                        BlockPos blockposition2 = new BlockPos(pos.getX() + j, l, pos.getZ() + k);
                        BlockState iblockdata = world.getBlockState(blockposition2);

                        if (iblockdata.isCollisionShapeFullBlock(world, blockposition2) && (force || !iblockdata.is(Blocks.BEDROCK))) {
                            blockposition1 = blockposition2;
                            break;
                        }
                    }
                }
            }
        }

        return blockposition1 == null ? pos : blockposition1;
    }

    private static LevelChunk getChunk(Level world, Vec3 pos) {
        return world.getChunk(Mth.floor(pos.x / 16.0D), Mth.floor(pos.z / 16.0D));
    }

    @Nullable
    private static BlockPos findValidSpawnInChunk(LevelChunk chunk) {
        ChunkPos chunkcoordintpair = chunk.getPos();
        BlockPos blockposition = new BlockPos(chunkcoordintpair.getMinBlockX(), 30, chunkcoordintpair.getMinBlockZ());
        int i = chunk.getHighestSectionPosition() + 16 - 1;
        BlockPos blockposition1 = new BlockPos(chunkcoordintpair.getMaxBlockX(), i, chunkcoordintpair.getMaxBlockZ());
        BlockPos blockposition2 = null;
        double d0 = 0.0D;
        Iterator iterator = BlockPos.betweenClosed(blockposition, blockposition1).iterator();

        while (iterator.hasNext()) {
            BlockPos blockposition3 = (BlockPos) iterator.next();
            BlockState iblockdata = chunk.getBlockState(blockposition3);
            BlockPos blockposition4 = blockposition3.above();
            BlockPos blockposition5 = blockposition3.above(2);

            if (iblockdata.is(Blocks.END_STONE) && !chunk.getBlockState(blockposition4).isCollisionShapeFullBlock(chunk, blockposition4) && !chunk.getBlockState(blockposition5).isCollisionShapeFullBlock(chunk, blockposition5)) {
                double d1 = blockposition3.distToCenterSqr(0.0D, 0.0D, 0.0D);

                if (blockposition2 == null || d1 < d0) {
                    blockposition2 = blockposition3;
                    d0 = d1;
                }
            }
        }

        return blockposition2;
    }

    private static void spawnGatewayPortal(ServerLevel world, BlockPos pos, EndGatewayConfiguration config) {
        Feature.END_GATEWAY.place(config, world, world.getChunkSource().getGenerator(), RandomSource.create(), pos);
    }

    @Override
    public boolean shouldRenderFace(Direction direction) {
        return Block.shouldRenderFace(this.getBlockState(), this.level.getBlockState(this.getBlockPos().relative(direction)), direction);
    }

    public int getParticleAmount() {
        int i = 0;
        Direction[] aenumdirection = Direction.values();
        int j = aenumdirection.length;

        for (int k = 0; k < j; ++k) {
            Direction enumdirection = aenumdirection[k];

            i += this.shouldRenderFace(enumdirection) ? 1 : 0;
        }

        return i;
    }

    public void setExitPosition(BlockPos pos, boolean exactTeleport) {
        this.exactTeleport = exactTeleport;
        this.exitPortal = pos;
        this.setChanged();
    }
}
