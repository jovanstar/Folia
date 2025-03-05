package net.minecraft.world.level.chunk.storage;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import javax.annotation.Nullable;
import net.minecraft.Optionull;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.levelgen.BelowZeroRetrogen;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.blending.BlendingData;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ProtoChunkTicks;
import net.minecraft.world.ticks.SavedTick;
import org.slf4j.Logger;

// CraftBukkit - persistentDataContainer
public record SerializableChunkData(Registry<Biome> biomeRegistry, ChunkPos chunkPos, int minSectionY, long lastUpdateTime, long inhabitedTime, ChunkStatus chunkStatus, @Nullable BlendingData.Packed blendingData, @Nullable BelowZeroRetrogen belowZeroRetrogen, UpgradeData upgradeData, @Nullable long[] carvingMask, Map<Heightmap.Types, long[]> heightmaps, ChunkAccess.PackedTicks packedTicks, ShortList[] postProcessingSections, boolean lightCorrect, List<SerializableChunkData.SectionData> sectionData, List<CompoundTag> entities, List<CompoundTag> blockEntities, CompoundTag structureData, @Nullable Tag persistentDataContainer) {

    public static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState(), null); // Paper start - Anti-Xray
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_UPGRADE_DATA = "UpgradeData";
    private static final String BLOCK_TICKS_TAG = "block_ticks";
    private static final String FLUID_TICKS_TAG = "fluid_ticks";
    public static final String X_POS_TAG = "xPos";
    public static final String Z_POS_TAG = "zPos";
    public static final String HEIGHTMAPS_TAG = "Heightmaps";
    public static final String IS_LIGHT_ON_TAG = "isLightOn";
    public static final String SECTIONS_TAG = "sections";
    public static final String BLOCK_LIGHT_TAG = "BlockLight";
    public static final String SKY_LIGHT_TAG = "SkyLight";
    // Paper start - guard against serializing mismatching coordinates
    // TODO Note: This needs to be re-checked each update
    public static ChunkPos getChunkCoordinate(final CompoundTag chunkData) {
        final int dataVersion = ChunkStorage.getVersion(chunkData);
        if (dataVersion < 2842) { // Level tag is removed after this version
            final CompoundTag levelData = chunkData.getCompound("Level");
            return new ChunkPos(levelData.getInt("xPos"), levelData.getInt("zPos"));
        } else {
            return new ChunkPos(chunkData.getInt("xPos"), chunkData.getInt("zPos"));
        }
    }
    // Paper end - guard against serializing mismatching coordinates
    // Paper start - Attempt to recalculate regionfile header if it is corrupt
    // TODO: Check on update
    public static long getLastWorldSaveTime(final CompoundTag chunkData) {
        final int dataVersion = ChunkStorage.getVersion(chunkData);
        if (dataVersion < 2842) { // Level tag is removed after this version
            final CompoundTag levelData = chunkData.getCompound("Level");
            return levelData.getLong("LastUpdate");
        } else {
            return chunkData.getLong("LastUpdate");
        }
    }
    // Paper end - Attempt to recalculate regionfile header if it is corrupt

    // Paper start - Do not let the server load chunks from newer versions
    private static final int CURRENT_DATA_VERSION = net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();
    private static final boolean JUST_CORRUPT_IT = Boolean.getBoolean("Paper.ignoreWorldDataVersion");
    // Paper end - Do not let the server load chunks from newer versions

    @Nullable
    public static SerializableChunkData parse(LevelHeightAccessor world, RegistryAccess registryManager, CompoundTag nbt) {
        net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) world; // Paper - Anti-Xray This is is seemingly only called from ChunkMap, where, we have a server level. We'll fight this later if needed.
        if (!nbt.contains("Status", 8)) {
            return null;
        } else {
            // Paper start - Do not let the server load chunks from newer versions
            if (nbt.contains("DataVersion", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
                final int dataVersion = nbt.getInt("DataVersion");
                if (!JUST_CORRUPT_IT && dataVersion > CURRENT_DATA_VERSION) {
                    new RuntimeException("Server attempted to load chunk saved with newer version of minecraft! " + dataVersion + " > " + CURRENT_DATA_VERSION).printStackTrace();
                    System.exit(1);
                }
            }
            // Paper end - Do not let the server load chunks from newer versions
            ChunkPos chunkcoordintpair = new ChunkPos(nbt.getInt("xPos"), nbt.getInt("zPos")); // Paper - guard against serializing mismatching coordinates; diff on change, see ChunkSerializer#getChunkCoordinate
            long i = nbt.getLong("LastUpdate");
            long j = nbt.getLong("InhabitedTime");
            ChunkStatus chunkstatus = ChunkStatus.byName(nbt.getString("Status"));
            UpgradeData chunkconverter = nbt.contains("UpgradeData", 10) ? new UpgradeData(nbt.getCompound("UpgradeData"), world) : UpgradeData.EMPTY;
            boolean flag = chunkstatus.isOrAfter(ChunkStatus.LIGHT) && (nbt.get("isLightOn") != null && nbt.getInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_VERSION_TAG) == ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_LIGHT_VERSION); // Paper - starlight
            DataResult dataresult;
            Logger logger;
            BlendingData.Packed blendingdata_d;

            if (nbt.contains("blending_data", 10)) {
                dataresult = BlendingData.Packed.CODEC.parse(NbtOps.INSTANCE, nbt.getCompound("blending_data"));
                logger = SerializableChunkData.LOGGER;
                Objects.requireNonNull(logger);
                blendingdata_d = (BlendingData.Packed) ((DataResult<BlendingData.Packed>) dataresult).resultOrPartial(logger::error).orElse(null); // CraftBukkit - decompile error
            } else {
                blendingdata_d = null;
            }

            BelowZeroRetrogen belowzeroretrogen;

            if (nbt.contains("below_zero_retrogen", 10)) {
                dataresult = BelowZeroRetrogen.CODEC.parse(NbtOps.INSTANCE, nbt.getCompound("below_zero_retrogen"));
                logger = SerializableChunkData.LOGGER;
                Objects.requireNonNull(logger);
                belowzeroretrogen = (BelowZeroRetrogen) ((DataResult<BelowZeroRetrogen>) dataresult).resultOrPartial(logger::error).orElse(null); // CraftBukkit - decompile error
            } else {
                belowzeroretrogen = null;
            }

            long[] along;

            if (nbt.contains("carving_mask", 12)) {
                along = nbt.getLongArray("carving_mask");
            } else {
                along = null;
            }

            CompoundTag nbttagcompound1 = nbt.getCompound("Heightmaps");
            Map<Heightmap.Types, long[]> map = new EnumMap(Heightmap.Types.class);
            Iterator iterator = chunkstatus.heightmapsAfter().iterator();

            while (iterator.hasNext()) {
                Heightmap.Types heightmap_type = (Heightmap.Types) iterator.next();
                String s = heightmap_type.getSerializationKey();

                if (nbttagcompound1.contains(s, 12)) {
                    map.put(heightmap_type, nbttagcompound1.getLongArray(s));
                }
            }

            List<SavedTick<Block>> list = SavedTick.loadTickList(nbt.getList("block_ticks", 10), (s1) -> {
                return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(s1));
            }, chunkcoordintpair);
            List<SavedTick<Fluid>> list1 = SavedTick.loadTickList(nbt.getList("fluid_ticks", 10), (s1) -> {
                return BuiltInRegistries.FLUID.getOptional(ResourceLocation.tryParse(s1));
            }, chunkcoordintpair);
            ChunkAccess.PackedTicks ichunkaccess_a = new ChunkAccess.PackedTicks(list, list1);
            ListTag nbttaglist = nbt.getList("PostProcessing", 9);
            ShortList[] ashortlist = new ShortList[nbttaglist.size()];

            for (int k = 0; k < nbttaglist.size(); ++k) {
                ListTag nbttaglist1 = nbttaglist.getList(k);
                ShortArrayList shortarraylist = new ShortArrayList(nbttaglist1.size());

                for (int l = 0; l < nbttaglist1.size(); ++l) {
                    shortarraylist.add(nbttaglist1.getShort(l));
                }

                ashortlist[k] = shortarraylist;
            }

            List<CompoundTag> list2 = Lists.transform(nbt.getList("entities", 10), (nbtbase) -> {
                return (CompoundTag) nbtbase;
            });
            List<CompoundTag> list3 = Lists.transform(nbt.getList("block_entities", 10), (nbtbase) -> {
                return (CompoundTag) nbtbase;
            });
            CompoundTag nbttagcompound2 = nbt.getCompound("structures");
            ListTag nbttaglist2 = nbt.getList("sections", 10);
            List<SerializableChunkData.SectionData> list4 = new ArrayList(nbttaglist2.size());
            Registry<Biome> iregistry = registryManager.lookupOrThrow(Registries.BIOME);
            Codec<PalettedContainer<Holder<Biome>>> codec = makeBiomeCodecRW(iregistry); // CraftBukkit - read/write

            for (int i1 = 0; i1 < nbttaglist2.size(); ++i1) {
                CompoundTag nbttagcompound3 = nbttaglist2.getCompound(i1); final CompoundTag sectionData = nbttagcompound3; // Paper - Anti-Xray - OBFHELPER
                byte b0 = nbttagcompound3.getByte("Y");
                LevelChunkSection chunksection;

                if (b0 >= world.getMinSectionY() && b0 <= world.getMaxSectionY()) {
                    PalettedContainer datapaletteblock;
                    // Paper start - Anti-Xray - Add preset block states
                    BlockState[] presetBlockStates = serverLevel.chunkPacketBlockController.getPresetBlockStates(serverLevel, chunkcoordintpair, b0);


                    if (nbttagcompound3.contains("block_states", 10)) {
                        Codec<PalettedContainer<BlockState>> blockStateCodec = presetBlockStates == null ? BLOCK_STATE_CODEC : PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState(), presetBlockStates); // Paper - Anti-Xray
                        datapaletteblock = blockStateCodec.parse(NbtOps.INSTANCE, sectionData.getCompound("block_states")).promotePartial((s1) -> { // Paper - Anti-Xray
                            logErrors(chunkcoordintpair, b0, s1);
                        }).getOrThrow(SerializableChunkData.ChunkReadException::new);
                    } else {
                        datapaletteblock = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES, presetBlockStates); // Paper - Anti-Xray
                    }

                    PalettedContainer object; // CraftBukkit - read/write

                    if (nbttagcompound3.contains("biomes", 10)) {
                        object = codec.parse(NbtOps.INSTANCE, nbttagcompound3.getCompound("biomes")).promotePartial((s1) -> { // CraftBukkit - read/write
                            logErrors(chunkcoordintpair, b0, s1);
                        }).getOrThrow(SerializableChunkData.ChunkReadException::new);
                    } else {
                        object = new PalettedContainer<>(iregistry.asHolderIdMap(), iregistry.getOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES, null);  // Paper - Anti-Xray - Add preset biomes
                    }

                    chunksection = new LevelChunkSection(datapaletteblock, (PalettedContainer) object); // CraftBukkit - read/write
                } else {
                    chunksection = null;
                }

                DataLayer nibblearray = nbttagcompound3.contains("BlockLight", 7) ? new DataLayer(nbttagcompound3.getByteArray("BlockLight")) : null;
                DataLayer nibblearray1 = nbttagcompound3.contains("SkyLight", 7) ? new DataLayer(nbttagcompound3.getByteArray("SkyLight")) : null;

                // Paper start - starlight
                SerializableChunkData.SectionData serializableChunkData = new SerializableChunkData.SectionData(b0, chunksection, nibblearray, nibblearray1);
                if (sectionData.contains(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.BLOCKLIGHT_STATE_TAG, Tag.TAG_ANY_NUMERIC)) {
                    ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)serializableChunkData).starlight$setBlockLightState(sectionData.getInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.BLOCKLIGHT_STATE_TAG));
                }

                if (sectionData.contains(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.SKYLIGHT_STATE_TAG, Tag.TAG_ANY_NUMERIC)) {
                    ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)serializableChunkData).starlight$setSkyLightState(sectionData.getInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.SKYLIGHT_STATE_TAG));
                }
                list4.add(serializableChunkData);
                // Paper end - starlight
            }

            // CraftBukkit - ChunkBukkitValues
            return new SerializableChunkData(iregistry, chunkcoordintpair, world.getMinSectionY(), i, j, chunkstatus, blendingdata_d, belowzeroretrogen, chunkconverter, along, map, ichunkaccess_a, ashortlist, flag, list4, list2, list3, nbttagcompound2, nbt.get("ChunkBukkitValues"));
        }
    }

    // Paper start - starlight
    private ProtoChunk loadStarlightLightData(final ServerLevel world, final ProtoChunk ret) {

        final boolean hasSkyLight = world.dimensionType().hasSkyLight();
        final int minSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinLightSection(world);

        final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[] blockNibbles = ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine.getFilledEmptyLight(world);
        final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[] skyNibbles = ca.spottedleaf.moonrise.patches.starlight.light.StarLightEngine.getFilledEmptyLight(world);

        if (!this.lightCorrect) {
            ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)ret).starlight$setBlockNibbles(blockNibbles);
            ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)ret).starlight$setSkyNibbles(skyNibbles);
            return ret;
        }

        try {
            for (final SerializableChunkData.SectionData sectionData : this.sectionData) {
                final int y = sectionData.y();
                final DataLayer blockLight = sectionData.blockLight();
                final DataLayer skyLight = sectionData.skyLight();

                final int blockState = ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$getBlockLightState();
                final int skyState = ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$getSkyLightState();

                if (blockState >= 0) {
                    if (blockLight != null) {
                        blockNibbles[y - minSection] = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray(ca.spottedleaf.moonrise.common.util.MixinWorkarounds.clone(blockLight.getData()), blockState); // clone for data safety
                    } else {
                        blockNibbles[y - minSection] = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray(null, blockState);
                    }
                }

                if (skyState >= 0 && hasSkyLight) {
                    if (skyLight != null) {
                        skyNibbles[y - minSection] = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray(ca.spottedleaf.moonrise.common.util.MixinWorkarounds.clone(skyLight.getData()), skyState); // clone for data safety
                    } else {
                        skyNibbles[y - minSection] = new ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray(null, skyState);
                    }
                }
            }

            ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)ret).starlight$setBlockNibbles(blockNibbles);
            ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)ret).starlight$setSkyNibbles(skyNibbles);
        } catch (final Throwable thr) {
            ret.setLightCorrect(false);

            LOGGER.error("Failed to parse light data for chunk " + ret.getPos() + " in world '" + ca.spottedleaf.moonrise.common.util.WorldUtil.getWorldName(world) + "'", thr);
        }

        return ret;
    }
    // Paper end - starlight

    public ProtoChunk read(ServerLevel world, PoiManager poiStorage, RegionStorageInfo key, ChunkPos expectedPos) {
        if (!Objects.equals(expectedPos, this.chunkPos)) {
            SerializableChunkData.LOGGER.error("Chunk file at {} is in the wrong location; relocating. (Expected {}, got {})", new Object[]{expectedPos, expectedPos, this.chunkPos});
            world.getServer().reportMisplacedChunk(this.chunkPos, expectedPos, key);
        }

        int i = world.getSectionsCount();
        LevelChunkSection[] achunksection = new LevelChunkSection[i];
        boolean flag = world.dimensionType().hasSkyLight();
        ServerChunkCache chunkproviderserver = world.getChunkSource();
        LevelLightEngine levellightengine = chunkproviderserver.getLightEngine();
        Registry<Biome> iregistry = world.registryAccess().lookupOrThrow(Registries.BIOME);
        boolean flag1 = false;
        Iterator iterator = this.sectionData.iterator();

        while (iterator.hasNext()) {
            SerializableChunkData.SectionData serializablechunkdata_b = (SerializableChunkData.SectionData) iterator.next();
            SectionPos sectionposition = SectionPos.of(expectedPos, serializablechunkdata_b.y);

            if (serializablechunkdata_b.chunkSection != null) {
                achunksection[world.getSectionIndexFromSectionY(serializablechunkdata_b.y)] = serializablechunkdata_b.chunkSection;
                //poiStorage.checkConsistencyWithBlocks(sectionposition, serializablechunkdata_b.chunkSection); // Paper - rewrite chunk system
            }

            boolean flag2 = serializablechunkdata_b.blockLight != null;
            boolean flag3 = flag && serializablechunkdata_b.skyLight != null;

            if (flag2 || flag3) {
                if (!flag1) {
                    levellightengine.retainData(expectedPos, true);
                    flag1 = true;
                }

                if (flag2) {
                    levellightengine.queueSectionData(LightLayer.BLOCK, sectionposition, serializablechunkdata_b.blockLight);
                }

                if (flag3) {
                    levellightengine.queueSectionData(LightLayer.SKY, sectionposition, serializablechunkdata_b.skyLight);
                }
            }
        }

        ChunkType chunktype = this.chunkStatus.getChunkType();
        Object object;

        if (chunktype == ChunkType.LEVELCHUNK) {
            LevelChunkTicks<Block> levelchunkticks = new LevelChunkTicks<>(this.packedTicks.blocks());
            LevelChunkTicks<Fluid> levelchunkticks1 = new LevelChunkTicks<>(this.packedTicks.fluids());

            object = new LevelChunk(world.getLevel(), expectedPos, this.upgradeData, levelchunkticks, levelchunkticks1, this.inhabitedTime, achunksection, postLoadChunk(world, this.entities, this.blockEntities), BlendingData.unpack(this.blendingData));
        } else {
            ProtoChunkTicks<Block> protochunkticklist = ProtoChunkTicks.load(this.packedTicks.blocks());
            ProtoChunkTicks<Fluid> protochunkticklist1 = ProtoChunkTicks.load(this.packedTicks.fluids());
            ProtoChunk protochunk = new ProtoChunk(expectedPos, this.upgradeData, achunksection, protochunkticklist, protochunkticklist1, world, iregistry, BlendingData.unpack(this.blendingData));

            object = protochunk;
            protochunk.setInhabitedTime(this.inhabitedTime);
            if (this.belowZeroRetrogen != null) {
                protochunk.setBelowZeroRetrogen(this.belowZeroRetrogen);
            }

            protochunk.setPersistedStatus(this.chunkStatus);
            if (this.chunkStatus.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) {
                protochunk.setLightEngine(levellightengine);
            }
        }

        // CraftBukkit start - load chunk persistent data from nbt - SPIGOT-6814: Already load PDC here to account for 1.17 to 1.18 chunk upgrading.
        if (this.persistentDataContainer instanceof CompoundTag) {
            ((ChunkAccess) object).persistentDataContainer.putAll((CompoundTag) this.persistentDataContainer);
        }
        // CraftBukkit end

        ((ChunkAccess) object).setLightCorrect(this.lightCorrect);
        EnumSet<Heightmap.Types> enumset = EnumSet.noneOf(Heightmap.Types.class);
        Iterator iterator1 = ((ChunkAccess) object).getPersistedStatus().heightmapsAfter().iterator();

        while (iterator1.hasNext()) {
            Heightmap.Types heightmap_type = (Heightmap.Types) iterator1.next();
            long[] along = (long[]) this.heightmaps.get(heightmap_type);

            if (along != null) {
                ((ChunkAccess) object).setHeightmap(heightmap_type, along);
            } else {
                enumset.add(heightmap_type);
            }
        }

        Heightmap.primeHeightmaps((ChunkAccess) object, enumset);
        ((ChunkAccess) object).setAllStarts(unpackStructureStart(StructurePieceSerializationContext.fromLevel(world), this.structureData, world.getSeed()));
        ((ChunkAccess) object).setAllReferences(unpackStructureReferences(world.registryAccess(), expectedPos, this.structureData));

        for (int j = 0; j < this.postProcessingSections.length; ++j) {
            ((ChunkAccess) object).addPackedPostProcess(this.postProcessingSections[j], j);
        }

        if (chunktype == ChunkType.LEVELCHUNK) {
            return this.loadStarlightLightData(world, new ImposterProtoChunk((LevelChunk) object, false)); // Paper - starlight
        } else {
            ProtoChunk protochunk1 = (ProtoChunk) object;
            Iterator iterator2 = this.entities.iterator();

            CompoundTag nbttagcompound;

            while (iterator2.hasNext()) {
                nbttagcompound = (CompoundTag) iterator2.next();
                protochunk1.addEntity(nbttagcompound);
            }

            iterator2 = this.blockEntities.iterator();

            while (iterator2.hasNext()) {
                nbttagcompound = (CompoundTag) iterator2.next();
                // Paper start - do not read tile entities positioned outside the chunk
                final BlockPos blockposition = BlockEntity.getPosFromTag(nbttagcompound);
                if ((blockposition.getX() >> 4) != chunkPos.x || (blockposition.getZ() >> 4) != chunkPos.z) {
                    LOGGER.warn("Tile entity serialized in chunk {} in world '{}' positioned at {} is located outside of the chunk", chunkPos, world.getWorld().getName(), blockposition);
                    continue;
                }
                // Paper end - do not read tile entities positioned outside the chunk
                protochunk1.setBlockEntityNbt(nbttagcompound);
            }

            if (this.carvingMask != null) {
                protochunk1.setCarvingMask(new CarvingMask(this.carvingMask, ((ChunkAccess) object).getMinY()));
            }

            return this.loadStarlightLightData(world, protochunk1); // Paper - starlight
        }
    }

    private static void logErrors(ChunkPos chunkPos, int y, String message) {
        SerializableChunkData.LOGGER.error("Recoverable errors when loading section [{}, {}, {}]: {}", new Object[]{chunkPos.x, y, chunkPos.z, message});
    }

    private static Codec<PalettedContainerRO<Holder<Biome>>> makeBiomeCodec(Registry<Biome> biomeRegistry) {
        return PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getOrThrow(Biomes.PLAINS));
    }

    // CraftBukkit start - read/write
    private static Codec<PalettedContainer<Holder<Biome>>> makeBiomeCodecRW(Registry<Biome> iregistry) {
        return PalettedContainer.codecRW(iregistry.asHolderIdMap(), iregistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, iregistry.getOrThrow(Biomes.PLAINS), null); // Paper - Anti-Xray - Add preset biomes
    }
    // CraftBukkit end

    public static SerializableChunkData copyOf(ServerLevel world, ChunkAccess chunk) {
        if (!chunk.canBeSerialized()) {
            throw new IllegalArgumentException("Chunk can't be serialized: " + String.valueOf(chunk));
        } else {
            ChunkPos chunkcoordintpair = chunk.getPos();
            List<SerializableChunkData.SectionData> list = new ArrayList(); final List<SerializableChunkData.SectionData> sections = list; // Paper - starlight - OBFHELPER
            LevelChunkSection[] achunksection = chunk.getSections();
            ThreadedLevelLightEngine lightenginethreaded = world.getChunkSource().getLightEngine();

            // Paper start - starlight
            final int minLightSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinLightSection(world);
            final int maxLightSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMaxLightSection(world);
            final int minBlockSection = ca.spottedleaf.moonrise.common.util.WorldUtil.getMinSection(world);

            final LevelChunkSection[] chunkSections = chunk.getSections();
            final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[] blockNibbles = ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)chunk).starlight$getBlockNibbles();
            final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray[] skyNibbles = ((ca.spottedleaf.moonrise.patches.starlight.chunk.StarlightChunk)chunk).starlight$getSkyNibbles();

            for (int lightSection = minLightSection; lightSection <= maxLightSection; ++lightSection) {
                final int lightSectionIdx = lightSection - minLightSection;
                final int blockSectionIdx = lightSection - minBlockSection;

                final LevelChunkSection chunkSection = (blockSectionIdx >= 0 && blockSectionIdx < chunkSections.length) ? chunkSections[blockSectionIdx].copy() : null;
                final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.SaveState blockNibble = blockNibbles[lightSectionIdx].getSaveState();
                final ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray.SaveState skyNibble = skyNibbles[lightSectionIdx].getSaveState();

                if (chunkSection == null && blockNibble == null && skyNibble == null) {
                    continue;
                }

                final SerializableChunkData.SectionData sectionData = new SerializableChunkData.SectionData(
                    lightSection, chunkSection,
                    blockNibble == null ? null : (blockNibble.data == null ? null : new DataLayer(blockNibble.data)),
                    skyNibble == null ? null : (skyNibble.data == null ? null : new DataLayer(skyNibble.data))
                );

                if (blockNibble != null) {
                    ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$setBlockLightState(blockNibble.state);
                }

                if (skyNibble != null) {
                    ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$setSkyLightState(skyNibble.state);
                }

                sections.add(sectionData);
            }
            // Paper end - starlight

            List<CompoundTag> list1 = new ArrayList(chunk.getBlockEntitiesPos().size());
            Iterator iterator = chunk.getBlockEntitiesPos().iterator();

            while (iterator.hasNext()) {
                BlockPos blockposition = (BlockPos) iterator.next();
                CompoundTag nbttagcompound = chunk.getBlockEntityNbtForSaving(blockposition, world.registryAccess());

                if (nbttagcompound != null) {
                    list1.add(nbttagcompound);
                }
            }

            List<CompoundTag> list2 = new ArrayList();
            long[] along = null;

            if (chunk.getPersistedStatus().getChunkType() == ChunkType.PROTOCHUNK) {
                ProtoChunk protochunk = (ProtoChunk) chunk;

                list2.addAll(protochunk.getEntities());
                CarvingMask carvingmask = protochunk.getCarvingMask();

                if (carvingmask != null) {
                    along = carvingmask.toArray();
                }
            }

            Map<Heightmap.Types, long[]> map = new EnumMap(Heightmap.Types.class);
            Iterator iterator1 = chunk.getHeightmaps().iterator();

            while (iterator1.hasNext()) {
                Entry<Heightmap.Types, Heightmap> entry = (Entry) iterator1.next();

                if (chunk.getPersistedStatus().heightmapsAfter().contains(entry.getKey())) {
                    long[] along1 = ((Heightmap) entry.getValue()).getRawData();

                    map.put((Heightmap.Types) entry.getKey(), (long[]) along1.clone());
                }
            }

            ChunkAccess.PackedTicks ichunkaccess_a = chunk.getTicksForSerialization(world.getRedstoneGameTime()); // Folia - region threading
            ShortList[] ashortlist = (ShortList[]) Arrays.stream(chunk.getPostProcessing()).map((shortlist) -> {
                return shortlist != null ? new ShortArrayList(shortlist) : null;
            }).toArray((k) -> {
                return new ShortList[k];
            });
            CompoundTag nbttagcompound1 = packStructureData(StructurePieceSerializationContext.fromLevel(world), chunkcoordintpair, chunk.getAllStarts(), chunk.getAllReferences());

            // CraftBukkit start - store chunk persistent data in nbt
            CompoundTag persistentDataContainer = null;
            if (!chunk.persistentDataContainer.isEmpty()) { // SPIGOT-6814: Always save PDC to account for 1.17 to 1.18 chunk upgrading.
                persistentDataContainer = chunk.persistentDataContainer.toTagCompound();
            }

            return new SerializableChunkData(world.registryAccess().lookupOrThrow(Registries.BIOME), chunkcoordintpair, chunk.getMinSectionY(), world.getGameTime(), chunk.getInhabitedTime(), chunk.getPersistedStatus(), (BlendingData.Packed) Optionull.map(chunk.getBlendingData(), BlendingData::pack), chunk.getBelowZeroRetrogen(), chunk.getUpgradeData().copy(), along, map, ichunkaccess_a, ashortlist, chunk.isLightCorrect(), list, list2, list1, nbttagcompound1, persistentDataContainer);
            // CraftBukkit end
        }
    }

    public CompoundTag write() {
        CompoundTag nbttagcompound = NbtUtils.addCurrentDataVersion(new CompoundTag());

        nbttagcompound.putInt("xPos", this.chunkPos.x);
        nbttagcompound.putInt("yPos", this.minSectionY);
        nbttagcompound.putInt("zPos", this.chunkPos.z);
        nbttagcompound.putLong("LastUpdate", this.lastUpdateTime); // Paper - Diff on change
        nbttagcompound.putLong("InhabitedTime", this.inhabitedTime);
        nbttagcompound.putString("Status", BuiltInRegistries.CHUNK_STATUS.getKey(this.chunkStatus).toString());
        DataResult<Tag> dataresult; // CraftBukkit - decompile error
        Logger logger;

        if (this.blendingData != null) {
            dataresult = BlendingData.Packed.CODEC.encodeStart(NbtOps.INSTANCE, this.blendingData);
            logger = SerializableChunkData.LOGGER;
            Objects.requireNonNull(logger);
            dataresult.resultOrPartial(logger::error).ifPresent((nbtbase) -> {
                nbttagcompound.put("blending_data", nbtbase);
            });
        }

        if (this.belowZeroRetrogen != null) {
            dataresult = BelowZeroRetrogen.CODEC.encodeStart(NbtOps.INSTANCE, this.belowZeroRetrogen);
            logger = SerializableChunkData.LOGGER;
            Objects.requireNonNull(logger);
            dataresult.resultOrPartial(logger::error).ifPresent((nbtbase) -> {
                nbttagcompound.put("below_zero_retrogen", nbtbase);
            });
        }

        if (!this.upgradeData.isEmpty()) {
            nbttagcompound.put("UpgradeData", this.upgradeData.write());
        }

        ListTag nbttaglist = new ListTag();
        Codec<PalettedContainerRO<Holder<Biome>>> codec = makeBiomeCodec(this.biomeRegistry);
        Iterator iterator = this.sectionData.iterator();

        while (iterator.hasNext()) {
            SerializableChunkData.SectionData serializablechunkdata_b = (SerializableChunkData.SectionData) iterator.next(); final SerializableChunkData.SectionData sectionData = serializablechunkdata_b; // Paper - starlight - OBFHELPER
            CompoundTag nbttagcompound1 = new CompoundTag(); final CompoundTag sectionNBT = nbttagcompound1; // Paper - starlight - OBFHELPER
            LevelChunkSection chunksection = serializablechunkdata_b.chunkSection;

            if (chunksection != null) {
                nbttagcompound1.put("block_states", (Tag) SerializableChunkData.BLOCK_STATE_CODEC.encodeStart(NbtOps.INSTANCE, chunksection.getStates()).getOrThrow());
                nbttagcompound1.put("biomes", (Tag) codec.encodeStart(NbtOps.INSTANCE, chunksection.getBiomes()).getOrThrow());
            }

            if (serializablechunkdata_b.blockLight != null) {
                nbttagcompound1.putByteArray("BlockLight", serializablechunkdata_b.blockLight.getData());
            }

            if (serializablechunkdata_b.skyLight != null) {
                nbttagcompound1.putByteArray("SkyLight", serializablechunkdata_b.skyLight.getData());
            }

            // Paper start - starlight
            final int blockState = ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$getBlockLightState();
            final int skyState = ((ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData)(Object)sectionData).starlight$getSkyLightState();

            if (blockState > 0) {
                sectionNBT.putInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.BLOCKLIGHT_STATE_TAG, blockState);
            }

            if (skyState > 0) {
                sectionNBT.putInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.SKYLIGHT_STATE_TAG, skyState);
            }
            // Paper end - starlight

            if (!nbttagcompound1.isEmpty()) {
                nbttagcompound1.putByte("Y", (byte) serializablechunkdata_b.y);
                nbttaglist.add(nbttagcompound1);
            }
        }

        nbttagcompound.put("sections", nbttaglist);
        if (this.lightCorrect) {
            nbttagcompound.putBoolean("isLightOn", true);
        }

        ListTag nbttaglist1 = new ListTag();

        nbttaglist1.addAll(this.blockEntities);
        nbttagcompound.put("block_entities", nbttaglist1);
        if (this.chunkStatus.getChunkType() == ChunkType.PROTOCHUNK) {
            ListTag nbttaglist2 = new ListTag();

            nbttaglist2.addAll(this.entities);
            nbttagcompound.put("entities", nbttaglist2);
            if (this.carvingMask != null) {
                nbttagcompound.putLongArray("carving_mask", this.carvingMask);
            }
        }

        saveTicks(nbttagcompound, this.packedTicks);
        nbttagcompound.put("PostProcessing", packOffsets(this.postProcessingSections));
        CompoundTag nbttagcompound2 = new CompoundTag();

        this.heightmaps.forEach((heightmap_type, along) -> {
            nbttagcompound2.put(heightmap_type.getSerializationKey(), new LongArrayTag(along));
        });
        nbttagcompound.put("Heightmaps", nbttagcompound2);
        nbttagcompound.put("structures", this.structureData);
        // CraftBukkit start - store chunk persistent data in nbt
        if (this.persistentDataContainer != null) { // SPIGOT-6814: Always save PDC to account for 1.17 to 1.18 chunk upgrading.
            nbttagcompound.put("ChunkBukkitValues", this.persistentDataContainer);
        }
        // CraftBukkit end
        // Paper start - starlight
        if (this.lightCorrect && !this.chunkStatus.isBefore(net.minecraft.world.level.chunk.status.ChunkStatus.LIGHT)) {
            // clobber vanilla value to force vanilla to relight
            nbttagcompound.putBoolean("isLightOn", false);
            // store our light version
            nbttagcompound.putInt(ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_VERSION_TAG, ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil.STARLIGHT_LIGHT_VERSION);
        }
        // Paper end - starlight
        return nbttagcompound;
    }

    private static void saveTicks(CompoundTag nbt, ChunkAccess.PackedTicks schedulers) {
        ListTag nbttaglist = new ListTag();
        Iterator iterator = schedulers.blocks().iterator();

        while (iterator.hasNext()) {
            SavedTick<Block> ticklistchunk = (SavedTick) iterator.next();

            nbttaglist.add(ticklistchunk.save((block) -> {
                return BuiltInRegistries.BLOCK.getKey(block).toString();
            }));
        }

        nbt.put("block_ticks", nbttaglist);
        ListTag nbttaglist1 = new ListTag();
        Iterator iterator1 = schedulers.fluids().iterator();

        while (iterator1.hasNext()) {
            SavedTick<Fluid> ticklistchunk1 = (SavedTick) iterator1.next();

            nbttaglist1.add(ticklistchunk1.save((fluidtype) -> {
                return BuiltInRegistries.FLUID.getKey(fluidtype).toString();
            }));
        }

        nbt.put("fluid_ticks", nbttaglist1);
    }

    public static ChunkType getChunkTypeFromTag(@Nullable CompoundTag nbt) {
        return nbt != null ? ChunkStatus.byName(nbt.getString("Status")).getChunkType() : ChunkType.PROTOCHUNK;
    }

    @Nullable
    private static LevelChunk.PostLoadProcessor postLoadChunk(ServerLevel world, List<CompoundTag> entities, List<CompoundTag> blockEntities) {
        return entities.isEmpty() && blockEntities.isEmpty() ? null : (chunk) -> {
            if (!entities.isEmpty()) {
                world.addLegacyChunkEntities(EntityType.loadEntitiesRecursive(entities, world, EntitySpawnReason.LOAD));
            }

            Iterator iterator = blockEntities.iterator();

            while (iterator.hasNext()) {
                CompoundTag nbttagcompound = (CompoundTag) iterator.next();
                boolean flag = nbttagcompound.getBoolean("keepPacked");

                if (flag) {
                    chunk.setBlockEntityNbt(nbttagcompound);
                } else {
                    BlockPos blockposition = BlockEntity.getPosFromTag(nbttagcompound);
                    // Paper start - do not read tile entities positioned outside the chunk
                    ChunkPos chunkPos = chunk.getPos();
                    if ((blockposition.getX() >> 4) != chunkPos.x || (blockposition.getZ() >> 4) != chunkPos.z) {
                        LOGGER.warn("Tile entity serialized in chunk " + chunkPos + " in world '" + world.getWorld().getName() + "' positioned at " + blockposition + " is located outside of the chunk");
                        continue;
                    }
                    // Paper end - do not read tile entities positioned outside the chunk
                    BlockEntity tileentity = BlockEntity.loadStatic(blockposition, chunk.getBlockState(blockposition), nbttagcompound, world.registryAccess());

                    if (tileentity != null) {
                        chunk.setBlockEntity(tileentity);
                    }
                }
            }

        };
    }

    private static CompoundTag packStructureData(StructurePieceSerializationContext context, ChunkPos pos, Map<Structure, StructureStart> starts, Map<Structure, LongSet> references) {
        CompoundTag nbttagcompound = new CompoundTag();
        CompoundTag nbttagcompound1 = new CompoundTag();
        Registry<Structure> iregistry = context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Iterator iterator = starts.entrySet().iterator();

        while (iterator.hasNext()) {
            Entry<Structure, StructureStart> entry = (Entry) iterator.next();
            ResourceLocation minecraftkey = iregistry.getKey((Structure) entry.getKey());

            nbttagcompound1.put(minecraftkey.toString(), ((StructureStart) entry.getValue()).createTag(context, pos));
        }

        nbttagcompound.put("starts", nbttagcompound1);
        CompoundTag nbttagcompound2 = new CompoundTag();
        Iterator iterator1 = references.entrySet().iterator();

        while (iterator1.hasNext()) {
            Entry<Structure, LongSet> entry1 = (Entry) iterator1.next();

            if (!((LongSet) entry1.getValue()).isEmpty()) {
                ResourceLocation minecraftkey1 = iregistry.getKey((Structure) entry1.getKey());

                nbttagcompound2.put(minecraftkey1.toString(), new LongArrayTag((LongSet) entry1.getValue()));
            }
        }

        nbttagcompound.put("References", nbttagcompound2);
        return nbttagcompound;
    }

    private static Map<Structure, StructureStart> unpackStructureStart(StructurePieceSerializationContext context, CompoundTag nbt, long worldSeed) {
        Map<Structure, StructureStart> map = Maps.newHashMap();
        Registry<Structure> iregistry = context.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        CompoundTag nbttagcompound1 = nbt.getCompound("starts");
        Iterator iterator = nbttagcompound1.getAllKeys().iterator();

        while (iterator.hasNext()) {
            String s = (String) iterator.next();
            ResourceLocation minecraftkey = ResourceLocation.tryParse(s);
            Structure structure = (Structure) iregistry.getValue(minecraftkey);

            if (structure == null) {
                SerializableChunkData.LOGGER.error("Unknown structure start: {}", minecraftkey);
            } else {
                StructureStart structurestart = StructureStart.loadStaticStart(context, nbttagcompound1.getCompound(s), worldSeed);

                if (structurestart != null) {
                    // CraftBukkit start - load persistent data for structure start
                    net.minecraft.nbt.Tag persistentBase = nbttagcompound1.getCompound(s).get("StructureBukkitValues");
                    if (persistentBase instanceof CompoundTag) {
                        structurestart.persistentDataContainer.putAll((CompoundTag) persistentBase);
                    }
                    // CraftBukkit end
                    map.put(structure, structurestart);
                }
            }
        }

        return map;
    }

    private static Map<Structure, LongSet> unpackStructureReferences(RegistryAccess registryManager, ChunkPos pos, CompoundTag nbt) {
        Map<Structure, LongSet> map = Maps.newHashMap();
        Registry<Structure> iregistry = registryManager.lookupOrThrow(Registries.STRUCTURE);
        CompoundTag nbttagcompound1 = nbt.getCompound("References");
        Iterator iterator = nbttagcompound1.getAllKeys().iterator();

        while (iterator.hasNext()) {
            String s = (String) iterator.next();
            ResourceLocation minecraftkey = ResourceLocation.tryParse(s);
            Structure structure = (Structure) iregistry.getValue(minecraftkey);

            if (structure == null) {
                SerializableChunkData.LOGGER.warn("Found reference to unknown structure '{}' in chunk {}, discarding", minecraftkey, pos);
            } else {
                long[] along = nbttagcompound1.getLongArray(s);

                if (along.length != 0) {
                    map.put(structure, new LongOpenHashSet(Arrays.stream(along).filter((i) -> {
                        ChunkPos chunkcoordintpair1 = new ChunkPos(i);

                        if (chunkcoordintpair1.getChessboardDistance(pos) > 8) {
                            SerializableChunkData.LOGGER.warn("Found invalid structure reference [ {} @ {} ] for chunk {}.", new Object[]{minecraftkey, chunkcoordintpair1, pos});
                            return false;
                        } else {
                            return true;
                        }
                    }).toArray()));
                }
            }
        }

        return map;
    }

    private static ListTag packOffsets(ShortList[] lists) {
        ListTag nbttaglist = new ListTag();
        ShortList[] ashortlist1 = lists;
        int i = lists.length;

        for (int j = 0; j < i; ++j) {
            ShortList shortlist = ashortlist1[j];
            ListTag nbttaglist1 = new ListTag();

            if (shortlist != null) {
                for (int k = 0; k < shortlist.size(); ++k) {
                    nbttaglist1.add(ShortTag.valueOf(shortlist.getShort(k)));
                }
            }

            nbttaglist.add(nbttaglist1);
        }

        return nbttaglist;
    }

    // Paper start - starlight - convert from record
    public static final class SectionData implements ca.spottedleaf.moonrise.patches.starlight.storage.StarlightSectionData { // Paper - starlight - our diff
        private final int y;
        @javax.annotation.Nullable
        private final net.minecraft.world.level.chunk.LevelChunkSection chunkSection;
        @javax.annotation.Nullable
        private final net.minecraft.world.level.chunk.DataLayer blockLight;
        @javax.annotation.Nullable
        private final net.minecraft.world.level.chunk.DataLayer skyLight;

        // Paper start - starlight - our diff
        private int blockLightState = -1;
        private int skyLightState = -1;

        @Override
        public final int starlight$getBlockLightState() {
            return this.blockLightState;
        }

        @Override
        public final void starlight$setBlockLightState(final int state) {
            this.blockLightState = state;
        }

        @Override
        public final int starlight$getSkyLightState() {
            return this.skyLightState;
        }

        @Override
        public final void starlight$setSkyLightState(final int state) {
            this.skyLightState = state;
        }
        // Paper end - starlight - our diff

        public SectionData(int y, @javax.annotation.Nullable net.minecraft.world.level.chunk.LevelChunkSection chunkSection, @javax.annotation.Nullable net.minecraft.world.level.chunk.DataLayer blockLight, @javax.annotation.Nullable net.minecraft.world.level.chunk.DataLayer skyLight) {
            this.y = y;
            this.chunkSection = chunkSection;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }

        public int y() {
            return y;
        }

        @javax.annotation.Nullable
        public net.minecraft.world.level.chunk.LevelChunkSection chunkSection() {
            return chunkSection;
        }

        @javax.annotation.Nullable
        public net.minecraft.world.level.chunk.DataLayer blockLight() {
            return blockLight;
        }

        @javax.annotation.Nullable
        public net.minecraft.world.level.chunk.DataLayer skyLight() {
            return skyLight;
        }
        // Paper end - starlight - convert from record

    }

    public static class ChunkReadException extends NbtException {

        public ChunkReadException(String message) {
            super(message);
        }
    }
}
