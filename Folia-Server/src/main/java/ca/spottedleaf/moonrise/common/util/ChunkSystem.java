package ca.spottedleaf.moonrise.common.util;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.chunk.ChunkSystemLevelChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader;
import ca.spottedleaf.moonrise.patches.chunk_system.world.ChunkSystemServerChunkCache;
import ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickServerLevel;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import java.util.List;
import java.util.function.Consumer;

public final class ChunkSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run) {
        scheduleChunkTask(level, chunkX, chunkZ, run, Priority.NORMAL);
    }

    public static void scheduleChunkTask(final ServerLevel level, final int chunkX, final int chunkZ, final Runnable run, final Priority priority) {
        ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkTask(chunkX, chunkZ, run, priority);
    }

    public static void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final boolean gen,
                                         final ChunkStatus toStatus, final boolean addTicket, final Priority priority,
                                         final Consumer<ChunkAccess> onComplete) {
        ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, gen, toStatus, addTicket, priority, onComplete);
    }

    public static void scheduleChunkLoad(final ServerLevel level, final int chunkX, final int chunkZ, final ChunkStatus toStatus,
                                         final boolean addTicket, final Priority priority, final Consumer<ChunkAccess> onComplete) {
        ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleChunkLoad(chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
    }

    public static void scheduleTickingState(final ServerLevel level, final int chunkX, final int chunkZ,
                                            final FullChunkStatus toStatus, final boolean addTicket,
                                            final Priority priority, final Consumer<LevelChunk> onComplete) {
        ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().scheduleTickingState(chunkX, chunkZ, toStatus, addTicket, priority, onComplete);
    }

    public static List<ChunkHolder> getVisibleChunkHolders(final ServerLevel level) {
        return ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.getOldChunkHolders();
    }

    public static List<ChunkHolder> getUpdatingChunkHolders(final ServerLevel level) {
        return ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.getOldChunkHolders();
    }

    public static int getVisibleChunkHolderCount(final ServerLevel level) {
        return ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.size();
    }

    public static int getUpdatingChunkHolderCount(final ServerLevel level) {
        return ((ChunkSystemServerLevel)level).moonrise$getChunkTaskScheduler().chunkHolderManager.size();
    }

    public static boolean hasAnyChunkHolders(final ServerLevel level) {
        return getUpdatingChunkHolderCount(level) != 0;
    }

    public static boolean screenEntity(final ServerLevel level, final Entity entity, final boolean fromDisk, final boolean event) {
        if (!PlatformHooks.get().screenEntity(level, entity, fromDisk, event)) {
            return false;
        }
        return true;
    }

    public static void onChunkHolderCreate(final ServerLevel level, final ChunkHolder holder) {
        // Folia start - threaded regions
        level.regioniser.addChunk(holder.getPos().x, holder.getPos().z);
        // Folia end - threaded regions
    }

    public static void onChunkHolderDelete(final ServerLevel level, final ChunkHolder holder) {
        // Update progress listener for LevelLoadingScreen
        final ChunkProgressListener progressListener = null; // Folia - threaded regions - cannot schedule chunk task here; as it would create a chunkholder
        if (progressListener != null) {
            ChunkSystem.scheduleChunkTask(level, holder.getPos().x, holder.getPos().z, () -> {
                progressListener.onStatusChange(holder.getPos(), null);
            });
        }
        // Folia start - threaded regions
        level.regioniser.removeChunk(holder.getPos().x, holder.getPos().z);
        // Folia end - threaded regions
    }

    public static void onChunkPreBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ChunkSystemServerChunkCache)((ServerLevel)chunk.getLevel()).getChunkSource())
                .moonrise$setFullChunk(chunk.getPos().x, chunk.getPos().z, chunk);
    }

    public static void onChunkBorder(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().addChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
        chunk.loadCallback();
    }

    public static void onChunkNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().removeChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
        chunk.unloadCallback();
    }

    public static void onChunkPostNotBorder(final LevelChunk chunk, final ChunkHolder holder) {
        ((ChunkSystemServerChunkCache)((ServerLevel)chunk.getLevel()).getChunkSource())
                .moonrise$setFullChunk(chunk.getPos().x, chunk.getPos().z, null);
    }

    public static void onChunkTicking(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().addTickingChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
        if (!((ChunkSystemLevelChunk)chunk).moonrise$isPostProcessingDone()) {
            chunk.postProcessGeneration((ServerLevel)chunk.getLevel());
        }
        ((ServerLevel)chunk.getLevel()).startTickingChunk(chunk);
        ((ServerLevel)chunk.getLevel()).getChunkSource().chunkMap.tickingGenerated.incrementAndGet();
        ((ChunkTickServerLevel)(ServerLevel)chunk.getLevel()).moonrise$markChunkForPlayerTicking(chunk); // Moonrise - chunk tick iteration
    }

    public static void onChunkNotTicking(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().removeTickingChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
        ((ChunkTickServerLevel)(ServerLevel)chunk.getLevel()).moonrise$removeChunkForPlayerTicking(chunk); // Moonrise - chunk tick iteration
    }

    public static void onChunkEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().addEntityTickingChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
    }

    public static void onChunkNotEntityTicking(final LevelChunk chunk, final ChunkHolder holder) {
        chunk.getLevel().getCurrentWorldData().removeEntityTickingChunk(chunk.moonrise$getChunkAndHolder()); // Folia - region threading
    }

    public static ChunkHolder getUnloadingChunkHolder(final ServerLevel level, final int chunkX, final int chunkZ) {
        return null;
    }

    public static int getSendViewDistance(final ServerPlayer player) {
        return RegionizedPlayerChunkLoader.getAPISendViewDistance(player);
    }

    public static int getViewDistance(final ServerPlayer player) {
        return RegionizedPlayerChunkLoader.getAPIViewDistance(player);
    }

    public static int getTickViewDistance(final ServerPlayer player) {
        return RegionizedPlayerChunkLoader.getAPITickViewDistance(player);
    }

    public static void addPlayerToDistanceMaps(final ServerLevel world, final ServerPlayer player) {
        ((ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().addPlayer(player);
    }

    public static void removePlayerFromDistanceMaps(final ServerLevel world, final ServerPlayer player) {
        ((ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().removePlayer(player);
    }

    public static void updateMaps(final ServerLevel world, final ServerPlayer player) {
        ((ChunkSystemServerLevel)world).moonrise$getPlayerChunkLoader().updatePlayer(player);
    }

    private ChunkSystem() {}
}
