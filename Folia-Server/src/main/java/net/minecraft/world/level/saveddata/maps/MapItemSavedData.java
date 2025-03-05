package net.minecraft.world.level.saveddata.maps;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import io.netty.buffer.ByteBuf;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapDecorations;
import net.minecraft.world.item.component.MapItemColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

// CraftBukkit start
import io.papermc.paper.adventure.PaperAdventure; // Paper
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.map.CraftMapCursor;
import org.bukkit.craftbukkit.map.CraftMapView;
import org.bukkit.craftbukkit.util.CraftChatMessage;
// CraftBukkit end

public class MapItemSavedData extends SavedData {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAP_SIZE = 128;
    private static final int HALF_MAP_SIZE = 64;
    public static final int MAX_SCALE = 4;
    public static final int TRACKED_DECORATION_LIMIT = 256;
    private static final String FRAME_PREFIX = "frame-";
    public int centerX;
    public int centerZ;
    public ResourceKey<Level> dimension;
    public boolean trackingPosition;
    public boolean unlimitedTracking;
    public byte scale;
    public byte[] colors = new byte[16384];
    public boolean locked;
    public final List<MapItemSavedData.HoldingPlayer> carriedBy = Lists.newArrayList();
    public final Map<Player, MapItemSavedData.HoldingPlayer> carriedByPlayers = Maps.newHashMap();
    private final Map<String, MapBanner> bannerMarkers = Maps.newHashMap();
    public final Map<String, MapDecoration> decorations = Maps.newLinkedHashMap();
    private final Map<String, MapFrame> frameMarkers = Maps.newHashMap();
    private int trackedDecorationCount;
    private org.bukkit.craftbukkit.map.RenderData vanillaRender = new org.bukkit.craftbukkit.map.RenderData(); // Paper

    // CraftBukkit start
    public final CraftMapView mapView;
    private CraftServer server;
    public UUID uniqueId = null;
    public MapId id;
    // CraftBukkit end

    public static SavedData.Factory<MapItemSavedData> factory() {
        return new SavedData.Factory<>(() -> {
            throw new IllegalStateException("Should never create an empty map saved data");
        }, MapItemSavedData::load, DataFixTypes.SAVED_DATA_MAP_DATA);
    }

    private MapItemSavedData(int centerX, int centerZ, byte scale, boolean showDecorations, boolean unlimitedTracking, boolean locked, ResourceKey<Level> dimension) {
        this.scale = scale;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.dimension = dimension;
        this.trackingPosition = showDecorations;
        this.unlimitedTracking = unlimitedTracking;
        this.locked = locked;
        // CraftBukkit start
        this.mapView = new CraftMapView(this);
        this.server = (CraftServer) org.bukkit.Bukkit.getServer();
        this.vanillaRender.buffer = colors; // Paper
        // CraftBukkit end
    }

    public static MapItemSavedData createFresh(double centerX, double centerZ, byte scale, boolean showDecorations, boolean unlimitedTracking, ResourceKey<Level> dimension) {
        int i = 128 * (1 << scale);
        int j = Mth.floor((centerX + 64.0D) / (double) i);
        int k = Mth.floor((centerZ + 64.0D) / (double) i);
        int l = j * i + i / 2 - 64;
        int i1 = k * i + i / 2 - 64;

        return new MapItemSavedData(l, i1, scale, showDecorations, unlimitedTracking, false, dimension);
    }

    public static MapItemSavedData createForClient(byte scale, boolean locked, ResourceKey<Level> dimension) {
        return new MapItemSavedData(0, 0, scale, false, false, locked, dimension);
    }

