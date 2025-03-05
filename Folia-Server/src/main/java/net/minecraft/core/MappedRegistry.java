package net.minecraft.core;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.ImmutableMap.Builder;
import com.mojang.serialization.Lifecycle;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.RandomSource;

public class MappedRegistry<T> implements WritableRegistry<T> {
    private final ResourceKey<? extends Registry<T>> key;
    private final ObjectList<Holder.Reference<T>> byId = new ObjectArrayList<>(256);
    private final Reference2IntMap<T> toId = Util.make(new Reference2IntOpenHashMap<>(2048), map -> map.defaultReturnValue(-1)); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<ResourceLocation, Holder.Reference<T>> byLocation = new HashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<ResourceKey<T>, Holder.Reference<T>> byKey = new HashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<T, Holder.Reference<T>> byValue = new IdentityHashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private final Map<ResourceKey<T>, RegistrationInfo> registrationInfos = new IdentityHashMap<>(2048); // Paper - Perf: Use bigger expected size to reduce collisions
    private Lifecycle registryLifecycle;
    private final Map<TagKey<T>, HolderSet.Named<T>> frozenTags = new IdentityHashMap<>();
    MappedRegistry.TagSet<T> allTags = MappedRegistry.TagSet.unbound();
    private boolean frozen;
    @Nullable
    private Map<T, Holder.Reference<T>> unregisteredIntrusiveHolders;

    @Override
    public Stream<HolderSet.Named<T>> listTags() {
        return this.getTags();
    }

    // Paper start - fluid method optimisations
    private void injectFluidRegister(
        final ResourceKey<?> resourceKey,
        final T object
    ) {
        if (resourceKey.registryKey() == (Object)net.minecraft.core.registries.Registries.FLUID) {
            for (final net.minecraft.world.level.material.FluidState possibleState : ((net.minecraft.world.level.material.Fluid)object).getStateDefinition().getPossibleStates()) {
                ((ca.spottedleaf.moonrise.patches.fluid.FluidFluidState)(Object)possibleState).moonrise$initCaches();
            }
        }
    }
    // Paper end - fluid method optimisations

    public MappedRegistry(ResourceKey<? extends Registry<T>> key, Lifecycle lifecycle) {
        this(key, lifecycle, false);
    }

    public MappedRegistry(ResourceKey<? extends Registry<T>> key, Lifecycle lifecycle, boolean intrusive) {
        this.key = key;
        this.registryLifecycle = lifecycle;
        if (intrusive) {
            this.unregisteredIntrusiveHolders = new IdentityHashMap<>();
        }
    }

    @Override
    public ResourceKey<? extends Registry<T>> key() {
        return this.key;
    }

    @Override
    public String toString() {
        return "Registry[" + this.key + " (" + this.registryLifecycle + ")]";
    }

