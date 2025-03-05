package net.minecraft.world.level.storage.loot;

import com.mojang.serialization.Codec;
import java.util.stream.Stream;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;

// CraftBukkit start
import org.bukkit.craftbukkit.CraftLootTable;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
// CraftBukkit end

public record LootDataType<T>(ResourceKey<Registry<T>> registryKey, Codec<T> codec, LootDataType.Validator<T> validator) {

    public static final LootDataType<LootItemCondition> PREDICATE = new LootDataType<>(Registries.PREDICATE, LootItemCondition.DIRECT_CODEC, createSimpleValidator());
    public static final LootDataType<LootItemFunction> MODIFIER = new LootDataType<>(Registries.ITEM_MODIFIER, LootItemFunctions.ROOT_CODEC, createSimpleValidator());
    public static final LootDataType<LootTable> TABLE = new LootDataType<>(Registries.LOOT_TABLE, LootTable.DIRECT_CODEC, createLootTableValidator());

    public void runValidation(ValidationContext reporter, ResourceKey<T> key, T value) {
        this.validator.run(reporter, key, value);
    }

    public static Stream<LootDataType<?>> values() {
        return Stream.of(LootDataType.PREDICATE, LootDataType.MODIFIER, LootDataType.TABLE);
    }

    private static <T extends LootContextUser> LootDataType.Validator<T> createSimpleValidator() {
        return (lootcollector, resourcekey, lootitemuser) -> {
            lootitemuser.validate(lootcollector.enterElement("{" + String.valueOf(resourcekey.registry()) + "/" + String.valueOf(resourcekey.location()) + "}", resourcekey));
        };
    }

    private static LootDataType.Validator<LootTable> createLootTableValidator() {
        return (lootcollector, resourcekey, loottable) -> {
            loottable.validate(lootcollector.setContextKeySet(loottable.getParamSet()).enterElement("{" + String.valueOf(resourcekey.registry()) + "/" + String.valueOf(resourcekey.location()) + "}", resourcekey));
            loottable.craftLootTable = new CraftLootTable(CraftNamespacedKey.fromMinecraft(resourcekey.location()), loottable); // CraftBukkit
        };
    }

    @FunctionalInterface
    public interface Validator<T> {

        void run(ValidationContext reporter, ResourceKey<T> key, T value);
    }
}
