package net.minecraft.server.level;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap.Entry;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportType;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.util.CsvOutput;
import net.minecraft.util.Mth;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ReputationEventHandler;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.village.ReputationEventType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventDispatcher;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheck;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.portal.PortalForcer;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTicks;
import org.slf4j.Logger;
import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.generator.CustomWorldChunkManager;
import org.bukkit.craftbukkit.util.WorldUUID;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.TimeSkipEvent;
// CraftBukkit end

public class ServerLevel extends Level implements ServerEntityGetter, WorldGenLevel, ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel, ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevelReader, ca.spottedleaf.moonrise.patches.chunk_tick_iteration.ChunkTickServerLevel {  // Paper - rewrite chunk system // Paper - chunk tick iteration

    public static final BlockPos END_SPAWN_POINT = new BlockPos(100, 50, 0);
    public static final IntProvider RAIN_DELAY = UniformInt.of(12000, 180000);
    public static final IntProvider RAIN_DURATION = UniformInt.of(12000, 24000);
    private static final IntProvider THUNDER_DELAY = UniformInt.of(12000, 180000);
    public static final IntProvider THUNDER_DURATION = UniformInt.of(3600, 15600);
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int EMPTY_TIME_NO_TICK = 300;
    private static final int MAX_SCHEDULED_TICKS_PER_TICK = 65536;
    final List<ServerPlayer> players = new java.util.concurrent.CopyOnWriteArrayList<>(); // Folia - region threading
    public final ServerChunkCache chunkSource;
    private final MinecraftServer server;
    public final PrimaryLevelData serverLevelData; // CraftBukkit - type
    private int lastSpawnChunkRadius;
    //final EntityTickList entityTickList = new EntityTickList(); // Folia - region threading
    // Paper - rewrite chunk system
    private final GameEventDispatcher gameEventDispatcher;
    public boolean noSave;
    private final SleepStatus sleepStatus;
    private int emptyTime;
    private final PortalForcer portalForcer;
    //private final LevelTicks<Block> blockTicks = new LevelTicks<>(this::isPositionTickingWithEntitiesLoaded); // Folia - region threading
    //private final LevelTicks<Fluid> fluidTicks = new LevelTicks<>(this::isPositionTickingWithEntitiesLoaded); // Folia - region threading
    //private final PathTypeCache pathTypesByPosCache = new PathTypeCache(); // Folia - region threading
    //final Set<Mob> navigatingMobs = new ObjectOpenHashSet(); // Folia - region threading
    volatile boolean isUpdatingNavigations;
    protected final Raids raids;
    //private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet(); // Folia - region threading
    //private final List<BlockEventData> blockEventsToReschedule = new ArrayList(64); // Folia - region threading
    //private boolean handlingTick; // Folia - region threading
    private final List<CustomSpawner> customSpawners;
    @Nullable
    private EndDragonFight dragonFight;
    final Int2ObjectMap<EnderDragonPart> dragonParts = new Int2ObjectOpenHashMap();
    private final StructureManager structureManager;
    private final StructureCheck structureCheck;
    public final boolean tickTime; // Folia - region threading
    private final RandomSequences randomSequences;

    // CraftBukkit start
    public final LevelStorageSource.LevelStorageAccess convertable;
    public final UUID uuid;
    // Folia - region threading - move to regionised world data

    public LevelChunk getChunkIfLoaded(int x, int z) {
        return this.chunkSource.getChunkAtIfLoadedImmediately(x, z); // Paper - Use getChunkIfLoadedImmediately
    }

    @Override
    public ResourceKey<LevelStem> getTypeKey() {
        return this.convertable.dimensionType;
    }