    public static MapItemSavedData load(CompoundTag nbt, HolderLookup.Provider registries) {
        // Paper start - fix "Not a string" spam
        Tag dimension = nbt.get("dimension");
        if (dimension instanceof final net.minecraft.nbt.NumericTag numericTag && numericTag.getAsInt() >= CraftWorld.CUSTOM_DIMENSION_OFFSET) {
            long least = nbt.getLong("UUIDLeast");
            long most = nbt.getLong("UUIDMost");

            if (least != 0L && most != 0L) {
                UUID uuid = new UUID(most, least);
                CraftWorld world = (CraftWorld) Bukkit.getWorld(uuid);
                if (world != null) {
                    dimension = net.minecraft.nbt.StringTag.valueOf("minecraft:" + world.getName().toLowerCase(java.util.Locale.ENGLISH));
                } else {
                    dimension = net.minecraft.nbt.StringTag.valueOf("bukkit:_invalidworld_");
                }
            } else {
                dimension = net.minecraft.nbt.StringTag.valueOf("bukkit:_invalidworld_");
            }
        }
        DataResult<ResourceKey<Level>> dataresult = DimensionType.parseLegacy(new Dynamic(NbtOps.INSTANCE, dimension)); // CraftBukkit - decompile error
        // Paper end - fix "Not a string" spam
        Logger logger = MapItemSavedData.LOGGER;

        Objects.requireNonNull(logger);
        // CraftBukkit start
        ResourceKey<Level> resourcekey = (ResourceKey) dataresult.resultOrPartial(logger::error).orElseGet(() -> {
            long least = nbt.getLong("UUIDLeast");
            long most = nbt.getLong("UUIDMost");

            if (least != 0L && most != 0L) {
                UUID uniqueId = new UUID(most, least);

                CraftWorld world = (CraftWorld) Bukkit.getWorld(uniqueId);
                // Check if the stored world details are correct.
                if (world == null) {
                    /* All Maps which do not have their valid world loaded are set to a dimension which hopefully won't be reached.
                       This is to prevent them being corrupted with the wrong map data. */
                    // PAIL: Use Vanilla exception handling for now
                } else {
                    return world.getHandle().dimension();
                }
            }
            throw new IllegalArgumentException("Invalid map dimension: " + String.valueOf(nbt.get("dimension")));
            // CraftBukkit end
        });
        int i = nbt.getInt("xCenter");
        int j = nbt.getInt("zCenter");
        byte b0 = (byte) Mth.clamp(nbt.getByte("scale"), 0, 4);
        boolean flag = !nbt.contains("trackingPosition", 1) || nbt.getBoolean("trackingPosition");
        boolean flag1 = nbt.getBoolean("unlimitedTracking");
        boolean flag2 = nbt.getBoolean("locked");
        MapItemSavedData worldmap = new MapItemSavedData(i, j, b0, flag, flag1, flag2, resourcekey);
        byte[] abyte = nbt.getByteArray("colors");

        if (abyte.length == 16384) {
            worldmap.colors = abyte;
        }
        worldmap.vanillaRender.buffer = abyte; // Paper

        RegistryOps<Tag> registryops = registries.createSerializationContext(NbtOps.INSTANCE);
        List<MapBanner> list = (List) MapBanner.LIST_CODEC.parse(registryops, nbt.get("banners")).resultOrPartial((s) -> {
            MapItemSavedData.LOGGER.warn("Failed to parse map banner: '{}'", s);
        }).orElse(List.of());
        Iterator iterator = list.iterator();

        while (iterator.hasNext()) {
            MapBanner mapiconbanner = (MapBanner) iterator.next();

            worldmap.bannerMarkers.put(mapiconbanner.getId(), mapiconbanner);
            // CraftBukkit - decompile error
            worldmap.addDecoration(mapiconbanner.getDecoration(), (LevelAccessor) null, mapiconbanner.getId(), (double) mapiconbanner.pos().getX(), (double) mapiconbanner.pos().getZ(), 180.0D, (Component) mapiconbanner.name().orElse(null));
        }

        ListTag nbttaglist = nbt.getList("frames", 10);

        for (int k = 0; k < nbttaglist.size(); ++k) {
            MapFrame worldmapframe = MapFrame.load(nbttaglist.getCompound(k));

            if (worldmapframe != null) {
                worldmap.frameMarkers.put(worldmapframe.getId(), worldmapframe);
                worldmap.addDecoration(MapDecorationTypes.FRAME, (LevelAccessor) null, MapItemSavedData.getFrameKey(worldmapframe.getEntityId()), (double) worldmapframe.getPos().getX(), (double) worldmapframe.getPos().getZ(), (double) worldmapframe.getRotation(), (Component) null);
            }
        }

        return worldmap;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries) { // Folia - make map data thread-safe
        DataResult<Tag> dataresult = ResourceLocation.CODEC.encodeStart(NbtOps.INSTANCE, this.dimension.location()); // CraftBukkit - decompile error
        Logger logger = MapItemSavedData.LOGGER;

        Objects.requireNonNull(logger);
        dataresult.resultOrPartial(logger::error).ifPresent((nbtbase) -> {
            nbt.put("dimension", nbtbase);
        });
        // CraftBukkit start
        if (true) {
            if (this.uniqueId == null) {
                for (org.bukkit.World world : this.server.getWorlds()) {
                    CraftWorld cWorld = (CraftWorld) world;
                    if (cWorld.getHandle().dimension() == this.dimension) {
                        this.uniqueId = cWorld.getUID();
                        break;
                    }
                }
            }
            /* Perform a second check to see if a matching world was found, this is a necessary
               change incase Maps are forcefully unlinked from a World and lack a UID.*/
            if (this.uniqueId != null) {
                nbt.putLong("UUIDLeast", this.uniqueId.getLeastSignificantBits());
                nbt.putLong("UUIDMost", this.uniqueId.getMostSignificantBits());
            }
        }
        // CraftBukkit end
        nbt.putInt("xCenter", this.centerX);
        nbt.putInt("zCenter", this.centerZ);
        nbt.putByte("scale", this.scale);
        nbt.putByteArray("colors", this.colors);
        nbt.putBoolean("trackingPosition", this.trackingPosition);
        nbt.putBoolean("unlimitedTracking", this.unlimitedTracking);
        nbt.putBoolean("locked", this.locked);
        RegistryOps<Tag> registryops = registries.createSerializationContext(NbtOps.INSTANCE);

        nbt.put("banners", (Tag) MapBanner.LIST_CODEC.encodeStart(registryops, List.copyOf(this.bannerMarkers.values())).getOrThrow());
        ListTag nbttaglist = new ListTag();
        Iterator iterator = this.frameMarkers.values().iterator();

        while (iterator.hasNext()) {
            MapFrame worldmapframe = (MapFrame) iterator.next();

            nbttaglist.add(worldmapframe.save());
        }

        nbt.put("frames", nbttaglist);
        return nbt;
    }

