package net.minecraft.world.level.saveddata;

import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;

public abstract class SavedData {
    private volatile boolean dirty; // Folia - make map data thread-safe

    public abstract CompoundTag save(CompoundTag nbt, HolderLookup.Provider registries);

    public void setDirty() {
        this.setDirty(true);
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag compoundTag = new CompoundTag();
        this.setDirty(false); // Folia - make map data thread-safe - move before save, so that any changes after are not lost
        compoundTag.put("data", this.save(new CompoundTag(), registries));
        NbtUtils.addCurrentDataVersion(compoundTag);
        // Folia - make map data thread-safe - move before save, so that any changes after are not lost
        return compoundTag;
    }

    public static record Factory<T extends SavedData>(
        Supplier<T> constructor, BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer, DataFixTypes type
    ) {
    }
}
