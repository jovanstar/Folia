package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.FileUtil;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkScanAccess;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

public class ServerChunkCache extends ChunkSource implements ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache { // Paper - rewrite chunk system

    private static final Logger LOGGER = LogUtils.getLogger();
    private final DistanceManager distanceManager;
    private final ServerLevel level;
    public final Thread mainThread;
    final ThreadedLevelLightEngine lightEngine;
    public final ServerChunkCache.MainThreadExecutor mainThreadProcessor;
    public final ChunkMap chunkMap;
    private final DimensionDataStorage dataStorage;
    //private long lastInhabitedUpdate; // Folia - region threading
    public boolean spawnEnemies = true;
    public boolean spawnFriendlies = true;
    private static final int CACHE_SIZE = 4;
    private final long[] lastChunkPos = new long[4];
    private final ChunkStatus[] lastChunkStatus = new ChunkStatus[4];
    private final ChunkAccess[] lastChunk = new ChunkAccess[4];
    // Folia - moved to regionised world data
    // Paper start
    private final ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable<net.minecraft.world.level.chunk.LevelChunk> fullChunks = new ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable<>();
    public int getFullChunksCount() {
        return this.fullChunks.size();
    }
    long chunkFutureAwaitCounter;
    // Paper end
    // Paper start - rewrite chunk system