    public synchronized MapItemSavedData locked() { // Folia - make map data thread-safe
        MapItemSavedData worldmap = new MapItemSavedData(this.centerX, this.centerZ, this.scale, this.trackingPosition, this.unlimitedTracking, true, this.dimension);

        worldmap.bannerMarkers.putAll(this.bannerMarkers);
        worldmap.decorations.putAll(this.decorations);
        worldmap.trackedDecorationCount = this.trackedDecorationCount;
        System.arraycopy(this.colors, 0, worldmap.colors, 0, this.colors.length);
        return worldmap;
    }

    public synchronized MapItemSavedData scaled() { // Folia - make map data thread-safe
        return MapItemSavedData.createFresh((double) this.centerX, (double) this.centerZ, (byte) Mth.clamp(this.scale + 1, 0, 4), this.trackingPosition, this.unlimitedTracking, this.dimension);
    }

    private static Predicate<ItemStack> mapMatcher(ItemStack stack) {
        MapId mapid = (MapId) stack.get(DataComponents.MAP_ID);

        return (itemstack1) -> {
            return itemstack1 == stack ? true : itemstack1.is(stack.getItem()) && Objects.equals(mapid, itemstack1.get(DataComponents.MAP_ID));
        };
    }

    public synchronized void tickCarriedBy(Player player, ItemStack stack) { // Folia - make map data thread-safe
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(player, "Ticking map player in incorrect region"); // Folia - region threading
        if (!this.carriedByPlayers.containsKey(player)) {
            MapItemSavedData.HoldingPlayer worldmap_worldmaphumantracker = new MapItemSavedData.HoldingPlayer(player);

            this.carriedByPlayers.put(player, worldmap_worldmaphumantracker);
            this.carriedBy.add(worldmap_worldmaphumantracker);
        }

        Predicate<ItemStack> predicate = MapItemSavedData.mapMatcher(stack);

        if (!player.getInventory().contains(predicate)) {
            this.removeDecoration(player.getName().getString());
        }

        for (int i = 0; i < this.carriedBy.size(); ++i) {
            MapItemSavedData.HoldingPlayer worldmap_worldmaphumantracker1 = (MapItemSavedData.HoldingPlayer) this.carriedBy.get(i);
            Player entityhuman1 = worldmap_worldmaphumantracker1.player;
            String s = entityhuman1.getName().getString();

            if (!entityhuman1.isRemoved() && (entityhuman1.getInventory().contains(predicate) || stack.isFramed())) {
                if (!stack.isFramed() && entityhuman1.level().dimension() == this.dimension && this.trackingPosition) {
                    this.addDecoration(MapDecorationTypes.PLAYER, entityhuman1.level(), s, entityhuman1.getX(), entityhuman1.getZ(), (double) entityhuman1.getYRot(), (Component) null);
                }
            } else {
                this.carriedByPlayers.remove(entityhuman1);
                this.carriedBy.remove(worldmap_worldmaphumantracker1);
                this.removeDecoration(s);
            }

            if (!entityhuman1.equals(player) && MapItemSavedData.hasMapInvisibilityItemEquipped(entityhuman1)) {
                this.removeDecoration(s);
            }
        }

        if (stack.isFramed() && this.trackingPosition) {
            ItemFrame entityitemframe = stack.getFrame();
            BlockPos blockposition = entityitemframe.getPos();
            MapFrame worldmapframe = (MapFrame) this.frameMarkers.get(MapFrame.frameId(blockposition));

            if (worldmapframe != null && entityitemframe.getId() != worldmapframe.getEntityId() && this.frameMarkers.containsKey(worldmapframe.getId())) {
                this.removeDecoration(MapItemSavedData.getFrameKey(worldmapframe.getEntityId()));
            }

            MapFrame worldmapframe1 = new MapFrame(blockposition, entityitemframe.getDirection().get2DDataValue() * 90, entityitemframe.getId());

            if (this.decorations.size() < player.level().paperConfig().maps.itemFrameCursorLimit) { // Paper - Limit item frame cursors on maps
            this.addDecoration(MapDecorationTypes.FRAME, player.level(), MapItemSavedData.getFrameKey(entityitemframe.getId()), (double) blockposition.getX(), (double) blockposition.getZ(), (double) (entityitemframe.getDirection().get2DDataValue() * 90), (Component) null);
            this.frameMarkers.put(worldmapframe1.getId(), worldmapframe1);
            } // Paper - Limit item frame cursors on maps
        }

        MapDecorations mapdecorations = (MapDecorations) stack.getOrDefault(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY);

        if (!this.decorations.keySet().containsAll(mapdecorations.decorations().keySet())) {
            mapdecorations.decorations().forEach((s1, mapdecorations_a) -> {
                if (!this.decorations.containsKey(s1)) {
                    this.addDecoration(mapdecorations_a.type(), player.level(), s1, mapdecorations_a.x(), mapdecorations_a.z(), (double) mapdecorations_a.rotation(), (Component) null);
                }

            });
        }

    }

