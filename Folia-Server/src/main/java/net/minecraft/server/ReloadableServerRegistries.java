package net.minecraft.server;

import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import net.minecraft.Util;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.ValidationContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import org.slf4j.Logger;

public class ReloadableServerRegistries {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RegistrationInfo DEFAULT_REGISTRATION_INFO = new RegistrationInfo(Optional.empty(), Lifecycle.experimental());

    public static CompletableFuture<ReloadableServerRegistries.LoadResult> reload(
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries,
        List<Registry.PendingTags<?>> pendingTagLoads,
        ResourceManager resourceManager,
        Executor prepareExecutor
    ) {
        List<HolderLookup.RegistryLookup<?>> list = TagLoader.buildUpdatedLookups(
            dynamicRegistries.getAccessForLoading(RegistryLayer.RELOADABLE), pendingTagLoads
        );
        HolderLookup.Provider provider = HolderLookup.Provider.create(list.stream());
        RegistryOps<JsonElement> registryOps = provider.createSerializationContext(JsonOps.INSTANCE);
        final io.papermc.paper.registry.data.util.Conversions conversions = new io.papermc.paper.registry.data.util.Conversions(registryOps.lookupProvider); // Paper
        List<CompletableFuture<WritableRegistry<?>>> list2 = LootDataType.values()
            .map(type -> scheduleRegistryLoad((LootDataType<?>)type, registryOps, resourceManager, prepareExecutor, conversions)) // Paper
            .toList();
        CompletableFuture<List<WritableRegistry<?>>> completableFuture = Util.sequence(list2);
        return completableFuture.thenApplyAsync(
            registries -> createAndValidateFullContext(dynamicRegistries, provider, (List<WritableRegistry<?>>)registries), prepareExecutor
        );
    }

    private static <T> CompletableFuture<WritableRegistry<?>> scheduleRegistryLoad(
        LootDataType<T> type, RegistryOps<JsonElement> ops, ResourceManager resourceManager, Executor prepareExecutor, io.papermc.paper.registry.data.util.Conversions conversions // Paper
    ) {
        return CompletableFuture.supplyAsync(() -> {
            WritableRegistry<T> writableRegistry = new MappedRegistry<>(type.registryKey(), Lifecycle.experimental());
            io.papermc.paper.registry.PaperRegistryAccess.instance().registerReloadableRegistry(type.registryKey(), writableRegistry); // Paper - register reloadable registry
            Map<ResourceLocation, T> map = new HashMap<>();
            SimpleJsonResourceReloadListener.scanDirectory(resourceManager, type.registryKey(), ops, type.codec(), map);
            map.forEach((id, value) -> io.papermc.paper.registry.PaperRegistryListenerManager.INSTANCE.registerWithListeners(writableRegistry, ResourceKey.create(type.registryKey(), id), value, DEFAULT_REGISTRATION_INFO, conversions)); // Paper - register with listeners
            TagLoader.loadTagsForRegistry(resourceManager, writableRegistry, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.RELOAD); // Paper - tag life cycle - reload
            return writableRegistry;
        }, prepareExecutor);
    }

    private static ReloadableServerRegistries.LoadResult createAndValidateFullContext(
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries, HolderLookup.Provider nonReloadables, List<WritableRegistry<?>> registries
    ) {
        LayeredRegistryAccess<RegistryLayer> layeredRegistryAccess = createUpdatedRegistries(dynamicRegistries, registries);
        HolderLookup.Provider provider = concatenateLookups(nonReloadables, layeredRegistryAccess.getLayer(RegistryLayer.RELOADABLE));
        validateLootRegistries(provider);
        return new ReloadableServerRegistries.LoadResult(layeredRegistryAccess, provider);
    }

    private static HolderLookup.Provider concatenateLookups(HolderLookup.Provider first, HolderLookup.Provider second) {
        return HolderLookup.Provider.create(Stream.concat(first.listRegistries(), second.listRegistries()));
    }

    private static void validateLootRegistries(HolderLookup.Provider registries) {
        ProblemReporter.Collector collector = new ProblemReporter.Collector();
        ValidationContext validationContext = new ValidationContext(collector, LootContextParamSets.ALL_PARAMS, registries);
        LootDataType.values().forEach(type -> validateRegistry(validationContext, (LootDataType<?>)type, registries));
        collector.get().forEach((id, error) -> LOGGER.warn("Found loot table element validation problem in {}: {}", id, error));
    }

    private static LayeredRegistryAccess<RegistryLayer> createUpdatedRegistries(
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries, List<WritableRegistry<?>> registries
    ) {
        return dynamicRegistries.replaceFrom(RegistryLayer.RELOADABLE, new RegistryAccess.ImmutableRegistryAccess(registries).freeze());
    }

    private static <T> void validateRegistry(ValidationContext reporter, LootDataType<T> lootDataType, HolderLookup.Provider registries) {
        HolderLookup<T> holderLookup = registries.lookupOrThrow(lootDataType.registryKey());
        holderLookup.listElements().forEach(entry -> lootDataType.runValidation(reporter, entry.key(), entry.value()));
    }

    public static class Holder {
        private final HolderLookup.Provider registries;

        public Holder(HolderLookup.Provider registries) {
            this.registries = registries;
        }

        public HolderGetter.Provider lookup() {
            return this.registries;
        }

        public Collection<ResourceLocation> getKeys(ResourceKey<? extends Registry<?>> registryRef) {
            return this.registries.lookupOrThrow(registryRef).listElementIds().map(ResourceKey::location).toList();
        }

        public LootTable getLootTable(ResourceKey<LootTable> key) {
            return this.registries
                .lookup(Registries.LOOT_TABLE)
                .flatMap(registryEntryLookup -> registryEntryLookup.get(key))
                .map(net.minecraft.core.Holder::value)
                .orElse(LootTable.EMPTY);
        }
    }

    public static record LoadResult(LayeredRegistryAccess<RegistryLayer> layers, HolderLookup.Provider lookupWithUpdatedTags) {
    }
}
