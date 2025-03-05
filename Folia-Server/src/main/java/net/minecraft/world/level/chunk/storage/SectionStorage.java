package net.minecraft.world.level.chunk.storage;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.OptionalDynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import org.slf4j.Logger;

public class SectionStorage<R, P> implements AutoCloseable, ca.spottedleaf.moonrise.patches.chunk_system.level.storage.ChunkSystemSectionStorage { // Paper - rewrite chunk system
    static final Logger LOGGER = LogUtils.getLogger();
    private static final String SECTIONS_TAG = "Sections";
    // Paper - rewrite chunk system
    private final Long2ObjectMap<Optional<R>> storage = new Long2ObjectOpenHashMap<>();
    private final LongLinkedOpenHashSet dirtyChunks = new LongLinkedOpenHashSet();
    private final Codec<P> codec;
    private final Function<R, P> packer;
    private final BiFunction<P, Runnable, R> unpacker;
    private final Function<Runnable, R> factory;
    private final RegistryAccess registryAccess;
    private final ChunkIOErrorReporter errorReporter;
    protected final LevelHeightAccessor levelHeightAccessor;
    private final LongSet loadedChunks = new LongOpenHashSet();
    private final Long2ObjectMap<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> pendingLoads = new Long2ObjectOpenHashMap<>();
    private final Object loadLock = new Object();

    // Paper start - rewrite chunk system
    private final RegionFileStorage regionStorage;

    @Override
    public final RegionFileStorage moonrise$getRegionStorage() {
        return this.regionStorage;
    }

    @Override
    public void moonrise$close() throws IOException {}
    // Paper end - rewrite chunk system

    public SectionStorage(
        SimpleRegionStorage storageAccess,
        Codec<P> codec,
        Function<R, P> serializer,
        BiFunction<P, Runnable, R> deserializer,
        Function<Runnable, R> factory,
        RegistryAccess registryManager,
        ChunkIOErrorReporter errorHandler,
        LevelHeightAccessor world
    ) {
        // Paper - rewrite chunk system
        this.codec = codec;
        this.packer = serializer;
        this.unpacker = deserializer;
        this.factory = factory;
        this.registryAccess = registryManager;
        this.errorReporter = errorHandler;
        this.levelHeightAccessor = world;
        this.regionStorage = storageAccess.worker.storage; // Paper - rewrite chunk system
    }

    protected void tick(BooleanSupplier shouldKeepTicking) {
        LongIterator longIterator = this.dirtyChunks.iterator();

        while (longIterator.hasNext() && shouldKeepTicking.getAsBoolean()) {
            ChunkPos chunkPos = new ChunkPos(longIterator.nextLong());
            longIterator.remove();
            this.writeChunk(chunkPos);
        }

        this.unpackPendingLoads();
    }

    private void unpackPendingLoads() {
        synchronized (this.loadLock) {
            Iterator<Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>>> iterator = Long2ObjectMaps.fastIterator(this.pendingLoads);

            while (iterator.hasNext()) {
                Entry<CompletableFuture<Optional<SectionStorage.PackedChunk<P>>>> entry = iterator.next();
                Optional<SectionStorage.PackedChunk<P>> optional = entry.getValue().getNow(null);
                if (optional != null) {
                    long l = entry.getLongKey();
                    this.unpackChunk(new ChunkPos(l), optional.orElse(null));
                    iterator.remove();
                    this.loadedChunks.add(l);
                }
            }
        }
    }

    public void flushAll() {
        if (!this.dirtyChunks.isEmpty()) {
            this.dirtyChunks.forEach(chunkPos -> this.writeChunk(new ChunkPos(chunkPos)));
            this.dirtyChunks.clear();
        }
    }

    public boolean hasWork() {
        return !this.dirtyChunks.isEmpty();
    }

    @Nullable
    public Optional<R> get(long pos) { // Paper - public
        return this.storage.get(pos);
    }

    public Optional<R> getOrLoad(long pos) { // Paper - public
        if (this.outsideStoredRange(pos)) {
            return Optional.empty();
        } else {
            Optional<R> optional = this.get(pos);
            if (optional != null) {
                return optional;
            } else {
                this.unpackChunk(SectionPos.of(pos).chunk());
                optional = this.get(pos);
                if (optional == null) {
                    throw (IllegalStateException)Util.pauseInIde(new IllegalStateException());
                } else {
                    return optional;
                }
            }
        }
    }

    protected boolean outsideStoredRange(long pos) {
        int i = SectionPos.sectionToBlockCoord(SectionPos.y(pos));
        return this.levelHeightAccessor.isOutsideBuildHeight(i);
    }

    protected R getOrCreate(long pos) {
        if (this.outsideStoredRange(pos)) {
            throw (IllegalArgumentException)Util.pauseInIde(new IllegalArgumentException("sectionPos out of bounds"));
        } else {
            Optional<R> optional = this.getOrLoad(pos);
            if (optional.isPresent()) {
                return optional.get();
            } else {
                R object = this.factory.apply(() -> this.setDirty(pos));
                this.storage.put(pos, Optional.of(object));
                return object;
            }
        }
    }