    private void validateWrite() {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen");
        }
    }

    public void validateWrite(ResourceKey<T> key) {
        if (this.frozen) {
            throw new IllegalStateException("Registry is already frozen (trying to add key " + key + ")");
        }
    }

    @Override
    public Holder.Reference<T> register(ResourceKey<T> key, T value, RegistrationInfo info) {
        this.validateWrite(key);
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        if (this.byLocation.containsKey(key.location())) {
            throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate key '" + key + "' to registry"));
        } else if (this.byValue.containsKey(value)) {
            throw (IllegalStateException)Util.pauseInIde(new IllegalStateException("Adding duplicate value '" + value + "' to registry"));
        } else {
            Holder.Reference<T> reference;
            if (this.unregisteredIntrusiveHolders != null) {
                reference = this.unregisteredIntrusiveHolders.remove(value);
                if (reference == null) {
                    throw new AssertionError("Missing intrusive holder for " + key + ":" + value);
                }

                reference.bindKey(key);
            } else {
                reference = this.byKey.computeIfAbsent(key, k -> Holder.Reference.createStandAlone(this, (ResourceKey<T>)k));
            }

            this.byKey.put(key, reference);
            this.byLocation.put(key.location(), reference);
            this.byValue.put(value, reference);
            int i = this.byId.size();
            this.byId.add(reference);
            this.toId.put(value, i);
            this.registrationInfos.put(key, info);
            this.registryLifecycle = this.registryLifecycle.add(info.lifecycle());
            this.injectFluidRegister(key, value); // Paper - fluid method optimisations
            return reference;
        }
    }

    @Nullable
    @Override
    public ResourceLocation getKey(T value) {
        Holder.Reference<T> reference = this.byValue.get(value);
        return reference != null ? reference.key().location() : null;
    }

    @Override
    public Optional<ResourceKey<T>> getResourceKey(T entry) {
        return Optional.ofNullable(this.byValue.get(entry)).map(Holder.Reference::key);
    }

    @Override
    public int getId(@Nullable T value) {
        return this.toId.getInt(value);
    }

    @Nullable
    @Override
    public T getValue(@Nullable ResourceKey<T> key) {
        return getValueFromNullable(this.byKey.get(key));
    }

    @Nullable
    @Override
    public T byId(int index) {
        return index >= 0 && index < this.byId.size() ? this.byId.get(index).value() : null;
    }

    @Override
    public Optional<Holder.Reference<T>> get(int rawId) {
        return rawId >= 0 && rawId < this.byId.size() ? Optional.ofNullable(this.byId.get(rawId)) : Optional.empty();
    }

    @Override
    public Optional<Holder.Reference<T>> get(ResourceLocation id) {
        return Optional.ofNullable(this.byLocation.get(id));
    }

    @Override
    public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
        return Optional.ofNullable(this.byKey.get(key));
    }

    @Override
    public Optional<Holder.Reference<T>> getAny() {
        return this.byId.isEmpty() ? Optional.empty() : Optional.of(this.byId.getFirst());
    }

    @Override
    public Holder<T> wrapAsHolder(T value) {
        Holder.Reference<T> reference = this.byValue.get(value);
        return (Holder<T>)(reference != null ? reference : Holder.direct(value));
    }

    Holder.Reference<T> getOrCreateHolderOrThrow(ResourceKey<T> key) {
        return this.byKey.computeIfAbsent(key, key2 -> {
            if (this.unregisteredIntrusiveHolders != null) {
                throw new IllegalStateException("This registry can't create new holders without value");
            } else {
                this.validateWrite((ResourceKey<T>)key2);
                return Holder.Reference.createStandAlone(this, (ResourceKey<T>)key2);
            }
        });
    }

    @Override
    public int size() {
        return this.byKey.size();
    }

    @Override
    public Optional<RegistrationInfo> registrationInfo(ResourceKey<T> key) {
        return Optional.ofNullable(this.registrationInfos.get(key));
    }

    @Override
    public Lifecycle registryLifecycle() {
        return this.registryLifecycle;
    }

    @Override
    public Iterator<T> iterator() {
        return Iterators.transform(this.byId.iterator(), Holder::value);
    }

    @Nullable
    @Override
    public T getValue(@Nullable ResourceLocation id) {
        Holder.Reference<T> reference = this.byLocation.get(id);
        return getValueFromNullable(reference);
    }

    @Nullable
    private static <T> T getValueFromNullable(@Nullable Holder.Reference<T> entry) {
        return entry != null ? entry.value() : null;
    }

    @Override
    public Set<ResourceLocation> keySet() {
        return Collections.unmodifiableSet(this.byLocation.keySet());
    }

    @Override
    public Set<ResourceKey<T>> registryKeySet() {
        return Collections.unmodifiableSet(this.byKey.keySet());
    }

    @Override
    public Set<Entry<ResourceKey<T>, T>> entrySet() {
        return Collections.unmodifiableSet(Maps.transformValues(this.byKey, Holder::value).entrySet());
    }

    @Override
    public Stream<Holder.Reference<T>> listElements() {
        return this.byId.stream();
    }

    @Override
    public Stream<HolderSet.Named<T>> getTags() {
        return this.allTags.getTags();
    }

    HolderSet.Named<T> getOrCreateTagForRegistration(TagKey<T> key) {
        return this.frozenTags.computeIfAbsent(key, this::createTag);
    }

    private HolderSet.Named<T> createTag(TagKey<T> tag) {
        return new HolderSet.Named<>(this, tag);
    }

    @Override
    public boolean isEmpty() {
        return this.byKey.isEmpty();
    }

    @Override
    public Optional<Holder.Reference<T>> getRandom(RandomSource random) {
        return Util.getRandomSafe(this.byId, random);
    }

    @Override
    public boolean containsKey(ResourceLocation id) {
        return this.byLocation.containsKey(id);
    }

    @Override
    public boolean containsKey(ResourceKey<T> key) {
        return this.byKey.containsKey(key);
    }

    @Override
    public Registry<T> freeze() {
        if (this.frozen) {
            return this;
        } else {
            this.frozen = true;
            this.byValue.forEach((value, entry) -> entry.bindValue((T)value));
            List<ResourceLocation> list = this.byKey
                .entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isBound())
                .map(entry -> entry.getKey().location())
                .sorted()
                .toList();
            if (!list.isEmpty()) {
                throw new IllegalStateException("Unbound values in registry " + this.key() + ": " + list);
            } else {
                if (this.unregisteredIntrusiveHolders != null) {
                    if (!this.unregisteredIntrusiveHolders.isEmpty()) {
                        throw new IllegalStateException("Some intrusive holders were not registered: " + this.unregisteredIntrusiveHolders.values());
                    }

                    this.unregisteredIntrusiveHolders = null;
                }

                if (this.allTags.isBound()) {
                    throw new IllegalStateException("Tags already present before freezing");
                } else {
                    List<ResourceLocation> list2 = this.frozenTags
                        .entrySet()
                        .stream()
                        .filter(entry -> !entry.getValue().isBound())
                        .map(entry -> entry.getKey().location())
                        .sorted()
                        .toList();
                    if (!list2.isEmpty()) {
                        throw new IllegalStateException("Unbound tags in registry " + this.key() + ": " + list2);
                    } else {
                        this.allTags = MappedRegistry.TagSet.fromMap(this.frozenTags);
                        this.refreshTagsInHolders();
                        return this;
                    }
                }
            }
        }
    }

    @Override
    public Holder.Reference<T> createIntrusiveHolder(T value) {
        if (this.unregisteredIntrusiveHolders == null) {
            throw new IllegalStateException("This registry can't create intrusive holders");
        } else {
            this.validateWrite();
            return this.unregisteredIntrusiveHolders.computeIfAbsent(value, valuex -> Holder.Reference.createIntrusive(this, (T)valuex));
        }
    }

    @Override
    public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
        return this.allTags.get(tag);
    }

    private Holder.Reference<T> validateAndUnwrapTagElement(TagKey<T> key, Holder<T> entry) {
        if (!entry.canSerializeIn(this)) {
            throw new IllegalStateException("Can't create named set " + key + " containing value " + entry + " from outside registry " + this);
        } else if (entry instanceof Holder.Reference) {
            return (Holder.Reference<T>)entry;
        } else {
            throw new IllegalStateException("Found direct holder " + entry + " value in tag " + key);
        }
    }

    @Override
    public void bindTag(TagKey<T> tag, List<Holder<T>> entries) {
        this.validateWrite();
        this.getOrCreateTagForRegistration(tag).bind(entries);
    }

    void refreshTagsInHolders() {
        Map<Holder.Reference<T>, List<TagKey<T>>> map = new IdentityHashMap<>();
        this.byKey.values().forEach(key -> map.put((Holder.Reference<T>)key, new ArrayList<>()));
        this.allTags.forEach((key, value) -> {
            for (Holder<T> holder : value) {
                Holder.Reference<T> reference = this.validateAndUnwrapTagElement((TagKey<T>)key, holder);
                map.get(reference).add((TagKey<T>)key);
            }
        });
        map.forEach(Holder.Reference::bindTags);
    }

    public void bindAllTagsToEmpty() {
        this.validateWrite();
        this.frozenTags.values().forEach(tag -> tag.bind(List.of()));
    }

    @Override
    public HolderGetter<T> createRegistrationLookup() {
        this.validateWrite();
        return new HolderGetter<T>() {
            @Override
            public Optional<Holder.Reference<T>> get(ResourceKey<T> key) {
                return Optional.of(this.getOrThrow(key));
            }

            @Override
            public Holder.Reference<T> getOrThrow(ResourceKey<T> key) {
                return MappedRegistry.this.getOrCreateHolderOrThrow(key);
            }

            @Override
            public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
                return Optional.of(this.getOrThrow(tag));
            }

            @Override
            public HolderSet.Named<T> getOrThrow(TagKey<T> tag) {
                return MappedRegistry.this.getOrCreateTagForRegistration(tag);
            }
        };
    }

    @Override
    public Registry.PendingTags<T> prepareTagReload(TagLoader.LoadResult<T> tags) {
        if (!this.frozen) {
            throw new IllegalStateException("Invalid method used for tag loading");
        } else {
            Builder<TagKey<T>, HolderSet.Named<T>> builder = ImmutableMap.builder();
            final Map<TagKey<T>, List<Holder<T>>> map = new HashMap<>();
            tags.tags().forEach((key, values) -> {
                HolderSet.Named<T> named = this.frozenTags.get(key);
                if (named == null) {
                    named = this.createTag((TagKey<T>)key);
                }

                builder.put((TagKey<T>)key, named);
                map.put((TagKey<T>)key, List.copyOf(values));
            });
            final ImmutableMap<TagKey<T>, HolderSet.Named<T>> immutableMap = builder.build();
            final HolderLookup.RegistryLookup<T> registryLookup = new HolderLookup.RegistryLookup.Delegate<T>() {
                @Override
                public HolderLookup.RegistryLookup<T> parent() {
                    return MappedRegistry.this;
                }

                @Override
                public Optional<HolderSet.Named<T>> get(TagKey<T> tag) {
                    return Optional.ofNullable(immutableMap.get(tag));
                }

                @Override
                public Stream<HolderSet.Named<T>> listTags() {
                    return immutableMap.values().stream();
                }
            };
            return new Registry.PendingTags<T>() {
                @Override
                public ResourceKey<? extends Registry<? extends T>> key() {
                    return MappedRegistry.this.key();
                }

                @Override
                public int size() {
                    return map.size();
                }

                @Override
                public HolderLookup.RegistryLookup<T> lookup() {
                    return registryLookup;
                }

                @Override
                public void apply() {
                    immutableMap.forEach((tagKey, named) -> {
                        List<Holder<T>> list = map.getOrDefault(tagKey, List.of());
                        named.bind(list);
                    });
                    MappedRegistry.this.allTags = MappedRegistry.TagSet.fromMap(immutableMap);
                    MappedRegistry.this.refreshTagsInHolders();
                }
            };
        }
    }

    interface TagSet<T> {
        static <T> MappedRegistry.TagSet<T> unbound() {
            return new MappedRegistry.TagSet<T>() {
                @Override
                public boolean isBound() {
                    return false;
                }

                @Override
                public Optional<HolderSet.Named<T>> get(TagKey<T> key) {
                    throw new IllegalStateException("Tags not bound, trying to access " + key);
                }

                @Override
                public void forEach(BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> consumer) {
                    throw new IllegalStateException("Tags not bound");
                }

                @Override
                public Stream<HolderSet.Named<T>> getTags() {
                    throw new IllegalStateException("Tags not bound");
                }
            };
        }

        static <T> MappedRegistry.TagSet<T> fromMap(Map<TagKey<T>, HolderSet.Named<T>> map) {
            return new MappedRegistry.TagSet<T>() {
                @Override
                public boolean isBound() {
                    return true;
                }

                @Override
                public Optional<HolderSet.Named<T>> get(TagKey<T> key) {
                    return Optional.ofNullable(map.get(key));
                }

                @Override
                public void forEach(BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> consumer) {
                    map.forEach(consumer);
                }

                @Override
                public Stream<HolderSet.Named<T>> getTags() {
                    return map.values().stream();
                }
            };
        }

        boolean isBound();

        Optional<HolderSet.Named<T>> get(TagKey<T> key);

        void forEach(BiConsumer<? super TagKey<T>, ? super HolderSet.Named<T>> consumer);

        Stream<HolderSet.Named<T>> getTags();
    }
    // Paper start
    // used to clear intrusive holders from GameEvent, Item, Block, EntityType, and Fluid from unused instances of those types
    public void clearIntrusiveHolder(final T instance) {
        if (this.unregisteredIntrusiveHolders != null) {
            this.unregisteredIntrusiveHolders.remove(instance);
        }
    }
    // Paper end
}