    @Override
    public final void moonrise$setFullChunk(final int chunkX, final int chunkZ, final LevelChunk chunk) {
        final long key = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ);
        if (chunk == null) {
            this.fullChunks.remove(key);
        } else {
            this.fullChunks.put(key, chunk);
        }
    }

    @Override
    public final LevelChunk moonrise$getFullChunkIfLoaded(final int chunkX, final int chunkZ) {
        return this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
    }

    private ChunkAccess syncLoad(final int chunkX, final int chunkZ, final ChunkStatus toStatus) {
        // Folia start - region threading
        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Cannot asynchronously load chunks");
        }
        // Folia end - region threading
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final CompletableFuture<ChunkAccess> completable = new CompletableFuture<>();
        chunkTaskScheduler.scheduleChunkLoad(
            chunkX, chunkZ, toStatus, true, ca.spottedleaf.concurrentutil.util.Priority.BLOCKING,
            completable::complete
        );

        if (!completable.isDone() && chunkTaskScheduler.hasShutdown()) {
            throw new IllegalStateException(
                "Chunk system has shut down, cannot process chunk requests in world '" + ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(this.level) + "' at "
                    + "(" + chunkX + "," + chunkZ + ") status: " + toStatus
            );
        }

        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.level, chunkX, chunkZ)) {
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.pushChunkWait(this.level, chunkX, chunkZ);
            this.mainThreadProcessor.managedBlock(completable::isDone);
            ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.popChunkWait();
        }

        final ChunkAccess ret = completable.join();
        if (ret == null) {
            throw new IllegalStateException("Chunk not loaded when requested");
        }

        return ret;
    }

    private ChunkAccess getChunkFallback(final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                         final boolean load) {
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler();
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder currentChunk = chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));

        final ChunkAccess ifPresent = currentChunk == null ? null : currentChunk.getChunkIfPresent(toStatus);

        if (ifPresent != null && (toStatus != ChunkStatus.FULL || currentChunk.isFullChunkReady())) {
            return ifPresent;
        }

        final ca.spottedleaf.moonrise.common.PlatformHooks platformHooks = ca.spottedleaf.moonrise.common.PlatformHooks.get();

        if (platformHooks.hasCurrentlyLoadingChunk() && currentChunk != null) {
            final ChunkAccess loading = platformHooks.getCurrentlyLoadingChunk(currentChunk.vanillaChunkHolder);
            if (loading != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
                return loading;
            }
        }

        return load ? this.syncLoad(chunkX, chunkZ, toStatus) : null;
    }
    // Paper end - rewrite chunk system
    // Paper start - chunk tick iteration optimisations
    private final ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom shuffleRandom = new ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom(0L);
    private boolean isChunkNearPlayer(final ChunkMap chunkMap, final ChunkPos chunkPos, final LevelChunk levelChunk) {
        final ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkData chunkData = ((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemChunkHolder)((ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk)levelChunk).moonrise$getChunkAndHolder().holder())
            .moonrise$getRealChunkHolder().holderData;
        final ca.spottedleaf.moonrise.common.misc.NearbyPlayers.TrackedChunk nearbyPlayers = chunkData.nearbyPlayers;
        if (nearbyPlayers == null) {
            return false;
        }

        final ca.spottedleaf.moonrise.common.list.ReferenceList<ServerPlayer> players = nearbyPlayers.getPlayers(ca.spottedleaf.moonrise.common.misc.NearbyPlayers.NearbyMapType.SPAWN_RANGE);

        if (players == null) {
            return false;
        }

        final ServerPlayer[] raw = players.getRawDataUnchecked();
        final int len = players.size();

        Objects.checkFromIndexSize(0, len, raw.length);
        for (int i = 0; i < len; ++i) {
            if (chunkMap.playerIsCloseEnoughForSpawning(raw[i], chunkPos, 16384.0D)) { // Spigot (reducedRange = false)
                return true;
            }
        }

        return false;
    }
    // Paper end - chunk tick iteration optimisations


    public ServerChunkCache(ServerLevel world, LevelStorageSource.LevelStorageAccess session, DataFixer dataFixer, StructureTemplateManager structureTemplateManager, Executor workerExecutor, ChunkGenerator chunkGenerator, int viewDistance, int simulationDistance, boolean dsync, ChunkProgressListener worldGenerationProgressListener, ChunkStatusUpdateListener chunkStatusChangeListener, Supplier<DimensionDataStorage> persistentStateManagerFactory) {
        this.level = world;
        this.mainThreadProcessor = new ServerChunkCache.MainThreadExecutor(world);
        this.mainThread = Thread.currentThread();
        Path path = session.getDimensionPath(world.dimension()).resolve("data");

        try {
            FileUtil.createDirectoriesSafe(path);
        } catch (IOException ioexception) {
            ServerChunkCache.LOGGER.error("Failed to create dimension data storage directory", ioexception);
        }

        this.dataStorage = new DimensionDataStorage(path, dataFixer, world.registryAccess());
        this.chunkMap = new ChunkMap(world, session, dataFixer, structureTemplateManager, workerExecutor, this.mainThreadProcessor, this, chunkGenerator, worldGenerationProgressListener, chunkStatusChangeListener, persistentStateManagerFactory, viewDistance, dsync);
        this.lightEngine = this.chunkMap.getLightEngine();
        this.distanceManager = this.chunkMap.getDistanceManager();
        this.distanceManager.updateSimulationDistance(simulationDistance);
        this.clearCache();
    }

    // CraftBukkit start - properly implement isChunkLoaded
    public boolean isChunkLoaded(int chunkX, int chunkZ) {
        ChunkHolder chunk = this.chunkMap.getUpdatingChunkIfPresent(ChunkPos.asLong(chunkX, chunkZ));
        if (chunk == null) {
            return false;
        }
        return chunk.getFullChunkNow() != null;
    }
    // CraftBukkit end
    // Paper start
    // Paper - rewrite chunk system

    @Nullable
    public ChunkAccess getChunkAtImmediately(int x, int z) {
        ChunkHolder holder = this.chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x, z));
        if (holder == null) {
            return null;
        }

        return holder.getLatestChunk();
    }

    public <T> void addTicketAtLevel(TicketType<T> ticketType, ChunkPos chunkPos, int ticketLevel, T identifier) {
        this.distanceManager.addTicket(ticketType, chunkPos, ticketLevel, identifier);
    }

    public <T> void removeTicketAtLevel(TicketType<T> ticketType, ChunkPos chunkPos, int ticketLevel, T identifier) {
        this.distanceManager.removeTicket(ticketType, chunkPos, ticketLevel, identifier);
    }

    // "real" get chunk if loaded
    // Note: Partially copied from the getChunkAt method below
    @Nullable
    public LevelChunk getChunkAtIfCachedImmediately(int x, int z) {
        long k = ChunkPos.asLong(x, z);

        // Note: Bypass cache since we need to check ticket level, and to make this MT-Safe

        ChunkHolder playerChunk = this.getVisibleChunkIfPresent(k);
        if (playerChunk == null) {
            return null;
        }

        return playerChunk.getFullChunkNowUnchecked();
    }

    @Nullable
    public LevelChunk getChunkAtIfLoadedImmediately(int x, int z) {
        return this.fullChunks.get(ChunkPos.asLong(x, z));
    }
    // Paper end

    @Override
    public ThreadedLevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    @Nullable
    private ChunkHolder getVisibleChunkIfPresent(long pos) {
        return this.chunkMap.getVisibleChunkIfPresent(pos);
    }

    public int getTickingGenerated() {
        return this.chunkMap.getTickingGenerated();
    }

    private void storeInCache(long pos, @Nullable ChunkAccess chunk, ChunkStatus status) {
        for (int j = 3; j > 0; --j) {
            this.lastChunkPos[j] = this.lastChunkPos[j - 1];
            this.lastChunkStatus[j] = this.lastChunkStatus[j - 1];
            this.lastChunk[j] = this.lastChunk[j - 1];
        }

        this.lastChunkPos[0] = pos;
        this.lastChunkStatus[0] = status;
        this.lastChunk[0] = chunk;
    }

    @Nullable
    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus leastStatus, boolean create) {
        // Paper start - rewrite chunk system
        if (leastStatus == ChunkStatus.FULL) {
            final LevelChunk ret = this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(x, z));

            if (ret != null) {
                return ret;
            }

            return create ? this.getChunkFallback(x, z, leastStatus, create) : null;
        }

        return this.getChunkFallback(x, z, leastStatus, create);
        // Paper end - rewrite chunk system
    }

    @Nullable
    @Override
    public LevelChunk getChunkNow(int chunkX, int chunkZ) {
        // Paper start - rewrite chunk system
        final LevelChunk ret = this.fullChunks.get(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
        if (!ca.spottedleaf.moonrise.common.PlatformHooks.get().hasCurrentlyLoadingChunk()) {
            return ret;
        }

        if (ret != null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return ret;
        }

        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler()
            .chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (holder == null) {
            return ret;
        }

        return ca.spottedleaf.moonrise.common.PlatformHooks.get().getCurrentlyLoadingChunk(holder.vanillaChunkHolder);
        // Paper end - rewrite chunk system
    }

    private void clearCache() {
        Arrays.fill(this.lastChunkPos, ChunkPos.INVALID_CHUNK_POS);
        Arrays.fill(this.lastChunkStatus, (Object) null);
        Arrays.fill(this.lastChunk, (Object) null);
    }

    public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFuture(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        if (true) throw new UnsupportedOperationException(); // Folia - region threading
        boolean flag1 = Thread.currentThread() == this.mainThread;
        CompletableFuture completablefuture;

        if (flag1) {
            completablefuture = this.getChunkFutureMainThread(chunkX, chunkZ, leastStatus, create);
            ServerChunkCache.MainThreadExecutor chunkproviderserver_b = this.mainThreadProcessor;

            Objects.requireNonNull(completablefuture);
            chunkproviderserver_b.managedBlock(completablefuture::isDone);
        } else {
            completablefuture = CompletableFuture.supplyAsync(() -> {
                return this.getChunkFutureMainThread(chunkX, chunkZ, leastStatus, create);
            }, this.mainThreadProcessor).thenCompose((completablefuture1) -> {
                return completablefuture1;
            });
        }

        return completablefuture;
    }

    private CompletableFuture<ChunkResult<ChunkAccess>> getChunkFutureMainThread(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) {
        // Paper start - rewrite chunk system
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this.level, chunkX, chunkZ, "Scheduling chunk load off-main");

        final int minLevel = ChunkLevel.byStatus(leastStatus);
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);

        final boolean needsFullScheduling = leastStatus == ChunkStatus.FULL && (chunkHolder == null || !chunkHolder.getChunkStatus().isOrAfter(FullChunkStatus.FULL));

        if ((chunkHolder == null || chunkHolder.getTicketLevel() > minLevel || needsFullScheduling) && !create) {
            return ChunkHolder.UNLOADED_CHUNK_FUTURE;
        }

        final ChunkAccess ifPresent = chunkHolder == null ? null : chunkHolder.getChunkIfPresent(leastStatus);
        if (needsFullScheduling || ifPresent == null) {
            // schedule
            final CompletableFuture<ChunkResult<ChunkAccess>> ret = new CompletableFuture<>();
            final Consumer<ChunkAccess> complete = (ChunkAccess chunk) -> {
                if (chunk == null) {
                    ret.complete(ChunkHolder.UNLOADED_CHUNK);
                } else {
                    ret.complete(ChunkResult.of(chunk));
                }
            };

            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(
                chunkX, chunkZ, leastStatus, true,
                ca.spottedleaf.concurrentutil.util.Priority.HIGHER,
                complete
            );

            return ret;
        } else {
            // can return now
            return CompletableFuture.completedFuture(ChunkResult.of(ifPresent));
        }
        // Paper end - rewrite chunk system
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return this.getChunkNow(x, z) != null; // Paper - rewrite chunk system
    }

    @Nullable
    @Override
    public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (newChunkHolder == null) {
            return null;
        }
        return newChunkHolder.getChunkIfPresentUnchecked(ChunkStatus.INITIALIZE_LIGHT.getParent());
        // Paper end - rewrite chunk system
    }

    @Override
    public Level getLevel() {
        return this.level;
    }

    public boolean pollTask() {
        return this.mainThreadProcessor.pollTask();
    }

    public boolean runDistanceManagerUpdates() { // Paper - public
        return ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.processTicketUpdates(); // Paper - rewrite chunk system
    }

    public boolean isPositionTicking(long pos) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(pos);
        return newChunkHolder != null && newChunkHolder.isTickingReady();
        // Paper end - rewrite chunk system
    }

    public void save(boolean flush) {
        // Paper - rewrite chunk system
        this.chunkMap.saveAllChunks(flush);
    }

    @Override
    public void close() throws IOException {
        // CraftBukkit start
        this.close(true);
    }

    public void close(boolean save) throws IOException {
        // CraftBukkit end
        // Paper - rewrite chunk system
        this.dataStorage.close();
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getChunkTaskScheduler().chunkHolderManager.close(save, true); // Paper - rewrite chunk system
    }

    // CraftBukkit start - modelled on below
    public void purgeUnload() {
        if (true) return; // Paper - rewrite chunk system
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("purge");
        this.distanceManager.purgeStaleTickets();
        this.runDistanceManagerUpdates();
        gameprofilerfiller.popPush("unload");
        this.chunkMap.tick(() -> true);
        gameprofilerfiller.pop();
        this.clearCache();
    }
    // CraftBukkit end

    @Override
    public void tick(BooleanSupplier shouldKeepTicking, boolean tickChunks) {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("purge");
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_HOLDER_MANAGER_TICK); try { // Folia - profiler
        if (this.level.tickRateManager().runsNormally() || !tickChunks || this.level.spigotConfig.unloadFrozenChunks) { // Spigot
            this.distanceManager.purgeStaleTickets();
        }

        this.runDistanceManagerUpdates();
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_HOLDER_MANAGER_TICK); } // Folia - profiler
        gameprofilerfiller.popPush("chunks");
        if (tickChunks) {
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_CHUNK_LOADER_TICK); try { // Folia - profiler
            ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().tick(); // Paper - rewrite chunk system
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.PLAYER_CHUNK_LOADER_TICK); } // Folia - profiler
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK); try { // Folia - profiler
            this.tickChunks();
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK); } // Folia - profiler
            this.chunkMap.tick();
        }

        gameprofilerfiller.popPush("unload");
        this.chunkMap.tick(shouldKeepTicking);
        gameprofilerfiller.pop();
        this.clearCache();
    }

    private void tickChunks() {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        //long i = this.level.getGameTime(); // Folia - region threading
        long j = 1L; // Folia - region threading

        //this.lastInhabitedUpdate = i; // Folia - region threading
        if (!this.level.isDebug()) {
            ProfilerFiller gameprofilerfiller = Profiler.get();

            gameprofilerfiller.push("pollingChunks");
            if (this.level.tickRateManager().runsNormally()) {
                List<LevelChunk> list = regionizedWorldData.temporaryChunkTickList; // Folia - region threading

                try {
                    gameprofilerfiller.push("filteringTickingChunks");
                    foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK_COLLECT_CHUNKS); try { // Folia - profiler
                    this.collectTickingChunks(list);
                    } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_TICK_COLLECT_CHUNKS); } // Folia - profiler
                    gameprofilerfiller.popPush("shuffleChunks");
                    // Paper start - chunk tick iteration optimisation
                    this.shuffleRandom.setSeed(this.level.random.nextLong());
                    if (!this.level.paperConfig().entities.spawning.perPlayerMobSpawns) Util.shuffle(list, this.shuffleRandom); // Paper - Optional per player mob spawns; do not need this when per-player is enabled
                    // Paper end - chunk tick iteration optimisation
                    this.tickChunks(gameprofilerfiller, j, list);
                    gameprofilerfiller.pop();
                } finally {
                    list.clear();
                }
            }

            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BROADCAST_BLOCK_CHANGES); try { // Folia - profiler
            this.broadcastChangedChunks(gameprofilerfiller);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BROADCAST_BLOCK_CHANGES); } // Folia - profiler
            gameprofilerfiller.pop();
        }
    }

    private void broadcastChangedChunks(ProfilerFiller profiler) {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        profiler.push("broadcast");
        Iterator iterator = regionizedWorldData.chunkHoldersToBroadcast.iterator(); // Folia - region threading - note: do not need to thread check, as getChunkToSend is only non-null when the chunkholder is loaded

        while (iterator.hasNext()) {
            ChunkHolder playerchunk = (ChunkHolder) iterator.next();
            LevelChunk chunk = playerchunk.getChunkToSend(); // Paper - rewrite chunk system

            if (chunk != null) {
                playerchunk.broadcastChanges(chunk);
            }
        }

        regionizedWorldData.chunkHoldersToBroadcast.clear(); // Folia - region threading
        profiler.pop();
    }

    private void collectTickingChunks(List<LevelChunk> chunks) {
        // Paper start - chunk tick iteration optimisation
        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.server.level.ServerChunkCache.ChunkAndHolder> tickingChunks =
            this.level.getCurrentWorldData().getEntityTickingChunks(); // Folia - region threading

        final ServerChunkCache.ChunkAndHolder[] raw = tickingChunks.getRawDataUnchecked();
        final int size = tickingChunks.size();

        final ChunkMap chunkMap = this.chunkMap;

        for (int i = 0; i < size; ++i) {
            final ServerChunkCache.ChunkAndHolder chunkAndHolder = raw[i];
            final LevelChunk levelChunk = chunkAndHolder.chunk();

            if (!this.isChunkNearPlayer(chunkMap, levelChunk.getPos(), levelChunk)) {
                continue;
            }

            chunks.add(levelChunk);
        }
        // Paper end - chunk tick iteration optimisation
    }

    private void tickChunks(ProfilerFiller profiler, long timeDelta, List<LevelChunk> chunks) {
        io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.level.getCurrentWorldData(); // Folia - region threading
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle foliaProfiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        profiler.popPush("naturalSpawnCount");
        int j = this.distanceManager.getNaturalSpawnChunkCount();
        // Paper start - Optional per player mob spawns
        final int naturalSpawnChunkCount = j;
        NaturalSpawner.SpawnState spawnercreature_d; // moved down
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MOB_SPAWN_ENTITY_COUNT); try { // Folia - profiler
        if ((this.spawnFriendlies || this.spawnEnemies) && this.level.paperConfig().entities.spawning.perPlayerMobSpawns) { // don't count mobs when animals and monsters are disabled
            // re-set mob counts
            for (ServerPlayer player : this.level.getLocalPlayers()) { // Folia - region threading
                // Paper start - per player mob spawning backoff
                for (int ii = 0; ii < ServerPlayer.MOBCATEGORY_TOTAL_ENUMS; ii++) {
                    player.mobCounts[ii] = 0;

                    int newBackoff = player.mobBackoffCounts[ii] - 1; // TODO make configurable bleed // TODO use nonlinear algorithm?
                    if (newBackoff < 0) {
                        newBackoff = 0;
                    }
                    player.mobBackoffCounts[ii] = newBackoff;
                }
                // Paper end - per player mob spawning backoff
            }
            spawnercreature_d = NaturalSpawner.createState(naturalSpawnChunkCount, regionizedWorldData.getLoadedEntities(), this::getFullChunk, null, true); // Folia - region threading - note: function only cares about loaded entities, doesn't need all
        } else {
            spawnercreature_d = NaturalSpawner.createState(naturalSpawnChunkCount, regionizedWorldData.getLoadedEntities(), this::getFullChunk, !this.level.paperConfig().entities.spawning.perPlayerMobSpawns ? new LocalMobCapCalculator(this.chunkMap) : null, false); // Folia - region threading - note: function only cares about loaded entities, doesn't need all
        }
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MOB_SPAWN_ENTITY_COUNT); } // Folia - profiler
        // Paper end - Optional per player mob spawns

        regionizedWorldData.lastSpawnState = spawnercreature_d; // Folia - region threading
        profiler.popPush("spawnAndTick");
        boolean flag = this.level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && !this.level.getLocalPlayers().isEmpty(); // CraftBukkit // Folia - region threading
        int k = this.level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        List list1;

        if (flag && (this.spawnEnemies || this.spawnFriendlies)) {
            // Paper start - PlayerNaturallySpawnCreaturesEvent
            for (ServerPlayer entityPlayer : this.level.getLocalPlayers()) { // Folia - region threading
                int chunkRange = Math.min(level.spigotConfig.mobSpawnRange, entityPlayer.getBukkitEntity().getViewDistance());
                chunkRange = Math.min(chunkRange, 8);
                entityPlayer.playerNaturallySpawnedEvent = new com.destroystokyo.paper.event.entity.PlayerNaturallySpawnCreaturesEvent(entityPlayer.getBukkitEntity(), (byte) chunkRange);
                entityPlayer.playerNaturallySpawnedEvent.callEvent();
            }
            // Paper end - PlayerNaturallySpawnCreaturesEvent
            boolean flag1 = this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) != 0L && this.level.getRedstoneGameTime() % this.level.ticksPerSpawnCategory.getLong(org.bukkit.entity.SpawnCategory.ANIMAL) == 0L; // CraftBukkit // Folia - region threading

            list1 = NaturalSpawner.getFilteredSpawningCategories(spawnercreature_d, this.spawnFriendlies, this.spawnEnemies, flag1, this.level); // CraftBukkit
        } else {
            list1 = List.of();
        }

        Iterator iterator = chunks.iterator();

        long spawnChunkCount = 0L; // Folia - profiler
        long randomChunkCount = 0L; // Folia - profiler
        foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_AND_RANDOM_TICK); try { // Folia - profiler
        while (iterator.hasNext()) {
            LevelChunk chunk = (LevelChunk) iterator.next();
            ChunkPos chunkcoordintpair = chunk.getPos();

            chunk.incrementInhabitedTime(timeDelta);
            if (!list1.isEmpty() && this.level.getWorldBorder().isWithinBounds(chunkcoordintpair) && this.chunkMap.anyPlayerCloseEnoughForSpawning(chunkcoordintpair, true)) { // Spigot
                NaturalSpawner.spawnForChunk(this.level, chunk, spawnercreature_d, list1);
            }

            if (true) { // Paper - rewrite chunk system
                this.level.tickChunk(chunk, k);
            }
        }
        foliaProfiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_CHUNK_COUNT, spawnChunkCount); // Folia - profiler
        foliaProfiler.addCounter(ca.spottedleaf.leafprofiler.LProfilerRegistry.RANDOM_CHUNK_TICK_COUNT, randomChunkCount); // Folia - profiler
        } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.SPAWN_AND_RANDOM_TICK); } // Folia - profiler

        profiler.popPush("customSpawners");
        if (flag) {
            foliaProfiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MISC_MOB_SPAWN_TICK); try { // Folia - profiler
            this.level.tickCustomSpawners(this.spawnEnemies, this.spawnFriendlies);
            } finally { foliaProfiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.MISC_MOB_SPAWN_TICK); } // Folia - profiler
        }

    }

    private void getFullChunk(long pos, Consumer<LevelChunk> chunkConsumer) {
        // Paper start - rewrite chunk system
        // note: bypass currentlyLoaded from getChunkNow
        final LevelChunk fullChunk = this.fullChunks.get(pos);
        if (fullChunk != null) {
            chunkConsumer.accept(fullChunk);
        }
        // Paper end - rewrite chunk system

    }

    @Override
    public String gatherStats() {
        return Integer.toString(this.getLoadedChunksCount());
    }

    @VisibleForTesting
    public int getPendingTasksCount() {
        return this.mainThreadProcessor.getPendingTasksCount();
    }

    public ChunkGenerator getGenerator() {
        return this.chunkMap.generator();
    }

    public ChunkGeneratorStructureState getGeneratorState() {
        return this.chunkMap.generatorState();
    }

    public RandomState randomState() {
        return this.chunkMap.randomState();
    }

    @Override
    public int getLoadedChunksCount() {
        return this.chunkMap.size();
    }

    public void blockChanged(BlockPos pos) {
        int i = SectionPos.blockToSectionCoord(pos.getX());
        int j = SectionPos.blockToSectionCoord(pos.getZ());
        ChunkHolder playerchunk = this.getVisibleChunkIfPresent(ChunkPos.asLong(i, j));

        if (playerchunk != null && playerchunk.blockChanged(pos)) {
            this.level.getCurrentWorldData().chunkHoldersToBroadcast.add(playerchunk); // Folia - region threading
        }

    }

    @Override
    public void onLightUpdate(LightLayer type, SectionPos pos) {
        Runnable run = () -> { // Folia - region threading
            ChunkHolder playerchunk = this.getVisibleChunkIfPresent(pos.chunk().toLong());

            if (playerchunk != null && playerchunk.sectionLightChanged(type, pos.y())) {
                this.level.getCurrentWorldData().chunkHoldersToBroadcast.add(playerchunk); // Folia - region threading
            }

        }; // Folia - region threading
        // Folia start - region threading
        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(
            this.level, pos.getX(), pos.getZ(), run
        );
        // Folia end - region threading
    }

    public <T> void addRegionTicket(TicketType<T> ticketType, ChunkPos pos, int radius, T argument) {
        this.distanceManager.addRegionTicket(ticketType, pos, radius, argument);
    }

    public <T> void removeRegionTicket(TicketType<T> ticketType, ChunkPos pos, int radius, T argument) {
        this.distanceManager.removeRegionTicket(ticketType, pos, radius, argument);
    }

    @Override
    public void updateChunkForced(ChunkPos pos, boolean forced) {
        this.distanceManager.updateChunkForced(pos, forced);
    }

    public void move(ServerPlayer player) {
        if (!player.isRemoved()) {
            this.chunkMap.move(player);
        }

    }

    public void removeEntity(Entity entity) {
        this.chunkMap.removeEntity(entity);
    }

    public void addEntity(Entity entity) {
        this.chunkMap.addEntity(entity);
    }

    public void broadcastAndSend(Entity entity, Packet<?> packet) {
        this.chunkMap.broadcastAndSend(entity, packet);
    }

    public void broadcast(Entity entity, Packet<?> packet) {
        this.chunkMap.broadcast(entity, packet);
    }

    public void setViewDistance(int watchDistance) {
        this.chunkMap.setServerViewDistance(watchDistance);
    }

    // Paper start - rewrite chunk system
    public void setSendViewDistance(int viewDistance) {
        ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel)this.level).moonrise$getPlayerChunkLoader().setSendDistance(viewDistance);
    }
    // Paper end - rewrite chunk system

    public void setSimulationDistance(int simulationDistance) {
        this.distanceManager.updateSimulationDistance(simulationDistance);
    }

    @Override
    public void setSpawnSettings(boolean spawnMonsters) {
        // CraftBukkit start
        this.setSpawnSettings(spawnMonsters, this.spawnFriendlies);
    }

    public void setSpawnSettings(boolean flag, boolean spawnFriendlies) {
        this.spawnEnemies = flag;
        this.spawnFriendlies = spawnFriendlies;
        // CraftBukkit end
    }

    public String getChunkDebugData(ChunkPos pos) {
        return this.chunkMap.getChunkDebugData(pos);
    }

    public DimensionDataStorage getDataStorage() {
        return this.dataStorage;
    }

    public PoiManager getPoiManager() {
        return this.chunkMap.getPoiManager();
    }

    public ChunkScanAccess chunkScanner() {
        return this.chunkMap.chunkScanner();
    }

    @Nullable
    @VisibleForDebug
    public NaturalSpawner.SpawnState getLastSpawnState() {
        io.papermc.paper.threadedregions.RegionizedWorldData worldData = this.level.getCurrentWorldData(); // Folia - region threading
        return worldData == null ? null : worldData.lastSpawnState; // Folia - region threading
    }

    public void removeTicketsOnClosing() {
        this.distanceManager.removeTicketsOnClosing();
    }

    public void onChunkReadyToSend(ChunkHolder chunkHolder) {
        if (chunkHolder.hasChangesToBroadcast()) {
            throw new UnsupportedOperationException(); // Folia - region threading
        }

    }

    public final class MainThreadExecutor extends BlockableEventLoop<Runnable> {

        MainThreadExecutor(final Level world) {
            super("Chunk source main thread executor for " + String.valueOf(world.dimension().location()));
        }

        @Override
        public void managedBlock(BooleanSupplier stopCondition) {
            super.managedBlock(() -> {
                return MinecraftServer.throwIfFatalException() && stopCondition.getAsBoolean();
            });
        }

        @Override
        public Runnable wrapRunnable(Runnable runnable) {
            return runnable;
        }

        @Override
        protected boolean shouldRun(Runnable task) {
            return true;
        }

        @Override
        protected boolean scheduleExecutables() {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return ServerChunkCache.this.mainThread;
        }

        // Folia start - region threading
        @Override
        public <V> CompletableFuture<V> submit(Supplier<V> task) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            return super.submit(task);
        }

        @Override
        public CompletableFuture<Void> submit(Runnable task) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            return super.submit(task);
        }

        @Override
        public void schedule(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.schedule(runnable);
        }

        @Override
        public void executeBlocking(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.executeBlocking(runnable);
        }

        @Override
        public void execute(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.execute(runnable);
        }

        @Override
        public void executeIfPossible(Runnable runnable) {
            if (true) {
                throw new UnsupportedOperationException();
            }
            super.executeIfPossible(runnable);
        }
        // Folia end - region threading

        @Override
        protected void doRunTask(Runnable task) {
            if (true) throw new UnsupportedOperationException(); // Folia - region threading
            Profiler.get().incrementCounter("runTask");
            super.doRunTask(task);
        }

        @Override
        // CraftBukkit start - process pending Chunk loadCallback() and unloadCallback() after each run task
        public boolean pollTask() {
            // Folia start - region threading
            if (ServerChunkCache.this.level != io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegionizedWorldData().world) {
                throw new IllegalStateException("Polling tasks from non-owned region");
            }
            // Folia end - region threading
            // Paper start - rewrite chunk system
            final ServerChunkCache serverChunkCache = ServerChunkCache.this;
            if (serverChunkCache.runDistanceManagerUpdates()) {
                return true;
            } else {
                return io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion().getData().getTaskQueueData().executeChunkTask(); // Folia - region threading
            }
            // Paper end - rewrite chunk system
        // CraftBukkit end
        }
    }

    public static record ChunkAndHolder(LevelChunk chunk, ChunkHolder holder) { // Paper - rewrite chunk system - public

    }
}