    public CompletableFuture<?> prefetch(ChunkPos chunkPos) {
        synchronized (this.loadLock) {
            long l = chunkPos.toLong();
            return this.loadedChunks.contains(l)
                ? CompletableFuture.completedFuture(null)
                : this.pendingLoads.computeIfAbsent(l, pos -> this.tryRead(chunkPos));
        }
    }

    private void unpackChunk(ChunkPos chunkPos) {
        long l = chunkPos.toLong();
        CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> completableFuture;
        synchronized (this.loadLock) {
            if (!this.loadedChunks.add(l)) {
                return;
            }

            completableFuture = this.pendingLoads.computeIfAbsent(l, pos -> this.tryRead(chunkPos));
        }

        this.unpackChunk(chunkPos, completableFuture.join().orElse(null));
        synchronized (this.loadLock) {
            this.pendingLoads.remove(l);
        }
    }

    private CompletableFuture<Optional<SectionStorage.PackedChunk<P>>> tryRead(ChunkPos chunkPos) {
        throw new IllegalStateException("Only chunk system can write state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private void unpackChunk(ChunkPos chunkPos, @Nullable SectionStorage.PackedChunk<P> result) {
        throw new IllegalStateException("Only chunk system can load in state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private void writeChunk(ChunkPos pos) {
        throw new IllegalStateException("Only chunk system can write state, offending class:" + this.getClass().getName()); // Paper - rewrite chunk system
    }

    private <T> Dynamic<T> writeChunk(ChunkPos chunkPos, DynamicOps<T> ops) {
        Map<T, T> map = Maps.newHashMap();

        for (int i = this.levelHeightAccessor.getMinSectionY(); i <= this.levelHeightAccessor.getMaxSectionY(); i++) {
            long l = getKey(chunkPos, i);
            Optional<R> optional = this.storage.get(l);
            if (optional != null && !optional.isEmpty()) {
                DataResult<T> dataResult = this.codec.encodeStart(ops, this.packer.apply(optional.get()));
                String string = Integer.toString(i);
                dataResult.resultOrPartial(LOGGER::error).ifPresent(value -> map.put(ops.createString(string), (T)value));
            }
        }

        return new Dynamic<>(
            ops,
            ops.createMap(
                ImmutableMap.of(
                    ops.createString("Sections"),
                    ops.createMap(map),
                    ops.createString("DataVersion"),
                    ops.createInt(SharedConstants.getCurrentVersion().getDataVersion().getVersion())
                )
            )
        );
    }

    private static long getKey(ChunkPos chunkPos, int y) {
        return SectionPos.asLong(chunkPos.x, y, chunkPos.z);
    }

    protected void onSectionLoad(long pos) {
    }

    public void setDirty(long pos) { // Paper - public
        Optional<R> optional = this.storage.get(pos);
        if (optional != null && !optional.isEmpty()) {
            this.dirtyChunks.add(ChunkPos.asLong(SectionPos.x(pos), SectionPos.z(pos)));
        } else {
            LOGGER.warn("No data for position: {}", SectionPos.of(pos));
        }
    }

    static int getVersion(Dynamic<?> dynamic) {
        return dynamic.get("DataVersion").asInt(1945);
    }

    public void flush(ChunkPos pos) {
        if (this.dirtyChunks.remove(pos.toLong())) {
            this.writeChunk(pos);
        }
    }

    @Override
    public void close() throws IOException {
        this.moonrise$close(); // Paper - rewrite chunk system
    }

    static record PackedChunk<T>(Int2ObjectMap<T> sectionsByY, boolean versionChanged) {
        public static <T> SectionStorage.PackedChunk<T> parse(
            Codec<T> sectionCodec, DynamicOps<Tag> ops, Tag nbt, SimpleRegionStorage storage, LevelHeightAccessor world
        ) {
            Dynamic<Tag> dynamic = new Dynamic<>(ops, nbt);
            int i = SectionStorage.getVersion(dynamic);
            int j = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
            boolean bl = i != j;
            Dynamic<Tag> dynamic2 = storage.upgradeChunkTag(dynamic, i);
            OptionalDynamic<Tag> optionalDynamic = dynamic2.get("Sections");
            Int2ObjectMap<T> int2ObjectMap = new Int2ObjectOpenHashMap<>();

            for (int k = world.getMinSectionY(); k <= world.getMaxSectionY(); k++) {
                Optional<T> optional = optionalDynamic.get(Integer.toString(k))
                    .result()
                    .flatMap(section -> sectionCodec.parse((Dynamic<Tag>)section).resultOrPartial(SectionStorage.LOGGER::error));
                if (optional.isPresent()) {
                    int2ObjectMap.put(k, optional.get());
                }
            }

            return new SectionStorage.PackedChunk<>(int2ObjectMap, bl);
        }
    }
}
