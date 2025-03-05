package net.minecraft.world.level.storage;

import com.google.common.collect.Iterables;
import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.FastBufferedInputStream;
import net.minecraft.util.Mth;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

public class DimensionDataStorage implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    public final Map<String, Optional<SavedData>> cache = new HashMap<>();
    private final DataFixer fixerUpper;
    private final HolderLookup.Provider registries;
    private final Path dataFolder;
    private CompletableFuture<?> pendingWriteFuture = CompletableFuture.completedFuture(null);

    public DimensionDataStorage(Path directory, DataFixer dataFixer, HolderLookup.Provider registries) {
        this.fixerUpper = dataFixer;
        this.dataFolder = directory;
        this.registries = registries;
    }

    private Path getDataFile(String id) {
        return this.dataFolder.resolve(id + ".dat");
    }

    public <T extends SavedData> T computeIfAbsent(SavedData.Factory<T> type, String id) {
        synchronized (this.cache) { // Folia - make map data thread-safe
        T savedData = this.get(type, id);
        if (savedData != null) {
            return savedData;
        } else {
            T savedData2 = (T)type.constructor().get();
            this.set(id, savedData2);
            return savedData2;
        }
        } // Folia - make map data thread-safe
    }

    @Nullable
    public <T extends SavedData> T get(SavedData.Factory<T> type, String id) {
        synchronized (this.cache) { // Folia - make map data thread-safe
        Optional<SavedData> optional = this.cache.get(id);
        if (optional == null) {
            optional = Optional.ofNullable(this.readSavedData(type.deserializer(), type.type(), id));
            this.cache.put(id, optional);
        }

        return (T)optional.orElse(null);
        } // Folia - make map data thread-safe
    }

    @Nullable
    private <T extends SavedData> T readSavedData(BiFunction<CompoundTag, HolderLookup.Provider, T> readFunction, DataFixTypes dataFixTypes, String id) {
        try {
            Path path = this.getDataFile(id);
            if (Files.exists(path)) {
                CompoundTag compoundTag = this.readTagFromDisk(id, dataFixTypes, SharedConstants.getCurrentVersion().getDataVersion().getVersion());
                return readFunction.apply(compoundTag.getCompound("data"), this.registries);
            }
        } catch (Exception var6) {
            LOGGER.error("Error loading saved data: {}", id, var6);
        }

        return null;
    }

    public void set(String id, SavedData state) {
        synchronized (this.cache) { // Folia - make map data thread-safe
        this.cache.put(id, Optional.of(state));
        state.setDirty();
        } // Folia - make map data thread-safe
    }

    public CompoundTag readTagFromDisk(String id, DataFixTypes dataFixTypes, int currentSaveVersion) throws IOException {
        CompoundTag var8;
        try (
            InputStream inputStream = Files.newInputStream(this.getDataFile(id));
            PushbackInputStream pushbackInputStream = new PushbackInputStream(new FastBufferedInputStream(inputStream), 2);
        ) {
            CompoundTag compoundTag;
            if (this.isGzip(pushbackInputStream)) {
                compoundTag = NbtIo.readCompressed(pushbackInputStream, NbtAccounter.unlimitedHeap());
            } else {
                try (DataInputStream dataInputStream = new DataInputStream(pushbackInputStream)) {
                    compoundTag = NbtIo.read(dataInputStream);
                }
            }

            int i = NbtUtils.getDataVersion(compoundTag, 1343);
            var8 = dataFixTypes.update(this.fixerUpper, compoundTag, i, currentSaveVersion);
        }

        return var8;
    }

    private boolean isGzip(PushbackInputStream stream) throws IOException {
        byte[] bs = new byte[2];
        boolean bl = false;
        int i = stream.read(bs, 0, 2);
        if (i == 2) {
            int j = (bs[1] & 255) << 8 | bs[0] & 255;
            if (j == 35615) {
                bl = true;
            }
        }

        if (i != 0) {
            stream.unread(bs, 0, i);
        }

        return bl;
    }

    public CompletableFuture<?> scheduleSave() {
        Map<Path, CompoundTag> map = this.collectDirtyTagsToSave();
        if (map.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        } else {
            int i = Util.maxAllowedExecutorThreads();
            int j = map.size();
            if (false && j > i) { // Paper - Separate dimension data IO pool; just throw them into the fixed pool queue
                this.pendingWriteFuture = this.pendingWriteFuture.thenCompose(object -> {
                    List<CompletableFuture<?>> list = new ArrayList<>(i);
                    int k = Mth.positiveCeilDiv(j, i);

                    for (List<Entry<Path, CompoundTag>> list2 : Iterables.partition(map.entrySet(), k)) {
                        list.add(CompletableFuture.runAsync(() -> {
                            for (Entry<Path, CompoundTag> entry : list2) {
                                tryWrite(entry.getKey(), entry.getValue());
                            }
                        }, Util.ioPool()));
                    }

                    return CompletableFuture.allOf(list.toArray(CompletableFuture[]::new));
                });
            } else {
                this.pendingWriteFuture = this.pendingWriteFuture
                    .thenCompose(
                        v -> CompletableFuture.allOf(
                                map.entrySet()
                                    .stream()
                                    .map(entry -> CompletableFuture.runAsync(() -> tryWrite(entry.getKey(), entry.getValue()), Util.DIMENSION_DATA_IO_POOL)) // Paper - Separate dimension data IO pool
                                    .toArray(CompletableFuture[]::new)
                            )
                    );
            }

            return this.pendingWriteFuture;
        }
    }

    private Map<Path, CompoundTag> collectDirtyTagsToSave() {
        Map<Path, CompoundTag> map = new Object2ObjectArrayMap<>();
        this.cache.forEach((id, state) -> state.filter(SavedData::isDirty).ifPresent(state2 -> map.put(this.getDataFile(id), state2.save(this.registries))));
        return map;
    }

    private static void tryWrite(Path path, CompoundTag nbt) {
        try {
            NbtIo.writeCompressed(nbt, path);
        } catch (IOException var3) {
            LOGGER.error("Could not save data to {}", path.getFileName(), var3);
        }
    }

    public void saveAndJoin() {
        this.scheduleSave().join();
    }

    @Override
    public void close() {
        this.saveAndJoin();
    }
}