    private static boolean hasMapInvisibilityItemEquipped(Player player) {
        EquipmentSlot[] aenumitemslot = EquipmentSlot.values();
        int i = aenumitemslot.length;

        for (int j = 0; j < i; ++j) {
            EquipmentSlot enumitemslot = aenumitemslot[j];

            if (enumitemslot != EquipmentSlot.MAINHAND && enumitemslot != EquipmentSlot.OFFHAND && player.getItemBySlot(enumitemslot).is(ItemTags.MAP_INVISIBILITY_EQUIPMENT)) {
                return true;
            }
        }

        return false;
    }

    private void removeDecoration(String id) {
        MapDecoration mapicon = (MapDecoration) this.decorations.remove(id);

        if (mapicon != null && ((MapDecorationType) mapicon.type().value()).trackCount()) {
            --this.trackedDecorationCount;
        }

        if (mapicon != null) this.setDecorationsDirty(); // Paper - only mark dirty if a change occurs
    }

    public static void addTargetDecoration(ItemStack stack, BlockPos pos, String id, Holder<MapDecorationType> decorationType) {
        MapDecorations.Entry mapdecorations_a = new MapDecorations.Entry(decorationType, (double) pos.getX(), (double) pos.getZ(), 180.0F);

        stack.update(DataComponents.MAP_DECORATIONS, MapDecorations.EMPTY, (mapdecorations) -> {
            return mapdecorations.withDecoration(id, mapdecorations_a);
        });
        if (((MapDecorationType) decorationType.value()).hasMapColor()) {
            stack.set(DataComponents.MAP_COLOR, new MapItemColor(((MapDecorationType) decorationType.value()).mapColor()));
        }

    }