    // Paper start
    public final boolean areChunksLoadedForMove(AABB axisalignedbb) {
        // copied code from collision methods, so that we can guarantee that they wont load chunks (we don't override
        // ICollisionAccess methods for VoxelShapes)
        // be more strict too, add a block (dumb plugins in move events?)
        int minBlockX = Mth.floor(axisalignedbb.minX - 1.0E-7D) - 3;
        int maxBlockX = Mth.floor(axisalignedbb.maxX + 1.0E-7D) + 3;

        int minBlockZ = Mth.floor(axisalignedbb.minZ - 1.0E-7D) - 3;
        int maxBlockZ = Mth.floor(axisalignedbb.maxZ + 1.0E-7D) + 3;

        int minChunkX = minBlockX >> 4;
        int maxChunkX = maxBlockX >> 4;

        int minChunkZ = minBlockZ >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        // Folia start - region threading
        // don't let players move into regions not owned
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this, minChunkX, minChunkZ, maxChunkX, maxChunkZ)) {
            return false;
        }
        // Folia end - region threading

        ServerChunkCache chunkProvider = this.getChunkSource();

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                if (chunkProvider.getChunkAtIfLoadedImmediately(cx, cz) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    public final void loadChunksForMoveAsync(AABB axisalignedbb, ca.spottedleaf.concurrentutil.util.Priority priority,
                                             java.util.function.Consumer<List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        // Paper - rewrite chunk system
        int minBlockX = Mth.floor(axisalignedbb.minX - 1.0E-7D) - 3;
        int minBlockZ = Mth.floor(axisalignedbb.minZ - 1.0E-7D) - 3;

        int maxBlockX = Mth.floor(axisalignedbb.maxX + 1.0E-7D) + 3;
        int maxBlockZ = Mth.floor(axisalignedbb.maxZ + 1.0E-7D) + 3;

        int minChunkX = minBlockX >> 4;
        int minChunkZ = minBlockZ >> 4;

        int maxChunkX = maxBlockX >> 4;
        int maxChunkZ = maxBlockZ >> 4;

        this.loadChunks(minChunkX, minChunkZ, maxChunkX, maxChunkZ, priority, onLoad);
    }

    public final void loadChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ,
                                 ca.spottedleaf.concurrentutil.util.Priority priority,
                                 java.util.function.Consumer<List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        this.moonrise$loadChunksAsync(minChunkX, maxChunkX, minChunkZ, maxChunkZ, priority, onLoad); // Paper - rewrite chunk system
    }
    // Paper end

    // Paper start - optimise getPlayerByUUID
    @Nullable
    @Override
    public Player getPlayerByUUID(UUID uuid) {
        final Player player = this.getServer().getPlayerList().getPlayer(uuid);
        return player != null && player.level() == this ? player : null;
    }
    // Paper end - optimise getPlayerByUUID
    // Paper start - rewrite chunk system
    private final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.ViewDistanceHolder viewDistanceHolder = new ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.ViewDistanceHolder();
    private final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader chunkLoader = new ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader((ServerLevel)(Object)this);
    private final ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController entityDataController;
    private final ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.PoiDataController poiDataController;
    private final ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.ChunkDataController chunkDataController;
    private final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler;
    private long lastMidTickFailure;
    private long tickedBlocksOrFluids;
    // Folia - region threading - move to regionized data

    @Override
    public final LevelChunk moonrise$getFullChunkIfLoaded(final int chunkX, final int chunkZ) {
        return this.chunkSource.getChunkNow(chunkX, chunkZ);
    }

    @Override
    public final ChunkAccess moonrise$getAnyChunkIfLoaded(final int chunkX, final int chunkZ) {
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(chunkX, chunkZ));
        if (newChunkHolder == null) {
            return null;
        }
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder.ChunkCompletion lastCompletion = newChunkHolder.getLastChunkCompletion();
        return lastCompletion == null ? null : lastCompletion.chunk();
    }

    @Override
    public final ChunkAccess moonrise$getSpecificChunkIfLoaded(final int chunkX, final int chunkZ, final net.minecraft.world.level.chunk.status.ChunkStatus leastStatus) {
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder newChunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkX, chunkZ);
        if (newChunkHolder == null) {
            return null;
        }
        return newChunkHolder.getChunkIfPresentUnchecked(leastStatus);
    }

    @Override
    public final void moonrise$midTickTasks() {
        ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this.server).moonrise$executeMidTickTasks();
    }

    @Override
    public final ChunkAccess moonrise$syncLoadNonFull(final int chunkX, final int chunkZ, final net.minecraft.world.level.chunk.status.ChunkStatus status) {
        return this.moonrise$getChunkTaskScheduler().syncLoadNonFull(chunkX, chunkZ, status);
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler moonrise$getChunkTaskScheduler() {
        return this.chunkTaskScheduler;
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController  moonrise$getChunkDataController() {
        return this.chunkDataController;
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController moonrise$getPoiChunkDataController() {
        return this.poiDataController;
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionDataController moonrise$getEntityChunkDataController() {
        return this.entityDataController;
    }

    @Override
    public final int moonrise$getRegionChunkShift() {
        return this.regioniser.sectionChunkShift; // Folia - region threading
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader moonrise$getPlayerChunkLoader() {
        return this.chunkLoader;
    }

    @Override
    public final void moonrise$loadChunksAsync(final BlockPos pos, final int radiusBlocks,
                                               final ca.spottedleaf.concurrentutil.util.Priority priority,
                                               final java.util.function.Consumer<java.util.List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        this.moonrise$loadChunksAsync(
            (pos.getX() - radiusBlocks) >> 4,
            (pos.getX() + radiusBlocks) >> 4,
            (pos.getZ() - radiusBlocks) >> 4,
            (pos.getZ() + radiusBlocks) >> 4,
            priority, onLoad
        );
    }

    @Override
    public final void moonrise$loadChunksAsync(final BlockPos pos, final int radiusBlocks,
                                               final net.minecraft.world.level.chunk.status.ChunkStatus chunkStatus, final ca.spottedleaf.concurrentutil.util.Priority priority,
                                               final java.util.function.Consumer<java.util.List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        this.moonrise$loadChunksAsync(
            (pos.getX() - radiusBlocks) >> 4,
            (pos.getX() + radiusBlocks) >> 4,
            (pos.getZ() - radiusBlocks) >> 4,
            (pos.getZ() + radiusBlocks) >> 4,
            chunkStatus, priority, onLoad
        );
    }

    @Override
    public final void moonrise$loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                               final ca.spottedleaf.concurrentutil.util.Priority priority,
                                               final java.util.function.Consumer<java.util.List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        this.moonrise$loadChunksAsync(minChunkX, maxChunkX, minChunkZ, maxChunkZ, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, priority, onLoad);
    }

    @Override
    public final void moonrise$loadChunksAsync(final int minChunkX, final int maxChunkX, final int minChunkZ, final int maxChunkZ,
                                               final net.minecraft.world.level.chunk.status.ChunkStatus chunkStatus, final ca.spottedleaf.concurrentutil.util.Priority priority,
                                               final java.util.function.Consumer<java.util.List<net.minecraft.world.level.chunk.ChunkAccess>> onLoad) {
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler chunkTaskScheduler = this.moonrise$getChunkTaskScheduler();
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager chunkHolderManager = chunkTaskScheduler.chunkHolderManager;

        final int requiredChunks = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        final java.util.concurrent.atomic.AtomicInteger loadedChunks = new java.util.concurrent.atomic.AtomicInteger();
        final Long holderIdentifier = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.getNextChunkLoadId();
        final int ticketLevel = ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.getTicketLevel(chunkStatus);

        final List<ChunkAccess> ret = new ArrayList<>(requiredChunks);

        final java.util.function.Consumer<net.minecraft.world.level.chunk.ChunkAccess> consumer = (final ChunkAccess chunk) -> {
            if (chunk != null) {
                synchronized (ret) {
                    ret.add(chunk);
                }
                chunkHolderManager.addTicketAtLevel(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_LOAD, chunk.getPos(), ticketLevel, holderIdentifier);
            }
            if (loadedChunks.incrementAndGet() == requiredChunks) {
                try {
                    onLoad.accept(java.util.Collections.unmodifiableList(ret));
                } finally {
                    for (int i = 0, len = ret.size(); i < len; ++i) {
                        final ChunkPos chunkPos = ret.get(i).getPos();

                        chunkHolderManager.removeTicketAtLevel(ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.CHUNK_LOAD, chunkPos, ticketLevel, holderIdentifier);
                    }
                }
            }
        };

        for (int cx = minChunkX; cx <= maxChunkX; ++cx) {
            for (int cz = minChunkZ; cz <= maxChunkZ; ++cz) {
                chunkTaskScheduler.scheduleChunkLoad(cx, cz, chunkStatus, true, priority, consumer);
            }
        }
    }

    @Override
    public final ca.spottedleaf.moonrise.patches.chunk_system.player.RegionizedPlayerChunkLoader.ViewDistanceHolder moonrise$getViewDistanceHolder() {
        return this.viewDistanceHolder;
    }

    @Override
    public final long moonrise$getLastMidTickFailure() {
        return this.lastMidTickFailure;
    }

    @Override
    public final void moonrise$setLastMidTickFailure(final long time) {
        this.lastMidTickFailure = time;
    }

    @Override
    public final ca.spottedleaf.moonrise.common.misc.NearbyPlayers moonrise$getNearbyPlayers() {
        return this.getCurrentWorldData().getNearbyPlayers(); // Folia - region threading
    }

    @Override
    public final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.server.level.ServerChunkCache.ChunkAndHolder> moonrise$getLoadedChunks() {
        return this.getCurrentWorldData().getChunks(); // Folia - region threading
    }

    @Override
    public final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.server.level.ServerChunkCache.ChunkAndHolder> moonrise$getTickingChunks() {
        return this.getCurrentWorldData().getTickingChunks(); // Folia - region threading
    }

    @Override
    public final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.server.level.ServerChunkCache.ChunkAndHolder> moonrise$getEntityTickingChunks() {
        return this.getCurrentWorldData().getEntityTickingChunks(); // Folia - region threading
    }

    @Override
    public final boolean moonrise$areChunksLoaded(final int fromX, final int fromZ, final int toX, final int toZ) {
        final ServerChunkCache chunkSource = this.chunkSource;

        for (int currZ = fromZ; currZ <= toZ; ++currZ) {
            for (int currX = fromX; currX <= toX; ++currX) {
                if (!chunkSource.hasChunk(currX, currZ)) {
                    return false;
                }
            }
        }

        return true;
    }
    // Paper end - rewrite chunk system
    // Paper start - chunk tick iteration
    private static final ServerChunkCache.ChunkAndHolder[] EMPTY_PLAYER_CHUNK_HOLDERS = new ServerChunkCache.ChunkAndHolder[0];
    // Folia - region threading

    @Override
    public final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.server.level.ServerChunkCache.ChunkAndHolder> moonrise$getPlayerTickingChunks() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }

    @Override
    public final void moonrise$markChunkForPlayerTicking(final LevelChunk chunk) {
        // Folia - region threading
    }

    @Override
    public final void moonrise$removeChunkForPlayerTicking(final LevelChunk chunk) {
        // Folia - region threading
    }

    @Override
    public final void moonrise$addPlayerTickingRequest(final int chunkX, final int chunkZ) {
        // Folia - region threading
    }

    @Override
    public final void moonrise$removePlayerTickingRequest(final int chunkX, final int chunkZ) {
        // Folia - region threading
    }
    // Paper end - chunk tick iteration
    // Paper start - lag compensation
    private long lagCompensationTick = net.minecraft.server.MinecraftServer.SERVER_INIT;

    public long getLagCompensationTick() {
        return this.getCurrentWorldData().getLagCompensationTick(); // Folia - region threading
    }

    public void updateLagCompensationTick() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }
    // Paper end - lag compensation
    // Folia start - region threading
    public final io.papermc.paper.threadedregions.TickRegions tickRegions = new io.papermc.paper.threadedregions.TickRegions();
    public final io.papermc.paper.threadedregions.ThreadedRegionizer<io.papermc.paper.threadedregions.TickRegions.TickRegionData, io.papermc.paper.threadedregions.TickRegions.TickRegionSectionData> regioniser;
    {
        this.regioniser = new io.papermc.paper.threadedregions.ThreadedRegionizer<>(
            (int)Math.max(1L, (8L * 16L * 16L) / (1L << (2 * (io.papermc.paper.threadedregions.TickRegions.getRegionChunkShift())))),
            (1.0 / 6.0),
            Math.max(1, 8 / (1 << io.papermc.paper.threadedregions.TickRegions.getRegionChunkShift())),
            1,
            io.papermc.paper.threadedregions.TickRegions.getRegionChunkShift(),
            this,
            this.tickRegions
        );
    }
    public final io.papermc.paper.threadedregions.RegionizedTaskQueue.WorldRegionTaskData taskQueueRegionData = new io.papermc.paper.threadedregions.RegionizedTaskQueue.WorldRegionTaskData(this);
    public static final int WORLD_INIT_NOT_CHECKED = 0;
    public static final int WORLD_INIT_CHECKING = 1;
    public static final int WORLD_INIT_CHECKED = 2;
    public final java.util.concurrent.atomic.AtomicInteger checkInitialised = new java.util.concurrent.atomic.AtomicInteger(WORLD_INIT_NOT_CHECKED);
    public ChunkPos randomSpawnSelection;

    public static final record PendingTeleport(Entity.EntityTreeNode rootVehicle, Vec3 to) {}
    private final it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<PendingTeleport> pendingTeleports = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();

    public void pushPendingTeleport(final PendingTeleport teleport) {
        synchronized (this.pendingTeleports) {
            this.pendingTeleports.add(teleport);
        }
    }

    public boolean removePendingTeleport(final PendingTeleport teleport) {
        synchronized (this.pendingTeleports) {
            return this.pendingTeleports.remove(teleport);
        }
    }

    public List<PendingTeleport> removeAllRegionTeleports() {
        final List<PendingTeleport> ret = new ArrayList<>();

        synchronized (this.pendingTeleports) {
            for (final Iterator<PendingTeleport> iterator = this.pendingTeleports.iterator(); iterator.hasNext(); ) {
                final PendingTeleport pendingTeleport = iterator.next();
                if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this, pendingTeleport.to())) {
                    ret.add(pendingTeleport);
                    iterator.remove();
                }
            }
        }

        return ret;
    }
    // Folia end - region threading

    // Add env and gen to constructor, IWorldDataServer -> WorldDataServer
    public ServerLevel(MinecraftServer minecraftserver, Executor executor, LevelStorageSource.LevelStorageAccess convertable_conversionsession, PrimaryLevelData iworlddataserver, ResourceKey<Level> resourcekey, LevelStem worlddimension, ChunkProgressListener worldloadlistener, boolean flag, long i, List<CustomSpawner> list, boolean flag1, @Nullable RandomSequences randomsequences, org.bukkit.World.Environment env, org.bukkit.generator.ChunkGenerator gen, org.bukkit.generator.BiomeProvider biomeProvider) {
        super(iworlddataserver, resourcekey, minecraftserver.registryAccess(), worlddimension.type(), false, flag, i, minecraftserver.getMaxChainedNeighborUpdates(), gen, biomeProvider, env, spigotConfig -> minecraftserver.paperConfigurations.createWorldConfig(io.papermc.paper.configuration.PaperConfigurations.createWorldContextMap(convertable_conversionsession.levelDirectory.path(), iworlddataserver.getLevelName(), resourcekey.location(), spigotConfig, minecraftserver.registryAccess(), iworlddataserver.getGameRules())), executor); // Paper - create paper world configs; Async-Anti-Xray: Pass executor
        this.pvpMode = minecraftserver.isPvpAllowed();
        this.convertable = convertable_conversionsession;
        this.uuid = WorldUUID.getUUID(convertable_conversionsession.levelDirectory.path().toFile());
        // CraftBukkit end
        this.tickTime = flag1;
        this.server = minecraftserver;
        this.customSpawners = list;
        this.serverLevelData = iworlddataserver;
        ChunkGenerator chunkgenerator = worlddimension.generator();
        // CraftBukkit start
        this.serverLevelData.setWorld(this);

        if (biomeProvider != null) {
            BiomeSource worldChunkManager = new CustomWorldChunkManager(this.getWorld(), biomeProvider, this.server.registryAccess().lookupOrThrow(Registries.BIOME), chunkgenerator.getBiomeSource()); // Paper - add vanillaBiomeProvider
            if (chunkgenerator instanceof NoiseBasedChunkGenerator cga) {
                chunkgenerator = new NoiseBasedChunkGenerator(worldChunkManager, cga.settings);
            } else if (chunkgenerator instanceof FlatLevelSource cpf) {
                chunkgenerator = new FlatLevelSource(cpf.settings(), worldChunkManager);
            }
        }

        if (gen != null) {
            chunkgenerator = new org.bukkit.craftbukkit.generator.CustomChunkGenerator(this, chunkgenerator, gen);
        }
        // CraftBukkit end
        boolean flag2 = minecraftserver.forceSynchronousWrites();
        DataFixer datafixer = minecraftserver.getFixerUpper();
        EntityPersistentStorage<Entity> entitypersistentstorage = new EntityStorage(new SimpleRegionStorage(new RegionStorageInfo(convertable_conversionsession.getLevelId(), resourcekey, "entities"), convertable_conversionsession.getDimensionPath(resourcekey).resolve("entities"), datafixer, flag2, DataFixTypes.ENTITY_CHUNK), this, minecraftserver);

        // Paper - rewrite chunk system
        StructureTemplateManager structuretemplatemanager = minecraftserver.getStructureManager();
        int j = this.spigotConfig.viewDistance; // Spigot
        int k = this.spigotConfig.simulationDistance; // Spigot
        // Paper - rewrite chunk system

        this.chunkSource = new ServerChunkCache(this, convertable_conversionsession, datafixer, structuretemplatemanager, executor, chunkgenerator, j, k, flag2, worldloadlistener, null, () -> { // Paper - rewrite chunk system
            return minecraftserver.overworld().getDataStorage();
        });
        this.chunkSource.getGeneratorState().ensureStructuresGenerated();
        this.portalForcer = new PortalForcer(this);
        //this.updateSkyBrightness(); // Folia - region threading - delay until first tick
        this.prepareWeather();
        this.getWorldBorder().setAbsoluteMaxSize(minecraftserver.getAbsoluteMaxWorldSize());
        this.raids = (Raids) this.getDataStorage().computeIfAbsent(Raids.factory(this), Raids.getFileId(this.dimensionTypeRegistration()));
        if (!minecraftserver.isSingleplayer()) {
            iworlddataserver.setGameType(minecraftserver.getDefaultGameType());
        }

        long l = minecraftserver.getWorldData().worldGenOptions().seed();

        this.structureCheck = new StructureCheck(this.chunkSource.chunkScanner(), this.registryAccess(), minecraftserver.getStructureManager(), this.getTypeKey(), chunkgenerator, this.chunkSource.randomState(), this, chunkgenerator.getBiomeSource(), l, datafixer); // Paper - Fix missing CB diff
        this.structureManager = new StructureManager(this, this.serverLevelData.worldGenOptions(), this.structureCheck); // CraftBukkit
        if ((this.dimension() == Level.END && this.dimensionTypeRegistration().is(BuiltinDimensionTypes.END)) || env == org.bukkit.World.Environment.THE_END) { // CraftBukkit - Allow to create EnderDragonBattle in default and custom END
            this.dragonFight = new EndDragonFight(this, this.serverLevelData.worldGenOptions().seed(), this.serverLevelData.endDragonFightData()); // CraftBukkit
        } else {
            this.dragonFight = null;
        }

        this.sleepStatus = new SleepStatus();
        this.gameEventDispatcher = new GameEventDispatcher(this);
        this.randomSequences = (RandomSequences) Objects.requireNonNullElseGet(randomsequences, () -> {
            return (RandomSequences) this.getDataStorage().computeIfAbsent(RandomSequences.factory(l), "random_sequences");
        });
        // Paper start - rewrite chunk system
        this.moonrise$setEntityLookup(new ca.spottedleaf.moonrise.patches.chunk_system.level.entity.server.ServerEntityLookup((ServerLevel)(Object)this, ((ServerLevel)(Object)this).new EntityCallbacks()));
        this.chunkTaskScheduler = new ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler((ServerLevel)(Object)this);
        this.entityDataController = new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController(
            new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.EntityDataController.EntityRegionFileStorage(
                new RegionStorageInfo(convertable_conversionsession.getLevelId(), resourcekey, "entities"),
                convertable_conversionsession.getDimensionPath(resourcekey).resolve("entities"),
                minecraftserver.forceSynchronousWrites()
            ),
            this.chunkTaskScheduler
        );
        this.poiDataController = new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.PoiDataController((ServerLevel)(Object)this, this.chunkTaskScheduler);
        this.chunkDataController = new ca.spottedleaf.moonrise.patches.chunk_system.io.datacontroller.ChunkDataController((ServerLevel)(Object)this, this.chunkTaskScheduler);
        // Paper end - rewrite chunk system
        this.getCraftServer().addWorld(this.getWorld()); // CraftBukkit
        this.updateTickData(); // Folia - region threading - make sure it is initialised before ticked
    }

    // Folia start - region threading
    public void updateTickData() {
        this.tickData = new io.papermc.paper.threadedregions.RegionizedServer.WorldLevelData(this, this.serverLevelData.getGameTime(), this.serverLevelData.getDayTime());
    }
    // Folia end - region threading

    // Paper start
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ) != null;
    }
    // Paper end

    /** @deprecated */
    @Deprecated
    @VisibleForTesting
    public void setDragonFight(@Nullable EndDragonFight enderDragonFight) {
        this.dragonFight = enderDragonFight;
    }

    public void setWeatherParameters(int clearDuration, int rainDuration, boolean raining, boolean thundering) {
        this.serverLevelData.setClearWeatherTime(clearDuration);
        this.serverLevelData.setRainTime(rainDuration);
        this.serverLevelData.setThunderTime(rainDuration);
        this.serverLevelData.setRaining(raining, org.bukkit.event.weather.WeatherChangeEvent.Cause.COMMAND); // Paper - Add cause to Weather/ThunderChangeEvents
        this.serverLevelData.setThundering(thundering, org.bukkit.event.weather.ThunderChangeEvent.Cause.COMMAND); // Paper - Add cause to Weather/ThunderChangeEvents
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int biomeX, int biomeY, int biomeZ) {
        return this.getChunkSource().getGenerator().getBiomeSource().getNoiseBiome(biomeX, biomeY, biomeZ, this.getChunkSource().randomState().sampler());
    }

    @Override // Folia - region threading
    public StructureManager structureManager() {
        return this.structureManager;
    }

    public void tick(BooleanSupplier shouldKeepTicking, io.papermc.paper.threadedregions.TickRegions.TickRegionData region) { // Folia - regionised ticking
        final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.getCurrentWorldData(); // Folia - regionised ticking
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        ProfilerFiller gameprofilerfiller = Profiler.get();

        regionizedWorldData.setHandlingTick(true); // Folia - regionised ticking
        TickRateManager tickratemanager = this.tickRateManager();
        boolean flag = tickratemanager.runsNormally();

        if (flag) {
            gameprofilerfiller.push("world border");
            if (region == null) this.getWorldBorder().tick(); // Folia - regionised ticking
            gameprofilerfiller.popPush("weather");
            if (region == null) this.advanceWeatherCycle(); // Folia - regionised ticking
            gameprofilerfiller.pop();
        }

        //int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE); // Folia - region threading - move into tickSleep
        long j;

        if (region == null) this.tickSleep(); // Folia - region threading

        if (region == null) this.updateSkyBrightness(); // Folia - region threading
        if (flag) {
            this.tickTime();
        }

        gameprofilerfiller.push("tickPending");
        if (!this.isDebug() && flag) {
            j = regionizedWorldData.getRedstoneGameTime(); // Folia - region threading
            gameprofilerfiller.push("blockTicks");
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BLOCK_TICK); try { // Folia - profiler
            regionizedWorldData.getBlockLevelTicks().tick(j, paperConfig().environment.maxBlockTicks, this::tickBlock); // Paper - configurable max block ticks // Folia - region ticking
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BLOCK_TICK); } // Folia - profiler
            gameprofilerfiller.popPush("fluidTicks");
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.FLUID_TICK); try { // Folia - profiler
            regionizedWorldData.getFluidLevelTicks().tick(j, paperConfig().environment.maxFluidTicks, this::tickFluid); // Paper - configurable max fluid ticks // Folia - region ticking
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.FLUID_TICK); } // Folia - profiler
            gameprofilerfiller.pop();
        }

        gameprofilerfiller.popPush("raid");
        if (flag) {
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.RAIDS_TICK); try { // Folia - profiler
            this.raids.tick();
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.RAIDS_TICK); } // Folia - profiler
        }

        gameprofilerfiller.popPush("chunkSource");
        profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_PROVIDER_TICK); try { // Folia - profiler
        this.getChunkSource().tick(shouldKeepTicking, true);
        } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.CHUNK_PROVIDER_TICK); } // Folia - profiler
        gameprofilerfiller.popPush("blockEvents");
        if (flag) {
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BLOCK_EVENT_TICK); try { // Folia - profiler
            this.runBlockEvents();
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.BLOCK_EVENT_TICK); } // Folia - profiler
        }

        regionizedWorldData.setHandlingTick(false); // Folia - regionised ticking
        gameprofilerfiller.pop();
        boolean flag1 = true || !this.players.isEmpty() || !this.getForcedChunks().isEmpty(); // CraftBukkit - this prevents entity cleanup, other issues on servers with no players // Paper - restore this // Folia - unrestore this, we always need to tick empty worlds

        if (flag1) {
            this.resetEmptyTime();
        }

        if (flag1 || this.emptyTime++ < 300) {
            gameprofilerfiller.push("entities");
            if (this.dragonFight != null && flag) {
                profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.DRAGON_FIGHT_TICK); try { // Folia - profiler
                if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this, this.dragonFight.origin)) { // Folia - region threading
                gameprofilerfiller.push("dragonFight");
                this.dragonFight.tick();
                gameprofilerfiller.pop();
                } else { // Folia start - region threading
                    // try to load dragon fight
                    ChunkPos fightCenter = new ChunkPos(this.dragonFight.origin);
                    this.chunkSource.addTicketAtLevel(
                        TicketType.UNKNOWN, fightCenter, ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.MAX_TICKET_LEVEL,
                        fightCenter
                    );
                } // Folia end - region threading
                } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.DRAGON_FIGHT_TICK); } // Folia - profiler
            }

            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ACTIVATE_ENTITIES); try { // Folia - profiler
            org.spigotmc.ActivationRange.activateEntities(this); // Spigot
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ACTIVATE_ENTITIES); } // Folia - profiler
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TICK); try { // Folia - profiler
            regionizedWorldData.forEachTickingEntity((entity) -> { // Folia - regionised ticking
                if (!entity.isRemoved()) {
                    if (!tickratemanager.isEntityFrozen(entity)) {
                        gameprofilerfiller.push("checkDespawn");
                        entity.checkDespawn();
                        if (entity.isRemoved()) return; // Folia - region threading - if we despawned, DON'T TICK IT!
                        gameprofilerfiller.pop();
                        if (true) { // Paper - rewrite chunk system
                            Entity entity1 = entity.getVehicle();

                            if (entity1 != null) {
                                if (!entity1.isRemoved() && entity1.hasPassenger(entity)) {
                                    return;
                                }

                                entity.stopRiding();
                            }

                            gameprofilerfiller.push("tick");
                            this.guardEntityTick(this::tickNonPassenger, entity);
                            gameprofilerfiller.pop();
                        }
                    }
                }
            });
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.ENTITY_TICK); } // Folia - profiler
            gameprofilerfiller.pop();
            profiler.startTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY); try { // Folia - profiler
            this.tickBlockEntities();
            } finally { profiler.stopTimer(ca.spottedleaf.leafprofiler.LProfilerRegistry.TILE_ENTITY); } // Folia - profiler
        }

        gameprofilerfiller.push("entityManagement");
        // Paper - rewrite chunk system
        gameprofilerfiller.pop();
    }

    // Folia start - region threading
    public void tickSleep() {
        int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE); long j; // Folia moved from tick loop
        if (this.sleepStatus.areEnoughSleeping(i) && this.sleepStatus.areEnoughDeepSleeping(i, this.players)) {
            // CraftBukkit start
            j = this.levelData.getDayTime() + 24000L;
            TimeSkipEvent event = new TimeSkipEvent(this.getWorld(), TimeSkipEvent.SkipReason.NIGHT_SKIP, (j - j % 24000L) - this.getDayTime());
            if (this.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {
                this.getCraftServer().getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    this.setDayTime(this.getDayTime() + event.getSkipAmount());
                }
            }

            if (!event.isCancelled()) {
                this.wakeUpAllPlayers();
            }
            // CraftBukkit end
            if (this.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE) && this.isRaining()) {
                this.resetWeatherCycle();
            }
        }
    }
    // Folia end - region threading

    @Override
    public boolean shouldTickBlocksAt(long chunkPos) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder holder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkPos);
        return holder != null && holder.isTickingReady();
        // Paper end - rewrite chunk system
    }

    protected void tickTime() {
        if (this.tickTime) {
            io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.getCurrentWorldData(); // Folia - region threading
            long i = regionizedWorldData.getRedstoneGameTime() + 1L; // Folia - region threading

            regionizedWorldData.setRedstoneGameTime(i); // Folia - region threading
            Profiler.get().push("scheduledFunctions");
            if (false) this.serverLevelData.getScheduledEvents().tick(this.server, i); // Folia - region threading - TODO any way to bring this in?
            Profiler.get().pop();
            if (false && this.serverLevelData.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)) {  // Folia - region threading
                this.setDayTime(this.levelData.getDayTime() + 1L);
            }

        }
    }

    public void setDayTime(long timeOfDay) {
        this.serverLevelData.setDayTime(timeOfDay);
    }

    public void tickCustomSpawners(boolean spawnMonsters, boolean spawnAnimals) {
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
        Iterator iterator = this.customSpawners.iterator();

        while (iterator.hasNext()) {
            CustomSpawner mobspawner = (CustomSpawner) iterator.next();

            final int customSpawnerTimer = profiler.getOrCreateTimerAndStart(() -> "Misc Spawner: ".concat(io.papermc.paper.util.ObfHelper.INSTANCE.deobfClassName(mobspawner.getClass().getName()))); try { // Folia - profiler
            mobspawner.tick(this, spawnMonsters, spawnAnimals);
            } finally { profiler.stopTimer(customSpawnerTimer); } // Folia - profiler
        }

    }

    private void wakeUpAllPlayers() {
        this.sleepStatus.removeAllSleepers();
        (this.players.stream().filter(LivingEntity::isSleeping).collect(Collectors.toList())).forEach((entityplayer) -> { // CraftBukkit - decompile error
            // Folia start - region threading
            entityplayer.getBukkitEntity().taskScheduler.schedule((ServerPlayer player) -> {
                if (player.level() != ServerLevel.this || !player.isSleeping()) {
                    return;
                }
                player.stopSleepInBed(false, false);
            }, null, 1L);
            // Folia end - region threading
        });
    }

    // Paper start - optimise random ticking
    private final io.papermc.paper.threadedregions.util.SimpleThreadLocalRandomSource simpleRandom = io.papermc.paper.threadedregions.util.SimpleThreadLocalRandomSource.INSTANCE; // Folia - region threading

    private void optimiseRandomTick(final LevelChunk chunk, final int tickSpeed) {
        final LevelChunkSection[] sections = chunk.getSections();
        final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection((ServerLevel)(Object)this);
        final io.papermc.paper.threadedregions.util.SimpleThreadLocalRandomSource simpleRandom = this.simpleRandom; // Folia - region threading
        final boolean doubleTickFluids = !ca.spottedleaf.moonrise.common.PlatformHooks.get().configFixMC224294();

        final ChunkPos cpos = chunk.getPos();
        final int offsetX = cpos.x << 4;
        final int offsetZ = cpos.z << 4;

        for (int sectionIndex = 0, sectionsLen = sections.length; sectionIndex < sectionsLen; sectionIndex++) {
            final int offsetY = (sectionIndex + minSection) << 4;
            final LevelChunkSection section = sections[sectionIndex];
            final net.minecraft.world.level.chunk.PalettedContainer<net.minecraft.world.level.block.state.BlockState> states = section.states;
            if (!section.isRandomlyTickingBlocks()) {
                continue;
            }

            final ca.spottedleaf.moonrise.common.list.ShortList tickList = ((ca.spottedleaf.moonrise.patches.block_counting.BlockCountingChunkSection)section).moonrise$getTickingBlockList();

            for (int i = 0; i < tickSpeed; ++i) {
                final int tickingBlocks = tickList.size();
                final int index = simpleRandom.nextInt() & ((16 * 16 * 16) - 1);

                if (index >= tickingBlocks) {
                    // most of the time we fall here
                    continue;
                }

                final int location = (int)tickList.getRaw(index) & 0xFFFF;
                final BlockState state = states.get(location);

                // do not use a mutable pos, as some random tick implementations store the input without calling immutable()!
                final BlockPos pos = new BlockPos((location & 15) | offsetX, ((location >>> (4 + 4)) & 15) | offsetY, ((location >>> 4) & 15) | offsetZ);

                state.randomTick((ServerLevel)(Object)this, pos, simpleRandom);
                if (doubleTickFluids) {
                    final FluidState fluidState = state.getFluidState();
                    if (fluidState.isRandomlyTicking()) {
                        fluidState.randomTick((ServerLevel)(Object)this, pos, simpleRandom);
                    }
                }
            }
        }

        return;
    }
    // Paper end - optimise random ticking

    public void tickChunk(LevelChunk chunk, int randomTickSpeed) {
        final io.papermc.paper.threadedregions.util.SimpleThreadLocalRandomSource simpleRandom = this.simpleRandom; // Paper - optimise random ticking // Folia - region threading
        ChunkPos chunkcoordintpair = chunk.getPos();
        boolean flag = this.isRaining();
        int j = chunkcoordintpair.getMinBlockX();
        int k = chunkcoordintpair.getMinBlockZ();
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("thunder");
        if (!this.paperConfig().environment.disableThunder && flag && this.isThundering() && this.spigotConfig.thunderChance > 0 && simpleRandom.nextInt(this.spigotConfig.thunderChance) == 0) { // Spigot // Paper - Option to disable thunder // Paper - optimise random ticking
            BlockPos blockposition = this.findLightningTargetAround(this.getBlockRandomPos(j, 0, k, 15));

            if (this.isRainingAt(blockposition)) {
                DifficultyInstance difficultydamagescaler = this.getCurrentDifficultyAt(blockposition);
                boolean flag1 = this.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) && this.random.nextDouble() < (double) difficultydamagescaler.getEffectiveDifficulty() * this.paperConfig().entities.spawning.skeletonHorseThunderSpawnChance.or(0.01D) && !this.getBlockState(blockposition.below()).is(Blocks.LIGHTNING_ROD); // Paper - Configurable spawn chances for skeleton horses

                if (flag1) {
                    SkeletonHorse entityhorseskeleton = (SkeletonHorse) EntityType.SKELETON_HORSE.create(this, EntitySpawnReason.EVENT);

                    if (entityhorseskeleton != null) {
                        entityhorseskeleton.setTrap(true);
                        entityhorseskeleton.setAge(0);
                        entityhorseskeleton.setPos((double) blockposition.getX(), (double) blockposition.getY(), (double) blockposition.getZ());
                        this.addFreshEntity(entityhorseskeleton, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING); // CraftBukkit
                    }
                }

                LightningBolt entitylightning = (LightningBolt) EntityType.LIGHTNING_BOLT.create(this, EntitySpawnReason.EVENT);

                if (entitylightning != null) {
                    entitylightning.moveTo(Vec3.atBottomCenterOf(blockposition));
                    entitylightning.setVisualOnly(flag1);
                    this.strikeLightning(entitylightning, org.bukkit.event.weather.LightningStrikeEvent.Cause.WEATHER); // CraftBukkit
                }
            }
        }

        gameprofilerfiller.popPush("iceandsnow");

        if (!this.paperConfig().environment.disableIceAndSnow) { // Paper - Option to disable ice and snow
        for (int l = 0; l < randomTickSpeed; ++l) {
            if (simpleRandom.nextInt(48) == 0) { // Paper - optimise random ticking
                this.tickPrecipitation(this.getBlockRandomPos(j, 0, k, 15));
            }
        }
        } // Paper - Option to disable ice and snow

        gameprofilerfiller.popPush("tickBlocks");
        if (randomTickSpeed > 0) {
            this.optimiseRandomTick(chunk, randomTickSpeed); // Paper - optimise random ticking
        }

        gameprofilerfiller.pop();
    }

    @VisibleForTesting
    public void tickPrecipitation(BlockPos pos) {
        BlockPos blockposition1 = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        BlockPos blockposition2 = blockposition1.below();
        Biome biomebase = (Biome) this.getBiome(blockposition1).value();

        if (biomebase.shouldFreeze(this, blockposition2)) {
            org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition2, Blocks.ICE.defaultBlockState(), null); // CraftBukkit
        }

        if (this.isRaining()) {
            int i = this.getGameRules().getInt(GameRules.RULE_SNOW_ACCUMULATION_HEIGHT);

            if (i > 0 && biomebase.shouldSnow(this, blockposition1)) {
                BlockState iblockdata = this.getBlockState(blockposition1);

                if (iblockdata.is(Blocks.SNOW)) {
                    int j = (Integer) iblockdata.getValue(SnowLayerBlock.LAYERS);

                    if (j < Math.min(i, 8)) {
                        BlockState iblockdata1 = (BlockState) iblockdata.setValue(SnowLayerBlock.LAYERS, j + 1);

                        Block.pushEntitiesUp(iblockdata, iblockdata1, this, blockposition1);
                        org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition1, iblockdata1, null); // CraftBukkit
                    }
                } else {
                    org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition1, Blocks.SNOW.defaultBlockState(), null); // CraftBukkit
                }
            }

            Biome.Precipitation biomebase_precipitation = biomebase.getPrecipitationAt(blockposition2, this.getSeaLevel());

            if (biomebase_precipitation != Biome.Precipitation.NONE) {
                BlockState iblockdata2 = this.getBlockState(blockposition2);

                iblockdata2.getBlock().handlePrecipitation(iblockdata2, this, blockposition2, biomebase_precipitation);
            }
        }

    }

    public Optional<BlockPos> findLightningRod(BlockPos pos) {
        Optional<BlockPos> optional = this.getPoiManager().findClosest((holder) -> {
            return holder.is(PoiTypes.LIGHTNING_ROD);
        }, (blockposition1) -> {
            return blockposition1.getY() == this.getHeight(Heightmap.Types.WORLD_SURFACE, blockposition1.getX(), blockposition1.getZ()) - 1;
        }, pos, 128, PoiManager.Occupancy.ANY);

        return optional.map((blockposition1) -> {
            return blockposition1.above(1);
        });
    }

    protected BlockPos findLightningTargetAround(BlockPos pos) {
        // Paper start - Add methods to find targets for lightning strikes
        return this.findLightningTargetAround(pos, false);
    }
    public BlockPos findLightningTargetAround(BlockPos pos, boolean returnNullWhenNoTarget) {
        // Paper end - Add methods to find targets for lightning strikes
        BlockPos blockposition1 = this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        Optional<BlockPos> optional = this.findLightningRod(blockposition1);

        if (optional.isPresent()) {
            return (BlockPos) optional.get();
        } else {
            AABB axisalignedbb = AABB.encapsulatingFullBlocks(blockposition1, blockposition1.atY(this.getMaxY() + 1)).inflate(3.0D);
            List<LivingEntity> list = this.getEntitiesOfClass(LivingEntity.class, axisalignedbb, (entityliving) -> {
                return entityliving != null && entityliving.isAlive() && this.canSeeSky(entityliving.blockPosition()) && !entityliving.isSpectator(); // Paper - Fix lightning being able to hit spectators (MC-262422)
            });

            if (!list.isEmpty()) {
                return ((LivingEntity) list.get(this.random.nextInt(list.size()))).blockPosition();
            } else {
                if (returnNullWhenNoTarget) return null; // Paper - Add methods to find targets for lightning strikes
                if (blockposition1.getY() == this.getMinY() - 1) {
                    blockposition1 = blockposition1.above(2);
                }

                return blockposition1;
            }
        }
    }

    public boolean isHandlingTick() {
        return this.getCurrentWorldData().isHandlingTick(); // Folia - regionised ticking
    }

    public boolean canSleepThroughNights() {
        return this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE) <= 100;
    }

    private void announceSleepStatus() {
        if (this.canSleepThroughNights()) {
            if (!this.getServer().isSingleplayer() || this.getServer().isPublished()) {
                int i = this.getGameRules().getInt(GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE);
                MutableComponent ichatmutablecomponent;

                if (this.sleepStatus.areEnoughSleeping(i)) {
                    ichatmutablecomponent = Component.translatable("sleep.skipping_night");
                } else {
                    ichatmutablecomponent = Component.translatable("sleep.players_sleeping", this.sleepStatus.amountSleeping(), this.sleepStatus.sleepersNeeded(i));
                }

                Iterator iterator = this.players.iterator();

                while (iterator.hasNext()) {
                    ServerPlayer entityplayer = (ServerPlayer) iterator.next();

                    entityplayer.displayClientMessage(ichatmutablecomponent, true);
                }

            }
        }
    }

    public void updateSleepingPlayerList() {
        // Folia start - region threading
        if (!io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread()) {
            io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(() -> {
                ServerLevel.this.updateSleepingPlayerList();
            });
            return;
        }
        // Folia end - region threading
        if (!this.players.isEmpty() && this.sleepStatus.update(this.players)) {
            this.announceSleepStatus();
        }

    }

    @Override
    public ServerScoreboard getScoreboard() {
        return this.server.getScoreboard();
    }

    public void advanceWeatherCycle() { // Folia - region threading - public
        boolean flag = this.isRaining();

        if (this.dimensionType().hasSkyLight()) {
            if (this.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE)) {
                int i = this.serverLevelData.getClearWeatherTime();
                int j = this.serverLevelData.getThunderTime();
                int k = this.serverLevelData.getRainTime();
                boolean flag1 = this.levelData.isThundering();
                boolean flag2 = this.levelData.isRaining();

                if (i > 0) {
                    --i;
                    j = flag1 ? 0 : 1;
                    k = flag2 ? 0 : 1;
                    flag1 = false;
                    flag2 = false;
                } else {
                    if (j > 0) {
                        --j;
                        if (j == 0) {
                            flag1 = !flag1;
                        }
                    } else if (flag1) {
                        j = ServerLevel.THUNDER_DURATION.sample(this.random);
                    } else {
                        j = ServerLevel.THUNDER_DELAY.sample(this.random);
                    }

                    if (k > 0) {
                        --k;
                        if (k == 0) {
                            flag2 = !flag2;
                        }
                    } else if (flag2) {
                        k = ServerLevel.RAIN_DURATION.sample(this.random);
                    } else {
                        k = ServerLevel.RAIN_DELAY.sample(this.random);
                    }
                }

                this.serverLevelData.setThunderTime(j);
                this.serverLevelData.setRainTime(k);
                this.serverLevelData.setClearWeatherTime(i);
                this.serverLevelData.setThundering(flag1, org.bukkit.event.weather.ThunderChangeEvent.Cause.NATURAL); // Paper - Add cause to Weather/ThunderChangeEvents
                this.serverLevelData.setRaining(flag2, org.bukkit.event.weather.WeatherChangeEvent.Cause.NATURAL); // Paper - Add cause to Weather/ThunderChangeEvents
            }

            this.oThunderLevel = this.thunderLevel;
            if (this.levelData.isThundering()) {
                this.thunderLevel += 0.01F;
            } else {
                this.thunderLevel -= 0.01F;
            }

            this.thunderLevel = Mth.clamp(this.thunderLevel, 0.0F, 1.0F);
            this.oRainLevel = this.rainLevel;
            if (this.levelData.isRaining()) {
                this.rainLevel += 0.01F;
            } else {
                this.rainLevel -= 0.01F;
            }

            this.rainLevel = Mth.clamp(this.rainLevel, 0.0F, 1.0F);
        }

        /* CraftBukkit start
        if (this.oRainLevel != this.rainLevel) {
            this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.RAIN_LEVEL_CHANGE, this.rainLevel), this.dimension());
        }

        if (this.oThunderLevel != this.thunderLevel) {
            this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.THUNDER_LEVEL_CHANGE, this.thunderLevel), this.dimension());
        }

        if (flag != this.isRaining()) {
            if (flag) {
                this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.STOP_RAINING, 0.0F));
            } else {
                this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.START_RAINING, 0.0F));
            }

            this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.RAIN_LEVEL_CHANGE, this.rainLevel));
            this.server.getPlayerList().broadcastAll(new PacketPlayOutGameStateChange(PacketPlayOutGameStateChange.THUNDER_LEVEL_CHANGE, this.thunderLevel));
        }
        // */
        ServerPlayer[] players = this.players.toArray(new ServerPlayer[0]); // Folia - region threading
        for (ServerPlayer player : players) { // Folia - region threading
            if (player.level() == this) { // Folia - region threading
                player.tickWeather(); // Folia - region threading
            }
        }

        if (flag != this.isRaining()) {
            // Only send weather packets to those affected
            for (ServerPlayer player : players) { // Folia - region threading
                if (player.level() == this) { // Folia - region threading
                    player.setPlayerWeather((!flag ? WeatherType.DOWNFALL : WeatherType.CLEAR), false); // Folia - region threading
                }
            }
        }
        for (ServerPlayer player : players) { // Folia - region threading
            if (player.level() == this) { // Folia - region threading
                player.updateWeather(this.oRainLevel, this.rainLevel, this.oThunderLevel, this.thunderLevel); // Folia - region threading
            }
        }
        // CraftBukkit end

    }

    @VisibleForTesting
    public void resetWeatherCycle() {
        // CraftBukkit start
        this.serverLevelData.setRaining(false, org.bukkit.event.weather.WeatherChangeEvent.Cause.SLEEP); // Paper - Add cause to Weather/ThunderChangeEvents
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.serverLevelData.isRaining()) {
            this.serverLevelData.setRainTime(0);
        }
        // CraftBukkit end
        this.serverLevelData.setThundering(false, org.bukkit.event.weather.ThunderChangeEvent.Cause.SLEEP); // Paper - Add cause to Weather/ThunderChangeEvents
        // CraftBukkit start
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.serverLevelData.isThundering()) {
            this.serverLevelData.setThunderTime(0);
        }
        // CraftBukkit end
    }

    public void resetEmptyTime() {
        this.emptyTime = 0;
    }

    private void tickFluid(BlockPos pos, Fluid fluid) {
        BlockState iblockdata = this.getBlockState(pos);
        FluidState fluid1 = iblockdata.getFluidState();

        if (fluid1.is(fluid)) {
            fluid1.tick(this, pos, iblockdata);
        }
        // Paper start - rewrite chunk system
        if ((++this.tickedBlocksOrFluids & 7L) != 0L) {
            ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this.server).moonrise$executeMidTickTasks();
        }
        // Paper end - rewrite chunk system

    }

    private void tickBlock(BlockPos pos, Block block) {
        BlockState iblockdata = this.getBlockState(pos);

        if (iblockdata.is(block)) {
            iblockdata.tick(this, pos, this.random);
        }
        // Paper start - rewrite chunk system
        if ((++this.tickedBlocksOrFluids & 7L) != 0L) {
            ((ca.spottedleaf.moonrise.patches.chunk_system.server.ChunkSystemMinecraftServer)this.server).moonrise$executeMidTickTasks();
        }
        // Paper end - rewrite chunk system

    }

    // Paper start - log detailed entity tick information
    // TODO replace with varhandle
    // Folia - region threading

    public static List<Entity> getCurrentlyTickingEntities() {
        throw new UnsupportedOperationException(); // Folia - region threading
    }
    // Paper end - log detailed entity tick information

    public void tickNonPassenger(Entity entity) {
        // Paper start - log detailed entity tick information
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread("Cannot tick an entity off-main");
        try {
            // Folia - region threading
            // Paper end - log detailed entity tick information
        // Spigot start
        /*if (!org.spigotmc.ActivationRange.checkIfActive(entity)) { // Paper - comment out EAR 2
            entity.tickCount++;
            entity.inactiveTick();
            return;
        }*/ // Paper - comment out EAR 2
        // Spigot end
        entity.setOldPosAndRot();
        ProfilerFiller gameprofilerfiller = Profiler.get();

        ++entity.tickCount;
        gameprofilerfiller.push(() -> {
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        });
        gameprofilerfiller.incrementCounter("tickNonPassenger");
        final boolean isActive = org.spigotmc.ActivationRange.checkIfActive(entity); // Paper - EAR 2
        // Folia start - profiler
        final int timerId = isActive ? entity.getType().tickTimerId : entity.getType().inactiveTickTimerId;
        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler();
        profiler.startTimer(timerId);
        try {
        // Folia end - profiler
        if (isActive) { // Paper - EAR 2
        entity.tick();
        // Folia start - region threading
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity)) {
            // removed from region while ticking
            return;
        }
        if (entity.handlePortal()) {
            // portalled
            return;
        }
        // Folia end - region threading
        } else { entity.inactiveTick(); } // Paper - EAR 2
        gameprofilerfiller.pop();
        } finally { profiler.stopTimer(timerId); } // Folia - timer
        Iterator iterator = entity.getPassengers().iterator();

        while (iterator.hasNext()) {
            Entity entity1 = (Entity) iterator.next();

            this.tickPassenger(entity, entity1, isActive); // Paper - EAR 2
        }

        // Paper start - log detailed entity tick information
        } finally {
            // Folia - region threading
        }
        // Paper end - log detailed entity tick information
    }

    private void tickPassenger(Entity vehicle, Entity passenger, boolean isActive) { // Paper - EAR 2
        if (!passenger.isRemoved() && passenger.getVehicle() == vehicle) {
            if (passenger instanceof Player || this.getCurrentWorldData().hasEntityTickingEntity(passenger)) { // Folia - region threading
                // Folia start - profiler
                final int timerId = isActive ? passenger.getType().tickTimerId : passenger.getType().inactiveTickTimerId;
                final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler();
                profiler.startTimer(timerId);
                try {
                // Folia end - profiler
                passenger.setOldPosAndRot();
                ++passenger.tickCount;
                ProfilerFiller gameprofilerfiller = Profiler.get();

                gameprofilerfiller.push(() -> {
                    return BuiltInRegistries.ENTITY_TYPE.getKey(passenger.getType()).toString();
                });
                gameprofilerfiller.incrementCounter("tickPassenger");
                // Paper start - EAR 2
                if (isActive) {
                passenger.rideTick();
                // Folia start - region threading
                if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(passenger)) {
                    // removed from region while ticking
                    return;
                }
                if (passenger.handlePortal()) {
                    // portalled
                    return;
                }
                // Folia end - region threading
                } else {
                passenger.setDeltaMovement(Vec3.ZERO);
                passenger.inactiveTick();
                // copied from inside of if (isPassenger()) of passengerTick, but that ifPassenger is unnecessary
                vehicle.positionRider(passenger);
                }
                // Paper end - EAR 2
                gameprofilerfiller.pop();
                Iterator iterator = passenger.getPassengers().iterator();

                while (iterator.hasNext()) {
                    Entity entity2 = (Entity) iterator.next();

                    this.tickPassenger(passenger, entity2, isActive); // Paper - EAR 2
                }

                } finally { profiler.stopTimer(timerId); } // Folia - profiler
            }
        } else {
            passenger.stopRiding();
        }
    }

    @Override
    public boolean mayInteract(Player player, BlockPos pos) {
        return !this.server.isUnderSpawnProtection(this, pos, player) && this.getWorldBorder().isWithinBounds(pos);
    }

    // Paper start - Incremental chunk and player saving
    public void saveIncrementally(boolean doFull) {
        ServerChunkCache chunkproviderserver = this.getChunkSource();

        if (doFull) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(getWorld()));
        }

        if (doFull) {
            this.saveLevelData(true);
        }
        // chunk autosave is already called by the ChunkSystem during unload processing (ChunkMap#processUnloads)
        // Copied from save()
        // CraftBukkit start - moved from MinecraftServer.saveChunks
        if (doFull) { // Paper
            ServerLevel worldserver1 = this;
            this.serverLevelData.setWorldBorder(worldserver1.getWorldBorder().createSettings());
            this.serverLevelData.setCustomBossEvents(this.server.getCustomBossEvents().save(this.registryAccess()));
            this.convertable.saveDataTag(this.server.registryAccess(), this.serverLevelData, this.server.getPlayerList().getSingleplayerData());
        }
        // CraftBukkit end
    }
    // Paper end - Incremental chunk and player saving

    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean savingDisabled) {
        // Paper start - add close param
        this.save(progressListener, flush, savingDisabled, false);
    }
    public void save(@Nullable ProgressListener progressListener, boolean flush, boolean savingDisabled, boolean close) {
        // Paper end - add close param
        ServerChunkCache chunkproviderserver = this.getChunkSource();

        if (!savingDisabled) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(this.getWorld())); // CraftBukkit
            if (progressListener != null) {
                progressListener.progressStartNoAbort(Component.translatable("menu.savingLevel"));
            }

            this.saveLevelData(flush);
            if (progressListener != null) {
                progressListener.progressStage(Component.translatable("menu.savingChunks"));
            }

            if (!close) { chunkproviderserver.save(flush); } // Paper - add close param
            // Paper - rewrite chunk system

        }
        // Paper start - add close param
        if (close) {
            try {
                chunkproviderserver.close(!savingDisabled);
            } catch (IOException never) {
                throw new RuntimeException(never);
            }
        }
        // Paper end - add close param

        // Folia - move into saveLevelData
    }

    public void saveLevelData(boolean flush) { // Folia - public
        if (this.dragonFight != null) {
            this.serverLevelData.setEndDragonFightData(this.dragonFight.saveData()); // CraftBukkit
        }
        // Folia start - moved into saveLevelData
        ServerLevel worldserver1 = this;

        this.serverLevelData.setWorldBorder(worldserver1.getWorldBorder().createSettings());
        this.serverLevelData.setCustomBossEvents(this.server.getCustomBossEvents().save(this.registryAccess()));
        this.convertable.saveDataTag(this.server.registryAccess(), this.serverLevelData, this.server.getPlayerList().getSingleplayerData());
        // Folia end - moved into saveLevelData

        DimensionDataStorage worldpersistentdata = this.getChunkSource().getDataStorage();

        if (flush) {
            worldpersistentdata.saveAndJoin();
        } else {
            worldpersistentdata.scheduleSave();
        }

    }

    public <T extends Entity> List<? extends T> getEntities(EntityTypeTest<Entity, T> filter, Predicate<? super T> predicate) {
        List<T> list = Lists.newArrayList();

        this.getEntities(filter, predicate, (List) list);
        return list;
    }

    public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> filter, Predicate<? super T> predicate, List<? super T> result) {
        this.getEntities(filter, predicate, result, Integer.MAX_VALUE);
    }

    public <T extends Entity> void getEntities(EntityTypeTest<Entity, T> filter, Predicate<? super T> predicate, List<? super T> result, int limit) {
        this.getEntities().get(filter, (entity) -> {
            if (predicate.test(entity)) {
                result.add(entity);
                if (result.size() >= limit) {
                    return AbortableIterationConsumer.Continuation.ABORT;
                }
            }

            return AbortableIterationConsumer.Continuation.CONTINUE;
        });
    }

    public List<? extends EnderDragon> getDragons() {
        return this.getEntities((EntityTypeTest) EntityType.ENDER_DRAGON, LivingEntity::isAlive);
    }

    public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate) {
        return this.getPlayers(predicate, Integer.MAX_VALUE);
    }

    public List<ServerPlayer> getPlayers(Predicate<? super ServerPlayer> predicate, int limit) {
        List<ServerPlayer> list = Lists.newArrayList();
        Iterator iterator = this.players.iterator();

        while (iterator.hasNext()) {
            ServerPlayer entityplayer = (ServerPlayer) iterator.next();

            if (predicate.test(entityplayer)) {
                list.add(entityplayer);
                if (list.size() >= limit) {
                    return list;
                }
            }
        }

        return list;
    }

    // Folia start - region threading
    @Nullable
    public ServerPlayer getRandomLocalPlayer() {
        List<ServerPlayer> list = this.getLocalPlayers();
        list = new java.util.ArrayList<>(list);
        list.removeIf((ServerPlayer player) -> {
            return !player.isAlive();
        });

        return list.isEmpty() ? null : (ServerPlayer) list.get(this.random.nextInt(list.size()));
    }
    // Folia end - region threading

    @Nullable
    public ServerPlayer getRandomPlayer() {
        List<ServerPlayer> list = this.getPlayers(LivingEntity::isAlive);

        return list.isEmpty() ? null : (ServerPlayer) list.get(this.random.nextInt(list.size()));
    }

    @Override
    public boolean addFreshEntity(Entity entity) {
        // CraftBukkit start
        return this.addFreshEntity(entity, CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    @Override
    public boolean addFreshEntity(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return this.addEntity(entity, reason);
        // CraftBukkit end
    }

    public boolean addWithUUID(Entity entity) {
        // CraftBukkit start
        return this.addWithUUID(entity, CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    public boolean addWithUUID(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return this.addEntity(entity, reason);
        // CraftBukkit end
    }

    public void addDuringTeleport(Entity entity) {
        // CraftBukkit start
        // SPIGOT-6415: Don't call spawn event for entities which travel trough worlds,
        // since it is only an implementation detail, that a new entity is created when
        // they are traveling between worlds.
        this.addDuringTeleport(entity, null);
    }

    public void addDuringTeleport(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        // CraftBukkit end
        if (entity instanceof ServerPlayer entityplayer) {
            this.addPlayer(entityplayer);
        } else {
            this.addEntity(entity, reason); // CraftBukkit
        }

    }

    public void addNewPlayer(ServerPlayer player) {
        this.addPlayer(player);
    }

    public void addRespawnedPlayer(ServerPlayer player) {
        this.addPlayer(player);
    }

    private void addPlayer(ServerPlayer player) {
        Entity entity = (Entity) this.getEntities().get(player.getUUID());

        if (entity != null) {
            ServerLevel.LOGGER.warn("Force-added player with duplicate UUID {}", player.getUUID());
            entity.unRide();
            this.removePlayerImmediately((ServerPlayer) entity, Entity.RemovalReason.DISCARDED);
        }

        this.moonrise$getEntityLookup().addNewEntity(player); // Paper - rewrite chunk system
    }

    // CraftBukkit start
    private boolean addEntity(Entity entity, CreatureSpawnEvent.SpawnReason spawnReason) {
        org.spigotmc.AsyncCatcher.catchOp("entity add"); // Spigot
        entity.generation = false; // Paper - Don't fire sync event during generation; Reset flag if it was added during a ServerLevel generation process
        // Paper start - extra debug info
        if (entity.valid) {
            MinecraftServer.LOGGER.error("Attempted Double World add on {}", entity, new Throwable());
            return true;
        }
        // Paper end - extra debug info
        if (entity.spawnReason == null) entity.spawnReason = spawnReason; // Paper - Entity#getEntitySpawnReason
        if (entity.isRemoved()) {
            // WorldServer.LOGGER.warn("Tried to add entity {} but it was marked as removed already", EntityTypes.getKey(entity.getType())); // CraftBukkit
            return false;
        } else {
            if (entity instanceof net.minecraft.world.entity.item.ItemEntity itemEntity && itemEntity.getItem().isEmpty()) return false; // Paper - Prevent empty items from being added
            // Paper start - capture all item additions to the world
            if (this.getCurrentWorldData().captureDrops != null && entity instanceof net.minecraft.world.entity.item.ItemEntity) { // Folia - region threading
                this.getCurrentWorldData().captureDrops.add((net.minecraft.world.entity.item.ItemEntity) entity); // Folia - region threading
                return true;
            }
            // Paper end - capture all item additions to the world
            // SPIGOT-6415: Don't call spawn event when reason is null. For example when an entity teleports to a new world.
            if (spawnReason != null && !CraftEventFactory.doEntityAddEventCalling(this, entity, spawnReason)) {
                return false;
            }
            // CraftBukkit end

            return this.moonrise$getEntityLookup().addNewEntity(entity); // Paper - rewrite chunk system
        }
    }

    public boolean tryAddFreshEntityWithPassengers(Entity entity) {
        // CraftBukkit start
        return this.tryAddFreshEntityWithPassengers(entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    public boolean tryAddFreshEntityWithPassengers(Entity entity, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
        // CraftBukkit end
        if (entity.getSelfAndPassengers().map(Entity::getUUID).anyMatch(this.moonrise$getEntityLookup()::hasEntity)) { // Paper - rewrite chunk system
            return false;
        } else {
            this.addFreshEntityWithPassengers(entity, reason); // CraftBukkit
            return true;
        }
    }

    public void unload(LevelChunk chunk) {
        // Spigot Start
        for (net.minecraft.world.level.block.entity.BlockEntity tileentity : chunk.getBlockEntities().values()) {
            if (tileentity instanceof net.minecraft.world.Container) {
                // Paper start - this area looks like it can load chunks, change the behavior
                // chests for example can apply physics to the world
                // so instead we just change the active container and call the event
                for (org.bukkit.entity.HumanEntity h : Lists.newArrayList(((net.minecraft.world.Container) tileentity).getViewers())) {
                    ((org.bukkit.craftbukkit.entity.CraftHumanEntity) h).getHandle().closeUnloadedInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper - Inventory close reason
                }
                // Paper end - this area looks like it can load chunks, change the behavior
            }
        }
        // Spigot End
        chunk.clearAllBlockEntities();
        chunk.unregisterTickContainerFromLevel(this);
    }

    public void removePlayerImmediately(ServerPlayer player, Entity.RemovalReason reason) {
        player.remove(reason, null); // CraftBukkit - add Bukkit remove cause
    }

    // CraftBukkit start
    public boolean strikeLightning(Entity entitylightning) {
        return this.strikeLightning(entitylightning, LightningStrikeEvent.Cause.UNKNOWN);
    }

    public boolean strikeLightning(Entity entitylightning, LightningStrikeEvent.Cause cause) {
        LightningStrikeEvent lightning = CraftEventFactory.callLightningStrikeEvent((org.bukkit.entity.LightningStrike) entitylightning.getBukkitEntity(), cause);

        if (lightning.isCancelled()) {
            return false;
        }

        return this.addFreshEntity(entitylightning);
    }
    // CraftBukkit end

    @Override
    public void destroyBlockProgress(int entityId, BlockPos pos, int progress) {
        Iterator iterator = this.server.getPlayerList().getPlayers().iterator();

        // CraftBukkit start
        Player entityhuman = null;
        Entity entity = this.getEntity(entityId);
        if (entity instanceof Player) entityhuman = (Player) entity;
        // CraftBukkit end

        // Paper start - Add BlockBreakProgressUpdateEvent
        // If a plugin is using this method to send destroy packets for a client-side only entity id, no block progress occurred on the server.
        // Hence, do not call the event.
        if (entity != null) {
            float progressFloat = Mth.clamp(progress, 0, 10) / 10.0f;
            org.bukkit.craftbukkit.block.CraftBlock bukkitBlock = org.bukkit.craftbukkit.block.CraftBlock.at(this, pos);
            new io.papermc.paper.event.block.BlockBreakProgressUpdateEvent(bukkitBlock, progressFloat, entity.getBukkitEntity())
                .callEvent();
        }
        // Paper end - Add BlockBreakProgressUpdateEvent

        while (iterator.hasNext()) {
            ServerPlayer entityplayer = (ServerPlayer) iterator.next();

            if (entityplayer != null && entityplayer.level() == this && entityplayer.getId() != entityId) {
                double d0 = (double) pos.getX() - entityplayer.getX();
                double d1 = (double) pos.getY() - entityplayer.getY();
                double d2 = (double) pos.getZ() - entityplayer.getZ();

                // CraftBukkit start
                if (entityhuman != null && !entityplayer.getBukkitEntity().canSee(entityhuman.getBukkitEntity())) {
                    continue;
                }
                // CraftBukkit end

                if (d0 * d0 + d1 * d1 + d2 * d2 < 1024.0D) {
                    entityplayer.connection.send(new ClientboundBlockDestructionPacket(entityId, pos, progress));
                }
            }
        }

    }

    @Override
    public void playSeededSound(@Nullable Player source, double x, double y, double z, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
        this.server.getPlayerList().broadcast(source, x, y, z, (double) ((SoundEvent) sound.value()).getRange(volume), this.dimension(), new ClientboundSoundPacket(sound, category, x, y, z, volume, pitch, seed));
    }

    @Override
    public void playSeededSound(@Nullable Player source, Entity entity, Holder<SoundEvent> sound, SoundSource category, float volume, float pitch, long seed) {
        this.server.getPlayerList().broadcast(source, entity.getX(), entity.getY(), entity.getZ(), (double) ((SoundEvent) sound.value()).getRange(volume), this.dimension(), new ClientboundSoundEntityPacket(sound, category, entity, volume, pitch, seed));
    }

    @Override
    public void globalLevelEvent(int eventId, BlockPos pos, int data) {
        if (this.getGameRules().getBoolean(GameRules.RULE_GLOBAL_SOUND_EVENTS)) {
            this.server.getPlayerList().getPlayers().forEach((entityplayer) -> {
                Vec3 vec3d;

                if (entityplayer.level() == this) {
                    Vec3 vec3d1 = Vec3.atCenterOf(pos);

                    if (entityplayer.distanceToSqr(vec3d1) < (double) Mth.square(32)) {
                        vec3d = vec3d1;
                    } else {
                        Vec3 vec3d2 = vec3d1.subtract(entityplayer.position()).normalize();

                        vec3d = entityplayer.position().add(vec3d2.scale(32.0D));
                    }
                } else {
                    vec3d = entityplayer.position();
                }

                entityplayer.connection.send(new ClientboundLevelEventPacket(eventId, BlockPos.containing(vec3d), data, true));
            });
        } else {
            this.levelEvent((Player) null, eventId, pos, data);
        }

    }

    @Override
    public void levelEvent(@Nullable Player player, int eventId, BlockPos pos, int data) {
        this.server.getPlayerList().broadcast(player, (double) pos.getX(), (double) pos.getY(), (double) pos.getZ(), 64.0D, this.dimension(), new ClientboundLevelEventPacket(eventId, pos, data, false)); // Paper - diff on change (the 64.0 distance is used as defaults for sound ranges in spigot config for ender dragon, end portal and wither)
    }

    public int getLogicalHeight() {
        return this.dimensionType().logicalHeight();
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 emitterPos, GameEvent.Context emitter) {
        // Paper start - Prevent GameEvents being fired from unloaded chunks
        if (this.getChunkIfLoadedImmediately((Mth.floor(emitterPos.x) >> 4), (Mth.floor(emitterPos.z) >> 4)) == null) {
            return;
        }
        // Paper end - Prevent GameEvents being fired from unloaded chunks
        this.gameEventDispatcher.post(event, emitterPos, emitter);
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        if (false && this.isUpdatingNavigations) { // Folia - region threading
            String s = "recursive call to sendBlockUpdated";

            Util.logAndPauseIfInIde("recursive call to sendBlockUpdated", new IllegalStateException("recursive call to sendBlockUpdated"));
        }

        this.getChunkSource().blockChanged(pos);
        final io.papermc.paper.threadedregions.RegionizedWorldData regionizedWorldData = this.getCurrentWorldData(); // Folia - region threading
        regionizedWorldData.pathTypesByPosCache.invalidate(pos); // Folia - region threading
        if (this.paperConfig().misc.updatePathfindingOnBlockUpdate) { // Paper - option to disable pathfinding updates
        VoxelShape voxelshape = oldState.getCollisionShape(this, pos);
        VoxelShape voxelshape1 = newState.getCollisionShape(this, pos);

        if (Shapes.joinIsNotEmpty(voxelshape, voxelshape1, BooleanOp.NOT_SAME)) {
            List<PathNavigation> list = new ObjectArrayList();
            Iterator iterator = regionizedWorldData.getNavigatingMobs(); // Folia - region threading

            while (iterator.hasNext()) {
                // CraftBukkit start - fix SPIGOT-6362
                Mob entityinsentient;
                try {
                    entityinsentient = (Mob) iterator.next();
                } catch (java.util.ConcurrentModificationException ex) {
                    // This can happen because the pathfinder update below may trigger a chunk load, which in turn may cause more navigators to register
                    // In this case we just run the update again across all the iterators as the chunk will then be loaded
                    // As this is a relative edge case it is much faster than copying navigators (on either read or write)
                    this.sendBlockUpdated(pos, oldState, newState, flags);
                    return;
                }
                // CraftBukkit end
                PathNavigation navigationabstract = entityinsentient.getNavigation();

                if (navigationabstract.shouldRecomputePath(pos)) {
                    list.add(navigationabstract);
                }
            }

            try {
                //this.isUpdatingNavigations = true; // Folia - region threading
                iterator = list.iterator();

                while (iterator.hasNext()) {
                    PathNavigation navigationabstract1 = (PathNavigation) iterator.next();

                    navigationabstract1.recomputePath();
                }
            } finally {
                //this.isUpdatingNavigations = false; // Folia - region threading
            }

        }
        } // Paper - option to disable pathfinding updates
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block block) {
        if (this.getCurrentWorldData().captureBlockStates) { return; } // Paper - Cancel all physics during placement // Folia - region threading
        this.updateNeighborsAt(pos, block, ExperimentalRedstoneUtils.initialOrientation(this, (Direction) null, (Direction) null));
    }

    @Override
    public void updateNeighborsAt(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation) {
        if (this.getCurrentWorldData().captureBlockStates) { return; } // Paper - Cancel all physics during placement // Folia - region threading
        this.getCurrentWorldData().neighborUpdater.updateNeighborsAtExceptFromFacing(pos, sourceBlock, (Direction) null, orientation); // Folia - region threading
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block sourceBlock, Direction direction, @Nullable Orientation orientation) {
        this.getCurrentWorldData().neighborUpdater.updateNeighborsAtExceptFromFacing(pos, sourceBlock, direction, orientation); // Folia - region threading
    }

    @Override
    public void neighborChanged(BlockPos pos, Block sourceBlock, @Nullable Orientation orientation) {
        this.getCurrentWorldData().neighborUpdater.neighborChanged(pos, sourceBlock, orientation); // Folia - region threading
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block sourceBlock, @Nullable Orientation orientation, boolean notify) {
        this.getCurrentWorldData().neighborUpdater.neighborChanged(state, pos, sourceBlock, orientation, notify); // Folia - region threading
    }

    @Override
    public void broadcastEntityEvent(Entity entity, byte status) {
        this.getChunkSource().broadcastAndSend(entity, new ClientboundEntityEventPacket(entity, status));
    }

    @Override
    public void broadcastDamageEvent(Entity entity, DamageSource damageSource) {
        this.getChunkSource().broadcastAndSend(entity, new ClientboundDamageEventPacket(entity, damageSource));
    }

    @Override
    public ServerChunkCache getChunkSource() {
        return this.chunkSource;
    }

    @Override
    public void explode(@Nullable Entity entity, @Nullable DamageSource damageSource, @Nullable ExplosionDamageCalculator behavior, double x, double y, double z, float power, boolean createFire, Level.ExplosionInteraction explosionSourceType, ParticleOptions smallParticle, ParticleOptions largeParticle, Holder<SoundEvent> soundEvent) {
        // CraftBukkit start
        this.explode0(entity, damageSource, behavior, x, y, z, power, createFire, explosionSourceType, smallParticle, largeParticle, soundEvent);
    }

    public ServerExplosion explode0(@Nullable Entity entity, @Nullable DamageSource damagesource, @Nullable ExplosionDamageCalculator explosiondamagecalculator, double d0, double d1, double d2, float f, boolean flag, Level.ExplosionInteraction world_a, ParticleOptions particleparam, ParticleOptions particleparam1, Holder<SoundEvent> holder) {
    // Paper start - Allow explosions to damage source
        return this.explode0(entity, damagesource, explosiondamagecalculator, d0, d1, d2, f, flag, world_a, particleparam, particleparam1, holder, null);
    }
    public ServerExplosion explode0(@Nullable Entity entity, @Nullable DamageSource damagesource, @Nullable ExplosionDamageCalculator explosiondamagecalculator, double d0, double d1, double d2, float f, boolean flag, Level.ExplosionInteraction world_a, ParticleOptions particleparam, ParticleOptions particleparam1, Holder<SoundEvent> holder, java.util.function.Consumer<ServerExplosion> configurator) {
    // Paper end - Allow explosions to damage source
        // CraftBukkit end
        Explosion.BlockInteraction explosion_effect;

        switch (world_a) {
            case NONE:
                explosion_effect = Explosion.BlockInteraction.KEEP;
                break;
            case BLOCK:
                explosion_effect = this.getDestroyType(GameRules.RULE_BLOCK_EXPLOSION_DROP_DECAY);
                break;
            case MOB:
                explosion_effect = this.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) ? this.getDestroyType(GameRules.RULE_MOB_EXPLOSION_DROP_DECAY) : Explosion.BlockInteraction.KEEP;
                break;
            case TNT:
                explosion_effect = this.getDestroyType(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY);
                break;
            case TRIGGER:
                explosion_effect = Explosion.BlockInteraction.TRIGGER_BLOCK;
                break;
            // CraftBukkit start - handle custom explosion type
            case STANDARD:
                explosion_effect = Explosion.BlockInteraction.DESTROY;
                break;
            // CraftBukkit end
            default:
                throw new MatchException((String) null, (Throwable) null);
        }

        Explosion.BlockInteraction explosion_effect1 = explosion_effect;
        Vec3 vec3d = new Vec3(d0, d1, d2);
        ServerExplosion serverexplosion = new ServerExplosion(this, entity, damagesource, explosiondamagecalculator, vec3d, f, flag, explosion_effect1);
        if (configurator != null) configurator.accept(serverexplosion);// Paper - Allow explosions to damage source

        serverexplosion.explode();
        // CraftBukkit start
        if (serverexplosion.wasCanceled) {
            return serverexplosion;
        }
        // CraftBukkit end
        ParticleOptions particleparam2 = serverexplosion.isSmall() ? particleparam : particleparam1;
        Iterator iterator = this.getLocalPlayers().iterator(); // Folia - region thraeding

        while (iterator.hasNext()) {
            ServerPlayer entityplayer = (ServerPlayer) iterator.next();

            if (entityplayer.distanceToSqr(vec3d) < 4096.0D) {
                Optional<Vec3> optional = Optional.ofNullable((Vec3) serverexplosion.getHitPlayers().get(entityplayer));

                entityplayer.connection.send(new ClientboundExplodePacket(vec3d, optional, particleparam2, holder));
            }
        }

        return serverexplosion; // CraftBukkit
    }

    private Explosion.BlockInteraction getDestroyType(GameRules.Key<GameRules.BooleanValue> decayRule) {
        return this.getGameRules().getBoolean(decayRule) ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
    }

    @Override
    public void blockEvent(BlockPos pos, Block block, int type, int data) {
        this.getCurrentWorldData().pushBlockEvent(new BlockEventData(pos, block, type, data)); // Folia - regionised ticking
    }

    private void runBlockEvents() {
        List<BlockEventData> blockEventsToReschedule = new ArrayList<>(64); // Folia - regionised ticking

        // Folia start - regionised ticking
        io.papermc.paper.threadedregions.RegionizedWorldData worldRegionData = this.getCurrentWorldData();
        BlockEventData blockactiondata;
        while ((blockactiondata = worldRegionData.removeFirstBlockEvent()) != null) {
            // Folia end - regionised ticking

            if (this.shouldTickBlocksAt(blockactiondata.pos())) {
                if (this.doBlockEvent(blockactiondata)) {
                    this.server.getPlayerList().broadcast((Player) null, (double) blockactiondata.pos().getX(), (double) blockactiondata.pos().getY(), (double) blockactiondata.pos().getZ(), 64.0D, this.dimension(), new ClientboundBlockEventPacket(blockactiondata.pos(), blockactiondata.block(), blockactiondata.paramA(), blockactiondata.paramB()));
                }
            } else {
                blockEventsToReschedule.add(blockactiondata); // Folia - regionised ticking
            }
        }

        worldRegionData.pushBlockEvents(blockEventsToReschedule); // Folia - regionised ticking
    }

    private boolean doBlockEvent(BlockEventData event) {
        BlockState iblockdata = this.getBlockState(event.pos());

        return iblockdata.is(event.block()) ? iblockdata.triggerEvent(this, event.pos(), event.paramA(), event.paramB()) : false;
    }

    @Override
    public LevelTicks<Block> getBlockTicks() {
        return this.getCurrentWorldData().getBlockLevelTicks(); // Folia - region ticking
    }

    @Override
    public LevelTicks<Fluid> getFluidTicks() {
        return this.getCurrentWorldData().getFluidLevelTicks(); // Folia - region ticking
    }

    @Nonnull
    @Override
    public MinecraftServer getServer() {
        return this.server;
    }

    public PortalForcer getPortalForcer() {
        return this.portalForcer;
    }

    public StructureTemplateManager getStructureManager() {
        return this.server.getStructureManager();
    }

    public <T extends ParticleOptions> int sendParticles(T parameters, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
        return this.sendParticlesSource(null, parameters, false, false, x, y, z, count, offsetX, offsetY, offsetZ, speed); // CraftBukkit - visibility api support
    }

    public <T extends ParticleOptions> int sendParticles(T parameters, boolean force, boolean important, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
        return this.sendParticlesSource(null, parameters, force, important, x, y, z, count, offsetX, offsetY, offsetZ, speed); // CraftBukkit - visibility api support
    }

    // CraftBukkit start - visibility api support
    public <T extends ParticleOptions> int sendParticlesSource(ServerPlayer sender, T t0, boolean flag, boolean flag1, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6) {
        // Paper start - Particle API
        return this.sendParticlesSource(this.getLocalPlayers(), sender, t0, flag, flag1, d0, d1, d2, i, d3, d4, d5, d6); // Folia - region threading
    }
    public <T extends ParticleOptions> int sendParticlesSource(List<ServerPlayer> receivers, @Nullable ServerPlayer sender, T t0, boolean flag, boolean flag1, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6) {
        // Paper end - Particle API
        // CraftBukkit end
        ClientboundLevelParticlesPacket packetplayoutworldparticles = new ClientboundLevelParticlesPacket(t0, flag, flag1, d0, d1, d2, (float) d3, (float) d4, (float) d5, (float) d6, i);
        int j = 0;

        for (Player entityhuman : receivers) { // Paper - Particle API
            ServerPlayer entityplayer = (ServerPlayer) entityhuman; // Paper - Particle API
            if (sender != null && !entityplayer.getBukkitEntity().canSee(sender.getBukkitEntity())) continue; // CraftBukkit

            if (this.sendParticles(entityplayer, flag, d0, d1, d2, packetplayoutworldparticles)) {
                ++j;
            }
        }

        return j;
    }

    public <T extends ParticleOptions> boolean sendParticles(ServerPlayer viewer, T parameters, boolean force, boolean important, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double speed) {
        Packet<?> packet = new ClientboundLevelParticlesPacket(parameters, force, important, x, y, z, (float) offsetX, (float) offsetY, (float) offsetZ, (float) speed, count);

        return this.sendParticles(viewer, force, x, y, z, packet);
    }

    private boolean sendParticles(ServerPlayer player, boolean force, double x, double y, double z, Packet<?> packet) {
        if (player.level() != this) {
            return false;
        } else {
            BlockPos blockposition = player.blockPosition();

            if (blockposition.closerToCenterThan(new Vec3(x, y, z), force ? 512.0D : 32.0D)) {
                player.connection.send(packet);
                return true;
            } else {
                return false;
            }
        }
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return (Entity) this.getEntities().get(id);
    }

    @Nullable
    public Entity getEntity(UUID uuid) {
        return (Entity) this.getEntities().get(uuid);
    }

    /** @deprecated */
    @Deprecated
    @Nullable
    public Entity getEntityOrPart(int id) {
        Entity entity = (Entity) this.getEntities().get(id);

        // Folia start - region threading
        if (entity !=  null) {
            return entity;
        }
        synchronized (this.dragonParts) {
            return this.dragonParts.get(id);
        }
        // Folia end - region threading
    }

    @Override
    public Collection<EnderDragonPart> dragonParts() {
        return this.dragonParts.values();
    }

    @Nullable
    public BlockPos findNearestMapStructure(TagKey<Structure> structureTag, BlockPos pos, int radius, boolean skipReferencedStructures) {
        if (!this.serverLevelData.worldGenOptions().generateStructures()) { // CraftBukkit
            return null;
        } else {
            Optional<HolderSet.Named<Structure>> optional = this.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(structureTag);

            if (optional.isEmpty()) {
                return null;
            } else {
                Pair<BlockPos, Holder<Structure>> pair = this.getChunkSource().getGenerator().findNearestMapStructure(this, (HolderSet) optional.get(), pos, radius, skipReferencedStructures);

                return pair != null ? (BlockPos) pair.getFirst() : null;
            }
        }
    }

    @Nullable
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(Predicate<Holder<Biome>> predicate, BlockPos pos, int radius, int horizontalBlockCheckInterval, int verticalBlockCheckInterval) {
        return this.getChunkSource().getGenerator().getBiomeSource().findClosestBiome3d(pos, radius, horizontalBlockCheckInterval, verticalBlockCheckInterval, predicate, this.getChunkSource().randomState().sampler(), this);
    }

    @Override
    public RecipeManager recipeAccess() {
        return this.server.getRecipeManager();
    }

    @Override
    public TickRateManager tickRateManager() {
        return this.server.tickRateManager();
    }

    @Override
    public boolean noSave() {
        return this.noSave;
    }

    public DimensionDataStorage getDataStorage() {
        return this.getChunkSource().getDataStorage();
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(MapId id) {
        // Paper start - Call missing map initialize event and set id
        final DimensionDataStorage storage = this.getServer().overworld().getDataStorage();

        synchronized (storage.cache) { // Folia - region threading
        final Optional<net.minecraft.world.level.saveddata.SavedData> cacheEntry = storage.cache.get(id.key());
        if (cacheEntry == null) { // Cache did not contain, try to load and may init
            final MapItemSavedData worldmap = storage.get(MapItemSavedData.factory(), id.key()); // get populates the cache
            if (worldmap != null) { // map was read, init it and return
                worldmap.id = id;
                new MapInitializeEvent(worldmap.mapView).callEvent();
                return worldmap;
            }

            return null; // Map does not exist, reading failed.
        }

        // Cache entry exists, update it with the id ref and return.
        if (cacheEntry.orElse(null) instanceof final MapItemSavedData mapItemSavedData) {
            mapItemSavedData.id = id;
            return mapItemSavedData;
        }

        return null;
        } // Folia - region threading
        // Paper end - Call missing map initialize event and set id
    }

    @Override
    public void setMapData(MapId id, MapItemSavedData state) {
        // CraftBukkit start
        state.id = id;
        MapInitializeEvent event = new MapInitializeEvent(state.mapView);
        Bukkit.getServer().getPluginManager().callEvent(event);
        // CraftBukkit end
        this.getServer().overworld().getDataStorage().set(id.key(), state);
    }

    @Override
    public MapId getFreeMapId() {
        return ((MapIndex) this.getServer().overworld().getDataStorage().computeIfAbsent(MapIndex.factory(), "idcounts")).getFreeAuxValueForMap();
    }

    public void setDefaultSpawnPos(BlockPos pos, float angle) {
        BlockPos blockposition1 = this.levelData.getSpawnPos();
        float f1 = this.levelData.getSpawnAngle();

        if (!blockposition1.equals(pos) || f1 != angle) {
            org.bukkit.Location prevSpawnLoc = this.getWorld().getSpawnLocation(); // Paper - Call SpawnChangeEvent
            this.levelData.setSpawn(pos, angle);
            new org.bukkit.event.world.SpawnChangeEvent(this.getWorld(), prevSpawnLoc).callEvent(); // Paper - Call SpawnChangeEvent
            this.getServer().getPlayerList().broadcastAll(new ClientboundSetDefaultSpawnPositionPacket(pos, angle));
        }

        if (this.lastSpawnChunkRadius > 1) {
            // Paper start - allow disabling gamerule limits
            for (ChunkPos chunkPos : io.papermc.paper.util.MCUtil.getSpiralOutChunks(blockposition1, this.lastSpawnChunkRadius - 2)) {
                this.getChunkSource().removeTicketAtLevel(TicketType.START, chunkPos, net.minecraft.server.level.ChunkLevel.ENTITY_TICKING_LEVEL, Unit.INSTANCE);
            }
            // Paper end - allow disabling gamerule limits
        }

        int i = this.getGameRules().getInt(GameRules.RULE_SPAWN_CHUNK_RADIUS) + 1;

        if (i > 1) {
            // Paper start - allow disabling gamerule limits
            for (ChunkPos chunkPos : io.papermc.paper.util.MCUtil.getSpiralOutChunks(pos, i - 2)) {
                this.getChunkSource().addTicketAtLevel(TicketType.START, chunkPos, net.minecraft.server.level.ChunkLevel.ENTITY_TICKING_LEVEL, Unit.INSTANCE);
            }
            // Paper end - allow disabling gamerule limits
        }

        this.lastSpawnChunkRadius = i;
    }

    public LongSet getForcedChunks() {
        ForcedChunksSavedData forcedchunk = (ForcedChunksSavedData) this.getDataStorage().get(ForcedChunksSavedData.factory(), "chunks");

        return (LongSet) (forcedchunk != null ? LongSets.unmodifiable(forcedchunk.getChunks()) : LongSets.EMPTY_SET);
    }

    public boolean setChunkForced(int x, int z, boolean forced) {
        io.papermc.paper.threadedregions.RegionizedServer.ensureGlobalTickThread("Cannot modify force loaded chunks off of the global region"); // Folia - region threading
        ForcedChunksSavedData forcedchunk = (ForcedChunksSavedData) this.getDataStorage().computeIfAbsent(ForcedChunksSavedData.factory(), "chunks");
        ChunkPos chunkcoordintpair = new ChunkPos(x, z);
        long k = chunkcoordintpair.toLong();
        boolean flag1;

        if (forced) {
            flag1 = forcedchunk.getChunks().add(k);
            if (flag1) {
                //this.getChunk(x, z); // Folia - region threading - we must let the chunk load asynchronously
            }
        } else {
            flag1 = forcedchunk.getChunks().remove(k);
        }

        forcedchunk.setDirty(flag1);
        if (flag1) {
            this.getChunkSource().updateChunkForced(chunkcoordintpair, forced);
        }

        return flag1;
    }

    @Override
    public List<ServerPlayer> players() {
        return this.players;
    }

    @Override
    public void onBlockStateChange(BlockPos pos, BlockState oldBlock, BlockState newBlock) {
        Optional<Holder<PoiType>> optional = PoiTypes.forState(oldBlock);
        Optional<Holder<PoiType>> optional1 = PoiTypes.forState(newBlock);

        if (!Objects.equals(optional, optional1)) {
            BlockPos blockposition1 = pos.immutable();

            optional.ifPresent((holder) -> {
                Runnable run = () -> { // Folia - region threading
                    this.getPoiManager().remove(blockposition1);
                    DebugPackets.sendPoiRemovedPacket(this, blockposition1);
                }; // Folia - region threading
                // Folia start - region threading
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(
                    this, blockposition1.getX() >> 4, blockposition1.getZ() >> 4, run
                );
                // Folia end - region threading
            });
            optional1.ifPresent((holder) -> {
                Runnable run = () -> { // Folia - region threading
                    // Paper start - Remove stale POIs
                    if (optional.isEmpty() && this.getPoiManager().exists(blockposition1, poiType -> true)) {
                        this.getPoiManager().remove(blockposition1);
                    }
                    // Paper end - Remove stale POIs
                    this.getPoiManager().add(blockposition1, holder);
                    DebugPackets.sendPoiAddedPacket(this, blockposition1);
                }; // Folia - region threading
                // Folia start - region threading
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueChunkTask(
                    this, blockposition1.getX() >> 4, blockposition1.getZ() >> 4, run
                );
                // Folia end - region threading
            });
        }
    }

    public PoiManager getPoiManager() {
        return this.getChunkSource().getPoiManager();
    }

    public boolean isVillage(BlockPos pos) {
        return this.isCloseToVillage(pos, 1);
    }

    public boolean isVillage(SectionPos sectionPos) {
        return this.isVillage(sectionPos.center());
    }

    public boolean isCloseToVillage(BlockPos pos, int maxDistance) {
        return maxDistance > 6 ? false : this.sectionsToVillage(SectionPos.of(pos)) <= maxDistance;
    }

    public int sectionsToVillage(SectionPos pos) {
        return this.getPoiManager().sectionsToVillage(pos);
    }

    public Raids getRaids() {
        return this.raids;
    }

    @Nullable
    public Raid getRaidAt(BlockPos pos) {
        return this.raids.getNearbyRaid(pos, 9216);
    }

    public boolean isRaided(BlockPos pos) {
        return this.getRaidAt(pos) != null;
    }

    public void onReputationEvent(ReputationEventType interaction, Entity entity, ReputationEventHandler observer) {
        observer.onReputationEventFrom(interaction, entity);
    }

    public void saveDebugReport(Path path) throws IOException {
        ChunkMap playerchunkmap = this.getChunkSource().chunkMap;
        BufferedWriter bufferedwriter = Files.newBufferedWriter(path.resolve("stats.txt"));

        try {
            //bufferedwriter.write(String.format(Locale.ROOT, "spawning_chunks: %d\n", playerchunkmap.getDistanceManager().getNaturalSpawnChunkCount())); // Folia - region threading
            NaturalSpawner.SpawnState spawnercreature_d = this.getChunkSource().getLastSpawnState();

            if (spawnercreature_d != null) {
                ObjectIterator objectiterator = spawnercreature_d.getMobCategoryCounts().object2IntEntrySet().iterator();

                while (objectiterator.hasNext()) {
                    Entry<MobCategory> entry = (Entry) objectiterator.next();

                    bufferedwriter.write(String.format(Locale.ROOT, "spawn_count.%s: %d\n", ((MobCategory) entry.getKey()).getName(), entry.getIntValue()));
                }
            }

            bufferedwriter.write(String.format(Locale.ROOT, "entities: %s\n", this.moonrise$getEntityLookup().getDebugInfo())); // Paper - rewrite chunk system
            //bufferedwriter.write(String.format(Locale.ROOT, "block_entity_tickers: %d\n", this.blockEntityTickers.size())); // Folia - region threading
            bufferedwriter.write(String.format(Locale.ROOT, "block_ticks: %d\n", this.getBlockTicks().count()));
            bufferedwriter.write(String.format(Locale.ROOT, "fluid_ticks: %d\n", this.getFluidTicks().count()));
            bufferedwriter.write("distance_manager: " + playerchunkmap.getDistanceManager().getDebugStatus() + "\n");
            bufferedwriter.write(String.format(Locale.ROOT, "pending_tasks: %d\n", this.getChunkSource().getPendingTasksCount()));
        } catch (Throwable throwable) {
            if (bufferedwriter != null) {
                try {
                    bufferedwriter.close();
                } catch (Throwable throwable1) {
                    throwable.addSuppressed(throwable1);
                }
            }

            throw throwable;
        }

        if (bufferedwriter != null) {
            bufferedwriter.close();
        }

        CrashReport crashreport = new CrashReport("Level dump", new Exception("dummy"));

        this.fillReportDetails(crashreport);
        BufferedWriter bufferedwriter1 = Files.newBufferedWriter(path.resolve("example_crash.txt"));

        try {
            bufferedwriter1.write(crashreport.getFriendlyReport(ReportType.TEST));
        } catch (Throwable throwable2) {
            if (bufferedwriter1 != null) {
                try {
                    bufferedwriter1.close();
                } catch (Throwable throwable3) {
                    throwable2.addSuppressed(throwable3);
                }
            }

            throw throwable2;
        }

        if (bufferedwriter1 != null) {
            bufferedwriter1.close();
        }

        Path path1 = path.resolve("chunks.csv");
        BufferedWriter bufferedwriter2 = Files.newBufferedWriter(path1);

        try {
            //playerchunkmap.dumpChunks(bufferedwriter2); // Paper - rewrite chunk system
        } catch (Throwable throwable4) {
            if (bufferedwriter2 != null) {
                try {
                    bufferedwriter2.close();
                } catch (Throwable throwable5) {
                    throwable4.addSuppressed(throwable5);
                }
            }

            throw throwable4;
        }

        if (bufferedwriter2 != null) {
            bufferedwriter2.close();
        }

        Path path2 = path.resolve("entity_chunks.csv");
        BufferedWriter bufferedwriter3 = Files.newBufferedWriter(path2);

        try {
            //this.entityManager.dumpSections(bufferedwriter3); // Paper - rewrite chunk system
        } catch (Throwable throwable6) {
            if (bufferedwriter3 != null) {
                try {
                    bufferedwriter3.close();
                } catch (Throwable throwable7) {
                    throwable6.addSuppressed(throwable7);
                }
            }

            throw throwable6;
        }

        if (bufferedwriter3 != null) {
            bufferedwriter3.close();
        }

        Path path3 = path.resolve("entities.csv");
        BufferedWriter bufferedwriter4 = Files.newBufferedWriter(path3);

        try {
            ServerLevel.dumpEntities(bufferedwriter4, this.getEntities().getAll());
        } catch (Throwable throwable8) {
            if (bufferedwriter4 != null) {
                try {
                    bufferedwriter4.close();
                } catch (Throwable throwable9) {
                    throwable8.addSuppressed(throwable9);
                }
            }

            throw throwable8;
        }

        if (bufferedwriter4 != null) {
            bufferedwriter4.close();
        }

        Path path4 = path.resolve("block_entities.csv");
        BufferedWriter bufferedwriter5 = Files.newBufferedWriter(path4);

        try {
            this.dumpBlockEntityTickers(bufferedwriter5);
        } catch (Throwable throwable10) {
            if (bufferedwriter5 != null) {
                try {
                    bufferedwriter5.close();
                } catch (Throwable throwable11) {
                    throwable10.addSuppressed(throwable11);
                }
            }

            throw throwable10;
        }

        if (bufferedwriter5 != null) {
            bufferedwriter5.close();
        }

    }

    private static void dumpEntities(Writer writer, Iterable<Entity> entities) throws IOException {
        CsvOutput csvwriter = CsvOutput.builder().addColumn("x").addColumn("y").addColumn("z").addColumn("uuid").addColumn("type").addColumn("alive").addColumn("display_name").addColumn("custom_name").build(writer);
        Iterator iterator = entities.iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();
            Component ichatbasecomponent = entity.getCustomName();
            Component ichatbasecomponent1 = entity.getDisplayName();

            csvwriter.writeRow(entity.getX(), entity.getY(), entity.getZ(), entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), entity.isAlive(), ichatbasecomponent1.getString(), ichatbasecomponent != null ? ichatbasecomponent.getString() : null);
        }

    }

    private void dumpBlockEntityTickers(Writer writer) throws IOException {
        CsvOutput csvwriter = CsvOutput.builder().addColumn("x").addColumn("y").addColumn("z").addColumn("type").build(writer);
        Iterator iterator = null; // Folia - region threading

        while (iterator.hasNext()) {
            TickingBlockEntity tickingblockentity = (TickingBlockEntity) iterator.next();
            BlockPos blockposition = tickingblockentity.getPos();

            csvwriter.writeRow(blockposition.getX(), blockposition.getY(), blockposition.getZ(), tickingblockentity.getType());
        }

    }

    @VisibleForTesting
    public void clearBlockEvents(BoundingBox box) {
        this.getCurrentWorldData().removeIfBlockEvents((blockactiondata) -> { // Folia - regionised ticking
            return box.isInside(blockactiondata.pos());
        });
    }

    @Override
    public void blockUpdated(BlockPos pos, Block block) {
        if (!this.isDebug()) {
            // CraftBukkit start
            if (this.getCurrentWorldData().populating) { // Folia - region threading
                return;
            }
            // CraftBukkit end
            this.updateNeighborsAt(pos, block);
        }

    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return 1.0F;
    }

    public Iterable<Entity> getAllEntities() {
        return this.getEntities().getAll();
    }

    public String toString() {
        return "ServerLevel[" + this.serverLevelData.getLevelName() + "]";
    }

    public boolean isFlat() {
        return this.serverLevelData.isFlatWorld(); // CraftBukkit
    }

    @Override
    public long getSeed() {
        return this.serverLevelData.worldGenOptions().seed(); // CraftBukkit
    }

    @Nullable
    public EndDragonFight getDragonFight() {
        return this.dragonFight;
    }

    @Override
    public ServerLevel getLevel() {
        return this;
    }

    @VisibleForTesting
    public String getWatchdogStats() {
        return "region threading"; // Folia - region threading
    }

    private static <T> String getTypeCount(Iterable<T> items, Function<T, String> classifier) {
        try {
            Object2IntOpenHashMap<String> object2intopenhashmap = new Object2IntOpenHashMap();
            Iterator<T> iterator = items.iterator(); // CraftBukkit - decompile error

            while (iterator.hasNext()) {
                T t0 = iterator.next();
                String s = (String) classifier.apply(t0);

                object2intopenhashmap.addTo(s, 1);
            }

            return (String) object2intopenhashmap.object2IntEntrySet().stream().sorted(Comparator.comparing(Entry<String>::getIntValue).reversed()).limit(5L).map((entry) -> { // CraftBukkit - decompile error
                String s1 = (String) entry.getKey();

                return s1 + ":" + entry.getIntValue();
            }).collect(Collectors.joining(","));
        } catch (Exception exception) {
            return "";
        }
    }

    @Override
    public LevelEntityGetter<Entity> getEntities() {
        org.spigotmc.AsyncCatcher.catchOp("Chunk getEntities call"); // Spigot
        return this.moonrise$getEntityLookup(); // Paper - rewrite chunk system
    }

    public void addLegacyChunkEntities(Stream<Entity> entities) {
        // Paper start - add chunkpos param
        this.addLegacyChunkEntities(entities, null);
    }
    public void addLegacyChunkEntities(Stream<Entity> entities, ChunkPos chunkPos) {
        // Paper end - add chunkpos param
        this.moonrise$getEntityLookup().addLegacyChunkEntities(entities.toList(), chunkPos); // Paper - rewrite chunk system
    }

    public void addWorldGenChunkEntities(Stream<Entity> entities) {
        // Paper start - add chunkpos param
        this.addWorldGenChunkEntities(entities, null);
    }
    public void addWorldGenChunkEntities(Stream<Entity> entities, ChunkPos chunkPos) {
        // Paper end - add chunkpos param
        this.moonrise$getEntityLookup().addWorldGenChunkEntities(entities.toList(), chunkPos); // Paper - rewrite chunk system
    }

    public void startTickingChunk(LevelChunk chunk) {
        chunk.unpackTicks(this.getRedstoneGameTime()); // Folia - region threading
    }

    public void onStructureStartsAvailable(ChunkAccess chunk) {
        // Folia start - region threading
        // no longer needs to be on main
        this.structureCheck.onStructureLoad(chunk.getPos(), chunk.getAllStarts());
        // Folia end - region threading
    }

    public PathTypeCache getPathTypeCache() {
        return this.getCurrentWorldData().pathTypesByPosCache; // Folia - region threading
    }

    @Override
    public void close() throws IOException {
        super.close();
        // Paper - rewrite chunk system
    }

    @Override
    public String gatherChunkSourceStats() {
        String s = this.chunkSource.gatherStats();

        return "Chunks[S] W: " + s + " E: " + this.moonrise$getEntityLookup().getDebugInfo(); // Paper - rewrite chunk system
    }

    public boolean areEntitiesLoaded(long chunkPos) {
        return this.moonrise$getAnyChunkIfLoaded(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(chunkPos), ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(chunkPos)) != null; // Paper - rewrite chunk system
    }

    public boolean isPositionTickingWithEntitiesLoaded(long chunkPos) { // Folia - region threaded - make public
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(chunkPos);
        // isTicking implies the chunk is loaded, and the chunk is loaded now implies the entities are loaded
        return chunkHolder != null && chunkHolder.isTickingReady();
        // Paper end - rewrite chunk system
    }

    public boolean isPositionEntityTicking(BlockPos pos) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(pos));
        return chunkHolder != null && chunkHolder.isEntityTickingReady();
        // Paper end - rewrite chunk system
    }

    public boolean isNaturalSpawningAllowed(BlockPos pos) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(pos));
        return chunkHolder != null && chunkHolder.isEntityTickingReady();
        // Paper end - rewrite chunk system
    }

    public boolean isNaturalSpawningAllowed(ChunkPos pos) {
        // Paper start - rewrite chunk system
        final ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder chunkHolder = this.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(pos));
        return chunkHolder != null && chunkHolder.isEntityTickingReady();
        // Paper end - rewrite chunk system
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.server.getWorldData().enabledFeatures();
    }

    @Override
    public PotionBrewing potionBrewing() {
        return this.server.potionBrewing();
    }

    @Override
    public FuelValues fuelValues() {
        return this.server.fuelValues();
    }

    public RandomSource getRandomSequence(ResourceLocation id) {
        return this.randomSequences.get(id);
    }

    public RandomSequences getRandomSequences() {
        return this.randomSequences;
    }

    public GameRules getGameRules() {
        return this.serverLevelData.getGameRules();
    }

    // Paper start - respect global sound events gamerule
    public List<net.minecraft.server.level.ServerPlayer> getPlayersForGlobalSoundGamerule() {
        return this.getGameRules().getBoolean(GameRules.RULE_GLOBAL_SOUND_EVENTS) ? ((ServerLevel) this).getServer().getPlayerList().players : ((ServerLevel) this).players();
    }

    public double getGlobalSoundRangeSquared(java.util.function.Function<org.spigotmc.SpigotWorldConfig, Integer> rangeFunction) {
        final double range = rangeFunction.apply(this.spigotConfig);
        return range <= 0 ? 64.0 * 64.0 : range * range; // 64 is taken from default in ServerLevel#levelEvent
    }
    // Paper end - respect global sound events gamerule
    // Paper start - notify observers even if grow failed
    public void checkCapturedTreeStateForObserverNotify(final BlockPos pos, final org.bukkit.craftbukkit.block.CraftBlockState craftBlockState) {
        // notify observers if the block state is the same and the Y level equals the original y level (for mega trees)
        // blocks at the same Y level with the same state can be assumed to be saplings which trigger observers regardless of if the
        // tree grew or not
        if (craftBlockState.getPosition().getY() == pos.getY() && this.getBlockState(craftBlockState.getPosition()) == craftBlockState.getHandle()) {
            this.notifyAndUpdatePhysics(craftBlockState.getPosition(), null, craftBlockState.getHandle(), craftBlockState.getHandle(), craftBlockState.getHandle(), craftBlockState.getFlag(), 512);
        }
    }
    // Paper end - notify observers even if grow failed

    @Override
    public CrashReportCategory fillReportDetails(CrashReport report) {
        CrashReportCategory crashreportsystemdetails = super.fillReportDetails(report);

        crashreportsystemdetails.setDetail("Loaded entity count", () -> {
            return String.valueOf(this.moonrise$getEntityLookup().getEntityCount()); // Paper - rewrite chunk system
        });
        return crashreportsystemdetails;
    }

    @Override
    public int getSeaLevel() {
        return this.chunkSource.getGenerator().getSeaLevel();
    }

    // Paper start - optimize redstone (Alternate Current)
    @Override
    public alternate.current.wire.WireHandler getWireHandler() {
        return this.getCurrentWorldData().wireHandler; // Folia - region threading
    }
    // Paper end - optimize redstone (Alternate Current)

    private final class EntityCallbacks implements LevelCallback<Entity> {

        EntityCallbacks() {}

        public void onCreated(Entity entity) {}

        public void onDestroyed(Entity entity) {
            // ServerLevel.this.getScoreboard().entityRemoved(entity); // Folia - region threading
        }

        public void onTickingStart(Entity entity) {
            if (entity instanceof net.minecraft.world.entity.Marker && !paperConfig().entities.markers.tick) return; // Paper - Configurable marker ticking
            ServerLevel.this.getCurrentWorldData().addEntityTickingEntity(entity); // Folia - region threading
        }

        public void onTickingEnd(Entity entity) {
            ServerLevel.this.getCurrentWorldData().removeEntityTickingEntity(entity); // Folia - region threading
            // Paper start - Reset pearls when they stop being ticked
            if (ServerLevel.this.paperConfig().fixes.disableUnloadedChunkEnderpearlExploit && ServerLevel.this.paperConfig().misc.legacyEnderPearlBehavior && entity instanceof net.minecraft.world.entity.projectile.ThrownEnderpearl pearl) {
                pearl.cachedOwner = null;
                pearl.ownerUUID = null;
            }
            // Paper end - Reset pearls when they stop being ticked
        }

        public void onTrackingStart(Entity entity) {
            org.spigotmc.AsyncCatcher.catchOp("entity register"); // Spigot
            ServerLevel.this.getCurrentWorldData().addLoadedEntity(entity); // Folia - region threading
            // ServerLevel.this.getChunkSource().addEntity(entity); // Paper - ignore and warn about illegal addEntity calls instead of crashing server; moved down below valid=true
            if (entity instanceof ServerPlayer entityplayer) {
                ServerLevel.this.players.add(entityplayer);
                ServerLevel.this.updateSleepingPlayerList();
            }

            if (entity instanceof Mob entityinsentient) {
                if (false && ServerLevel.this.isUpdatingNavigations) { // Paper - Remove unnecessary onTrackingStart during navigation warning
                    String s = "onTrackingStart called during navigation iteration";

                    Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration"));
                }

                ServerLevel.this.getCurrentWorldData().addNavigatingMob(entityinsentient); // Folia - region threading
            }

            if (entity instanceof EnderDragon entityenderdragon) {
                EnderDragonPart[] aentitycomplexpart = entityenderdragon.getSubEntities();
                int i = aentitycomplexpart.length;

                for (int j = 0; j < i; ++j) {
                    EnderDragonPart entitycomplexpart = aentitycomplexpart[j];

                    synchronized (ServerLevel.this.dragonParts) { // Folia - region threading
                    ServerLevel.this.dragonParts.put(entitycomplexpart.getId(), entitycomplexpart);
                    } // Folia - region threading
                }
            }

            entity.updateDynamicGameEventListener(DynamicGameEventListener::add);
            entity.inWorld = true; // CraftBukkit - Mark entity as in world
            entity.valid = true; // CraftBukkit
            ServerLevel.this.getChunkSource().addEntity(entity); // Paper - ignore and warn about illegal addEntity calls instead of crashing server
            // Paper start - Entity origin API
            if (entity.getOriginVector() == null) {
                entity.setOrigin(entity.getBukkitEntity().getLocation());
            }
            // Default to current world if unknown, gross assumption but entities rarely change world
            if (entity.getOriginWorld() == null) {
                entity.setOrigin(entity.getOriginVector().toLocation(getWorld()));
            }
            // Paper end - Entity origin API
            new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(entity.getBukkitEntity(), ServerLevel.this.getWorld()).callEvent(); // Paper - fire while valid
        }

        public void onTrackingEnd(Entity entity) {
            org.spigotmc.AsyncCatcher.catchOp("entity unregister"); // Spigot
            ServerLevel.this.getCurrentWorldData().removeLoadedEntity(entity);
            // Spigot start
            if ( entity instanceof Player )
            {
                com.google.common.collect.Streams.stream( ServerLevel.this.getServer().getAllLevels() ).map( ServerLevel::getDataStorage ).forEach( (worldData) ->
                {
                    // Folia start - make map data thread-safe
                    List<Object> worldDataCache;
                    synchronized (worldData.cache) {
                        worldDataCache = new java.util.ArrayList<>(worldData.cache.values());
                    }
                    for (Object o : worldDataCache )
                    // Folia end - make map data thread-safe
                    {
                        if ( o instanceof MapItemSavedData )
                        {
                            MapItemSavedData map = (MapItemSavedData) o;
                            synchronized (map) { // Folia - make map data thread-safe
                            map.carriedByPlayers.remove( (Player) entity );
                            for ( Iterator<MapItemSavedData.HoldingPlayer> iter = (Iterator<MapItemSavedData.HoldingPlayer>) map.carriedBy.iterator(); iter.hasNext(); )
                            {
                                if ( iter.next().player == entity )
                                {
                                    map.decorations.remove(entity.getName().getString()); // Paper
                                    iter.remove();
                                }
                            }
                            } // Folia - make map data thread-safe
                        }
                    }
                } );
            }
            // Spigot end
            // Spigot Start
            if (entity.getBukkitEntity() instanceof org.bukkit.inventory.InventoryHolder && (!(entity instanceof ServerPlayer) || entity.getRemovalReason() != Entity.RemovalReason.KILLED)) { // SPIGOT-6876: closeInventory clears death message
                // Paper start - Fix merchant inventory not closing on entity removal
                if (entity.getBukkitEntity() instanceof org.bukkit.inventory.Merchant merchant && merchant.getTrader() != null) {
                    merchant.getTrader().closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED);
                }
                // Paper end - Fix merchant inventory not closing on entity removal
                for (org.bukkit.entity.HumanEntity h : Lists.newArrayList(((org.bukkit.inventory.InventoryHolder) entity.getBukkitEntity()).getInventory().getViewers())) {
                    h.closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper - Inventory close reason
                }
            }
            // Spigot End
            ServerLevel.this.getChunkSource().removeEntity(entity);
            if (entity instanceof ServerPlayer entityplayer) {
                ServerLevel.this.players.remove(entityplayer);
                ServerLevel.this.updateSleepingPlayerList();
            }

            if (entity instanceof Mob entityinsentient) {
                if (false && ServerLevel.this.isUpdatingNavigations) { // Paper - Remove unnecessary onTrackingStart during navigation warning
                    String s = "onTrackingStart called during navigation iteration";

                    Util.logAndPauseIfInIde("onTrackingStart called during navigation iteration", new IllegalStateException("onTrackingStart called during navigation iteration"));
                }

                ServerLevel.this.getCurrentWorldData().removeNavigatingMob(entityinsentient); // Folia - region threading
            }

            if (entity instanceof EnderDragon entityenderdragon) {
                EnderDragonPart[] aentitycomplexpart = entityenderdragon.getSubEntities();
                int i = aentitycomplexpart.length;

                for (int j = 0; j < i; ++j) {
                    EnderDragonPart entitycomplexpart = aentitycomplexpart[j];

                    synchronized (ServerLevel.this.dragonParts) { // Folia - region threading
                    ServerLevel.this.dragonParts.remove(entitycomplexpart.getId());
                    } // Folia - region threading
                }
            }

            entity.updateDynamicGameEventListener(DynamicGameEventListener::remove);
            // CraftBukkit start
            entity.valid = false;
            // Folia - region threading - TODO THIS SHIT
            if (!(entity instanceof ServerPlayer)) {
                for (ServerPlayer player : ServerLevel.this.server.getPlayerList().players) { // Paper - call onEntityRemove for all online players
                    player.getBukkitEntity().onEntityRemove(entity);
                }
            }
            // CraftBukkit end
            new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(entity.getBukkitEntity(), ServerLevel.this.getWorld()).callEvent(); // Paper - fire while valid
        }

        public void onSectionChange(Entity entity) {
            entity.updateDynamicGameEventListener(DynamicGameEventListener::move);
        }
    }

    // Paper start - check global player list where appropriate
    @Override
    @Nullable
    public Player getGlobalPlayerByUUID(UUID uuid) {
        return this.server.getPlayerList().getPlayer(uuid);
    }
    // Paper end - check global player list where appropriate
}
