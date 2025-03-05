package net.minecraft.tags;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.DependencySorter;
import org.slf4j.Logger;

public class TagLoader<T> {
    private static final Logger LOGGER = LogUtils.getLogger();
    final TagLoader.ElementLookup<T> elementLookup;
    private final String directory;

    public TagLoader(TagLoader.ElementLookup<T> entrySupplier, String dataType) {
        this.elementLookup = entrySupplier;
        this.directory = dataType;
    }

    public Map<ResourceLocation, List<TagLoader.EntryWithSource>> load(ResourceManager resourceManager) {
        Map<ResourceLocation, List<TagLoader.EntryWithSource>> map = new HashMap<>();
        FileToIdConverter fileToIdConverter = FileToIdConverter.json(this.directory);

        for (Entry<ResourceLocation, List<Resource>> entry : fileToIdConverter.listMatchingResourceStacks(resourceManager).entrySet()) {
            ResourceLocation resourceLocation = entry.getKey();
            ResourceLocation resourceLocation2 = fileToIdConverter.fileToId(resourceLocation);

            for (Resource resource : entry.getValue()) {
                try (Reader reader = resource.openAsReader()) {
                    JsonElement jsonElement = JsonParser.parseReader(reader);
                    List<TagLoader.EntryWithSource> list = map.computeIfAbsent(resourceLocation2, id -> new ArrayList<>());
                    TagFile tagFile = TagFile.CODEC.parse(new Dynamic<>(JsonOps.INSTANCE, jsonElement)).getOrThrow();
                    if (tagFile.replace()) {
                        list.clear();
                    }

                    String string = resource.sourcePackId();
                    tagFile.entries().forEach(entryx -> list.add(new TagLoader.EntryWithSource(entryx, string)));
                } catch (Exception var17) {
                    LOGGER.error("Couldn't read tag list {} from {} in data pack {}", resourceLocation2, resourceLocation, resource.sourcePackId(), var17);
                }
            }
        }

        return map;
    }

    private Either<List<TagLoader.EntryWithSource>, List<T>> tryBuildTag(TagEntry.Lookup<T> valueGetter, List<TagLoader.EntryWithSource> entries) {
        SequencedSet<T> sequencedSet = new LinkedHashSet<>();
        List<TagLoader.EntryWithSource> list = new ArrayList<>();

        for (TagLoader.EntryWithSource entryWithSource : entries) {
            if (!entryWithSource.entry().build(valueGetter, sequencedSet::add)) {
                list.add(entryWithSource);
            }
        }

        return list.isEmpty() ? Either.right(List.copyOf(sequencedSet)) : Either.left(list);
    }

    // Paper start - fire tag registrar events
    public Map<ResourceLocation, List<T>> build(Map<ResourceLocation, List<TagLoader.EntryWithSource>> tags, @Nullable io.papermc.paper.tag.TagEventConfig<T, ?> eventConfig) {
        tags = io.papermc.paper.tag.PaperTagListenerManager.INSTANCE.firePreFlattenEvent(tags, eventConfig);
    // Paper end - fire tag registrar event
        final Map<ResourceLocation, List<T>> map = new HashMap<>();
        TagEntry.Lookup<T> lookup = new TagEntry.Lookup<T>() {
            @Nullable
            @Override
            public T element(ResourceLocation id, boolean required) {
                return (T)TagLoader.this.elementLookup.get(id, required).orElse(null);
            }

            @Nullable
            @Override
            public Collection<T> tag(ResourceLocation id) {
                return map.get(id);
            }
        };
        DependencySorter<ResourceLocation, TagLoader.SortingEntry> dependencySorter = new DependencySorter<>();
        tags.forEach((id, entries) -> dependencySorter.addEntry(id, new TagLoader.SortingEntry((List<TagLoader.EntryWithSource>)entries)));
        dependencySorter.orderByDependencies(
            (id, dependencies) -> this.tryBuildTag(lookup, dependencies.entries)
                    .ifLeft(
                        missingReferences -> LOGGER.error(
                                "Couldn't load tag {} as it is missing following references: {}",
                                id,
                                missingReferences.stream().map(Objects::toString).collect(Collectors.joining(", "))
                            )
                    )
                    .ifRight(values -> map.put(id, (List<T>)values))
        );
        return io.papermc.paper.tag.PaperTagListenerManager.INSTANCE.firePostFlattenEvent(map, eventConfig); // Paper - fire tag registrar events
    }

    public static <T> void loadTagsFromNetwork(TagNetworkSerialization.NetworkPayload tags, WritableRegistry<T> registry) {
        tags.resolve(registry).tags.forEach(registry::bindTag);
    }

    public static List<Registry.PendingTags<?>> loadTagsForExistingRegistries(ResourceManager resourceManager, RegistryAccess registryManager) {
    // Paper start - tag lifecycle - add cause
        return loadTagsForExistingRegistries(resourceManager, registryManager, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL);
    }
    public static List<Registry.PendingTags<?>> loadTagsForExistingRegistries(ResourceManager resourceManager, RegistryAccess registryManager, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause cause) {
    // Paper end - tag lifecycle - add cause
        return registryManager.registries()
            .map(registry -> loadPendingTags(resourceManager, registry.value(), cause)) // Paper - tag lifecycle - add cause
            .flatMap(Optional::stream)
            .collect(Collectors.toUnmodifiableList());
    }