    private void addDecoration(Holder<MapDecorationType> type, @Nullable LevelAccessor world, String key, double x, double z, double rotation, @Nullable Component text) {
        int i = 1 << this.scale;
        float f = (float) (x - (double) this.centerX) / (float) i;
        float f1 = (float) (z - (double) this.centerZ) / (float) i;
        MapItemSavedData.MapDecorationLocation worldmap_b = this.calculateDecorationLocationAndType(type, world, rotation, f, f1);

        if (worldmap_b == null) {
            this.removeDecoration(key);
        } else {
            MapDecoration mapicon = new MapDecoration(worldmap_b.type(), worldmap_b.x(), worldmap_b.y(), worldmap_b.rot(), Optional.ofNullable(text));
            MapDecoration mapicon1 = (MapDecoration) this.decorations.put(key, mapicon);

            if (!mapicon.equals(mapicon1)) {
                if (mapicon1 != null && ((MapDecorationType) mapicon1.type().value()).trackCount()) {
                    --this.trackedDecorationCount;
                }

                if (((MapDecorationType) worldmap_b.type().value()).trackCount()) {
                    ++this.trackedDecorationCount;
                }

                this.setDecorationsDirty();
            }

        }
    }

    @Nullable
    private MapItemSavedData.MapDecorationLocation calculateDecorationLocationAndType(Holder<MapDecorationType> type, @Nullable LevelAccessor world, double rotation, float dx, float dz) {
        byte b0 = MapItemSavedData.clampMapCoordinate(dx);
        byte b1 = MapItemSavedData.clampMapCoordinate(dz);

        if (type.is(MapDecorationTypes.PLAYER)) {
            Pair<Holder<MapDecorationType>, Byte> pair = this.playerDecorationTypeAndRotation(type, world, rotation, dx, dz);

            return pair == null ? null : new MapItemSavedData.MapDecorationLocation((Holder) pair.getFirst(), b0, b1, (Byte) pair.getSecond());
        } else {
            return !MapItemSavedData.isInsideMap(dx, dz) && !this.unlimitedTracking ? null : new MapItemSavedData.MapDecorationLocation(type, b0, b1, this.calculateRotation(world, rotation));
        }
    }

    @Nullable
    private Pair<Holder<MapDecorationType>, Byte> playerDecorationTypeAndRotation(Holder<MapDecorationType> type, @Nullable LevelAccessor world, double rotation, float dx, float dz) {
        if (MapItemSavedData.isInsideMap(dx, dz)) {
            return Pair.of(type, this.calculateRotation(world, rotation));
        } else {
            Holder<MapDecorationType> holder1 = this.decorationTypeForPlayerOutsideMap(dx, dz);

            return holder1 == null ? null : Pair.of(holder1, (byte) 0);
        }
    }

    private byte calculateRotation(@Nullable LevelAccessor world, double rotation) {
        if (this.dimension == Level.NETHER && world != null) {
            int i = (int) (world.dayTime() / 10L); // Folia - region threading

            return (byte) (i * i * 34187121 + i * 121 >> 15 & 15);
        } else {
            double d1 = rotation < 0.0D ? rotation - 8.0D : rotation + 8.0D;

            return (byte) ((int) (d1 * 16.0D / 360.0D));
        }
    }

    private static boolean isInsideMap(float dx, float dz) {
        boolean flag = true;

        return dx >= -63.0F && dz >= -63.0F && dx <= 63.0F && dz <= 63.0F;
    }

    @Nullable
    private Holder<MapDecorationType> decorationTypeForPlayerOutsideMap(float dx, float dz) {
        boolean flag = true;
        boolean flag1 = Math.abs(dx) < 320.0F && Math.abs(dz) < 320.0F;

        return flag1 ? MapDecorationTypes.PLAYER_OFF_MAP : (this.unlimitedTracking ? MapDecorationTypes.PLAYER_OFF_LIMITS : null);
    }

    private static byte clampMapCoordinate(float d) {
        boolean flag = true;

        return d <= -63.0F ? Byte.MIN_VALUE : (d >= 63.0F ? 127 : (byte) ((int) ((double) (d * 2.0F) + 0.5D)));
    }

    @Nullable
    public synchronized Packet<?> getUpdatePacket(MapId mapId, Player player) { // Folia - make map data thread-safe
        MapItemSavedData.HoldingPlayer worldmap_worldmaphumantracker = (MapItemSavedData.HoldingPlayer) this.carriedByPlayers.get(player);

        return worldmap_worldmaphumantracker == null ? null : worldmap_worldmaphumantracker.nextUpdatePacket(mapId);
    }

    public synchronized void setColorsDirty(int x, int z) { // Folia - make map data thread-safe
        // Folia - make dirty only after updating data - moved down
        Iterator iterator = this.carriedBy.iterator();

        while (iterator.hasNext()) {
            MapItemSavedData.HoldingPlayer worldmap_worldmaphumantracker = (MapItemSavedData.HoldingPlayer) iterator.next();

            worldmap_worldmaphumantracker.markColorsDirty(x, z);
        }
        this.setDirty(); // Folia - make dirty only after updating data - moved from above
    }

    public synchronized void setDecorationsDirty() { // Folia - make map data thread-safe
        // Folia - make dirty only after updating data - moved down
        this.carriedBy.forEach(MapItemSavedData.HoldingPlayer::markDecorationsDirty);
        this.setDirty(); // Folia - make dirty only after updating data - moved from above
    }

    public synchronized MapItemSavedData.HoldingPlayer getHoldingPlayer(Player player) { // Folia - make map data thread-safe
        MapItemSavedData.HoldingPlayer worldmap_worldmaphumantracker = (MapItemSavedData.HoldingPlayer) this.carriedByPlayers.get(player);

        if (worldmap_worldmaphumantracker == null) {
            worldmap_worldmaphumantracker = new MapItemSavedData.HoldingPlayer(player);
            this.carriedByPlayers.put(player, worldmap_worldmaphumantracker);
            this.carriedBy.add(worldmap_worldmaphumantracker);
        }

        return worldmap_worldmaphumantracker;
    }

    public synchronized boolean toggleBanner(LevelAccessor world, BlockPos pos) { // Folia - make map data thread-safe
        double d0 = (double) pos.getX() + 0.5D;
        double d1 = (double) pos.getZ() + 0.5D;
        int i = 1 << this.scale;
        double d2 = (d0 - (double) this.centerX) / (double) i;
        double d3 = (d1 - (double) this.centerZ) / (double) i;
        boolean flag = true;

        if (d2 >= -63.0D && d3 >= -63.0D && d2 <= 63.0D && d3 <= 63.0D) {
            MapBanner mapiconbanner = world.getChunkIfLoadedImmediately(pos.getX() >> 4, pos.getZ() >> 4) == null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world.getMinecraftWorld(), pos) ? null : MapBanner.fromWorld(world, pos); // Folia - make map data thread-safe - don't sync load or read data we do not own

            if (mapiconbanner == null) {
                return false;
            }

            if (this.bannerMarkers.remove(mapiconbanner.getId(), mapiconbanner)) {
                this.removeDecoration(mapiconbanner.getId());
                return true;
            }

            if (!this.isTrackedCountOverLimit(((Level) world).paperConfig().maps.itemFrameCursorLimit)) { // Paper - Limit item frame cursors on maps
                this.bannerMarkers.put(mapiconbanner.getId(), mapiconbanner);
                this.addDecoration(mapiconbanner.getDecoration(), world, mapiconbanner.getId(), d0, d1, 180.0D, (Component) mapiconbanner.name().orElse(null)); // CraftBukkit - decompile error
                return true;
            }
        }