    public static <T> void loadTagsForRegistry(ResourceManager resourceManager, WritableRegistry<T> registry) {
    // Paper start - tag lifecycle - add registrar event cause
        loadTagsForRegistry(resourceManager, registry, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL);
    }
    public static <T> void loadTagsForRegistry(ResourceManager resourceManager, WritableRegistry<T> registry, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause cause) {
    // Paper end - tag lifecycle - add registrar event cause
        ResourceKey<? extends Registry<T>> resourceKey = registry.key();
        TagLoader<Holder<T>> tagLoader = new TagLoader<>(TagLoader.ElementLookup.fromWritableRegistry(registry), Registries.tagsDirPath(resourceKey));
        tagLoader.build(tagLoader.load(resourceManager), io.papermc.paper.tag.PaperTagListenerManager.INSTANCE.createEventConfig(registry, cause)).forEach((id, entries) -> registry.bindTag(TagKey.create(resourceKey, id), (List<Holder<T>>)entries)); // Paper - tag lifecycle - add registrar event cause
    }

    private static <T> Map<TagKey<T>, List<Holder<T>>> wrapTags(ResourceKey<? extends Registry<T>> registryRef, Map<ResourceLocation, List<Holder<T>>> tags) {
        return tags.entrySet().stream().collect(Collectors.toUnmodifiableMap(entry -> TagKey.create(registryRef, entry.getKey()), Entry::getValue));
    }

    private static <T> Optional<Registry.PendingTags<T>> loadPendingTags(ResourceManager resourceManager, Registry<T> registry, io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause cause) { // Paper - add registrar event cause
        ResourceKey<? extends Registry<T>> resourceKey = registry.key();
        TagLoader<Holder<T>> tagLoader = new TagLoader<>(
            (TagLoader.ElementLookup<Holder<T>>)TagLoader.ElementLookup.fromFrozenRegistry(registry), Registries.tagsDirPath(resourceKey)
        );
        TagLoader.LoadResult<T> loadResult = new TagLoader.LoadResult<>(resourceKey, wrapTags(registry.key(), tagLoader.build(tagLoader.load(resourceManager), io.papermc.paper.tag.PaperTagListenerManager.INSTANCE.createEventConfig(registry, cause)))); // Paper - add registrar event cause
        return loadResult.tags().isEmpty() ? Optional.empty() : Optional.of(registry.prepareTagReload(loadResult));
    }

    public static List<HolderLookup.RegistryLookup<?>> buildUpdatedLookups(RegistryAccess.Frozen registryManager, List<Registry.PendingTags<?>> tagLoads) {
        List<HolderLookup.RegistryLookup<?>> list = new ArrayList<>();
        registryManager.registries().forEach(registry -> {
            Registry.PendingTags<?> pendingTags = findTagsForRegistry(tagLoads, registry.key());
            list.add((HolderLookup.RegistryLookup<?>)(pendingTags != null ? pendingTags.lookup() : registry.value()));
        });
        return list;
    }

    @Nullable
    private static Registry.PendingTags<?> findTagsForRegistry(List<Registry.PendingTags<?>> pendingTags, ResourceKey<? extends Registry<?>> registryRef) {
        for (Registry.PendingTags<?> pendingTags2 : pendingTags) {
            if (pendingTags2.key() == registryRef) {
                return pendingTags2;
            }
        }

        return null;
    }

    public interface ElementLookup<T> {
        Optional<? extends T> get(ResourceLocation id, boolean required);

        static <T> TagLoader.ElementLookup<? extends Holder<T>> fromFrozenRegistry(Registry<T> registry) {
            return (id, required) -> registry.get(id);
        }

        static <T> TagLoader.ElementLookup<Holder<T>> fromWritableRegistry(WritableRegistry<T> registry) {
            HolderGetter<T> holderGetter = registry.createRegistrationLookup();
            return (id, required) -> ((HolderGetter<T>)(required ? holderGetter : registry)).get(ResourceKey.create(registry.key(), id));
        }
    }

    public static record EntryWithSource(TagEntry entry, String source) {
        @Override
        public String toString() {
            return this.entry + " (from " + this.source + ")";
        }
    }

    public static record LoadResult<T>(ResourceKey<? extends Registry<T>> key, Map<TagKey<T>, List<Holder<T>>> tags) {
    }

    static record SortingEntry(List<TagLoader.EntryWithSource> entries) implements DependencySorter.Entry<ResourceLocation> {
        @Override
        public void visitRequiredDependencies(Consumer<ResourceLocation> callback) {
            this.entries.forEach(entry -> entry.entry.visitRequiredDependencies(callback));
        }

        @Override
        public void visitOptionalDependencies(Consumer<ResourceLocation> callback) {
            this.entries.forEach(entry -> entry.entry.visitOptionalDependencies(callback));
        }
    }
}