        return false;
    }

    public synchronized void checkBanners(BlockGetter world, int x, int z) { // Folia - make map data thread-safe
        Iterator<MapBanner> iterator = this.bannerMarkers.values().iterator();

        while (iterator.hasNext()) {
            MapBanner mapiconbanner = (MapBanner) iterator.next();

            if (mapiconbanner.pos().getX() == x && mapiconbanner.pos().getZ() == z) {
                MapBanner mapiconbanner1 = MapBanner.fromWorld(world, mapiconbanner.pos());

                if (!mapiconbanner.equals(mapiconbanner1)) {
                    iterator.remove();
                    this.removeDecoration(mapiconbanner.getId());
                }
            }
        }

    }

    public Collection<MapBanner> getBanners() {
        return this.bannerMarkers.values();
    }

    public synchronized void removedFromFrame(BlockPos pos, int id) { // Folia - make map data thread-safe
        this.removeDecoration(MapItemSavedData.getFrameKey(id));
        this.frameMarkers.remove(MapFrame.frameId(pos));
        this.setDirty();
    }

    public synchronized boolean updateColor(int x, int z, byte color) { // Folia - make map data thread-safe
        byte b1 = this.colors[x + z * 128];

        if (b1 != color) {
            this.setColor(x, z, color);
            return true;
        } else {
            return false;
        }
    }

    public synchronized void setColor(int x, int z, byte color) { // Folia - make map data thread-safe
        this.colors[x + z * 128] = color;
        this.setColorsDirty(x, z);
    }

    public synchronized boolean isExplorationMap() { // Folia - make map data thread-safe
        Iterator iterator = this.decorations.values().iterator();

        MapDecoration mapicon;

        do {
            if (!iterator.hasNext()) {
                return false;
            }

            mapicon = (MapDecoration) iterator.next();
        } while (!((MapDecorationType) mapicon.type().value()).explorationMapElement());

        return true;
    }

    public synchronized void addClientSideDecorations(List<MapDecoration> decorations) { // Folia - make map data thread-safe
        this.decorations.clear();
        this.trackedDecorationCount = 0;

        for (int i = 0; i < decorations.size(); ++i) {
            MapDecoration mapicon = (MapDecoration) decorations.get(i);

            this.decorations.put("icon-" + i, mapicon);
            if (((MapDecorationType) mapicon.type().value()).trackCount()) {
                ++this.trackedDecorationCount;
            }
        }

    }

    public Iterable<MapDecoration> getDecorations() {
        return this.decorations.values();
    }

    public synchronized boolean isTrackedCountOverLimit(int decorationCount) { // Folia - make map data thread-safe
        return this.trackedDecorationCount >= decorationCount;
    }

    private static String getFrameKey(int id) {
        return "frame-" + id;
    }

    public class HoldingPlayer {

        // Paper start
        private void addSeenPlayers(java.util.Collection<MapDecoration> icons) {
            org.bukkit.entity.Player player = (org.bukkit.entity.Player) this.player.getBukkitEntity();
            MapItemSavedData.this.decorations.forEach((name, mapIcon) -> {
                // If this cursor is for a player check visibility with vanish system
                org.bukkit.entity.Player other = org.bukkit.Bukkit.getPlayerExact(name); // Spigot
                if (other == null || player.canSee(other)) {
                    icons.add(mapIcon);
                }
            });
        }
        private boolean shouldUseVanillaMap() {
            return mapView.getRenderers().size() == 1 && mapView.getRenderers().get(0).getClass() == org.bukkit.craftbukkit.map.CraftMapRenderer.class;
        }
        // Paper end
        public final Player player;
        private boolean dirtyData = true;
        private int minDirtyX;
        private int minDirtyY;
        private int maxDirtyX = 127;
        private int maxDirtyY = 127;
        private boolean dirtyDecorations = true;
        private int tick;
        public int step;

        HoldingPlayer(final Player entityhuman) {
            this.player = entityhuman;
        }

        private MapItemSavedData.MapPatch createPatch(byte[] buffer) { // CraftBukkit
            int i = this.minDirtyX;
            int j = this.minDirtyY;
            int k = this.maxDirtyX + 1 - this.minDirtyX;
            int l = this.maxDirtyY + 1 - this.minDirtyY;
            byte[] abyte = new byte[k * l];

            for (int i1 = 0; i1 < k; ++i1) {
                for (int j1 = 0; j1 < l; ++j1) {
                    abyte[i1 + j1 * k] = buffer[i + i1 + (j + j1) * 128]; // CraftBukkit
                }
            }

            return new MapItemSavedData.MapPatch(i, j, k, l, abyte);
        }

        @Nullable
        Packet<?> nextUpdatePacket(MapId mapId) {
            MapItemSavedData.MapPatch worldmap_c;
            if (!this.dirtyData && this.tick % 5 != 0) { this.tick++; return null; } // Paper - this won't end up sending, so don't render it!
            boolean vanillaMaps = shouldUseVanillaMap(); // Paper
            org.bukkit.craftbukkit.map.RenderData render = !vanillaMaps ? MapItemSavedData.this.mapView.render((org.bukkit.craftbukkit.entity.CraftPlayer) this.player.getBukkitEntity()) : MapItemSavedData.this.vanillaRender; // CraftBukkit // Paper

            if (this.dirtyData) {
                this.dirtyData = false;
                worldmap_c = this.createPatch(render.buffer); // CraftBukkit
            } else {
                worldmap_c = null;
            }

            Collection collection;

            if ((true || this.dirtyDecorations) && this.tick++ % 5 == 0) { // CraftBukkit - custom maps don't update this yet
                this.dirtyDecorations = false;
                // CraftBukkit start
                java.util.Collection<MapDecoration> icons = new java.util.ArrayList<MapDecoration>();

                if (vanillaMaps) addSeenPlayers(icons); // Paper

                for (org.bukkit.map.MapCursor cursor : render.cursors) {
                    if (cursor.isVisible()) {
                        icons.add(new MapDecoration(CraftMapCursor.CraftType.bukkitToMinecraftHolder(cursor.getType()), cursor.getX(), cursor.getY(), cursor.getDirection(), Optional.ofNullable(PaperAdventure.asVanilla(cursor.caption()))));
                    }
                }
                collection = icons;
                // CraftBukkit end
            } else {
                collection = null;
            }

            return collection == null && worldmap_c == null ? null : new ClientboundMapItemDataPacket(mapId, MapItemSavedData.this.scale, MapItemSavedData.this.locked, collection, worldmap_c);
        }

        void markColorsDirty(int startX, int startZ) {
            if (this.dirtyData) {
                this.minDirtyX = Math.min(this.minDirtyX, startX);
                this.minDirtyY = Math.min(this.minDirtyY, startZ);
                this.maxDirtyX = Math.max(this.maxDirtyX, startX);
                this.maxDirtyY = Math.max(this.maxDirtyY, startZ);
            } else {
                this.dirtyData = true;
                this.minDirtyX = startX;
                this.minDirtyY = startZ;
                this.maxDirtyX = startX;
                this.maxDirtyY = startZ;
            }

        }

        private void markDecorationsDirty() {
            this.dirtyDecorations = true;
        }
    }

    private static record MapDecorationLocation(Holder<MapDecorationType> type, byte x, byte y, byte rot) {

    }

    public static record MapPatch(int startX, int startY, int width, int height, byte[] mapColors) {

        public static final StreamCodec<ByteBuf, Optional<MapItemSavedData.MapPatch>> STREAM_CODEC = StreamCodec.of(MapItemSavedData.MapPatch::write, MapItemSavedData.MapPatch::read);

        private static void write(ByteBuf buf, Optional<MapItemSavedData.MapPatch> updateData) {
            if (updateData.isPresent()) {
                MapItemSavedData.MapPatch worldmap_c = (MapItemSavedData.MapPatch) updateData.get();

                buf.writeByte(worldmap_c.width);
                buf.writeByte(worldmap_c.height);
                buf.writeByte(worldmap_c.startX);
                buf.writeByte(worldmap_c.startY);
                FriendlyByteBuf.writeByteArray(buf, worldmap_c.mapColors);
            } else {
                buf.writeByte(0);
            }

        }

        private static Optional<MapItemSavedData.MapPatch> read(ByteBuf buf) {
            short short0 = buf.readUnsignedByte();

            if (short0 > 0) {
                short short1 = buf.readUnsignedByte();
                short short2 = buf.readUnsignedByte();
                short short3 = buf.readUnsignedByte();
                byte[] abyte = FriendlyByteBuf.readByteArray(buf);

                return Optional.of(new MapItemSavedData.MapPatch(short2, short3, short0, short1, abyte));
            } else {
                return Optional.empty();
            }
        }

        public void applyToMap(MapItemSavedData mapState) {
            synchronized (mapState) { // Folia - make map data thread-safe
            for (int i = 0; i < this.width; ++i) {
                for (int j = 0; j < this.height; ++j) {
                    mapState.setColor(this.startX + i, this.startY + j, this.mapColors[i + j * this.width]);
                }
            }
            } // Folia - make map data thread-safe

        }
    }
}
