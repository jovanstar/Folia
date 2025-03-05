package net.minecraft.world.item.crafting;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultimap.Builder;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
// CraftBukkit start
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Maps;
// CraftBukkit end

public class RecipeMap {

    public static final RecipeMap EMPTY = new RecipeMap(ImmutableMultimap.of(), Map.of());
    public final Multimap<RecipeType<?>, RecipeHolder<?>> byType;
    public final Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey;

    private RecipeMap(Multimap<RecipeType<?>, RecipeHolder<?>> byType, Map<ResourceKey<Recipe<?>>, RecipeHolder<?>> byKey) {
        this.byType = byType;
        this.byKey = byKey;
    }

    public static RecipeMap create(Iterable<RecipeHolder<?>> recipes) {
        Builder<RecipeType<?>, RecipeHolder<?>> builder = ImmutableMultimap.builder();
        com.google.common.collect.ImmutableMap.Builder<ResourceKey<Recipe<?>>, RecipeHolder<?>> com_google_common_collect_immutablemap_builder = ImmutableMap.builder();
        Iterator iterator = recipes.iterator();

        while (iterator.hasNext()) {
            RecipeHolder<?> recipeholder = (RecipeHolder) iterator.next();

            builder.put(recipeholder.value().getType(), recipeholder);
            com_google_common_collect_immutablemap_builder.put(recipeholder.id(), recipeholder);
        }

        // CraftBukkit start - mutable
        return new RecipeMap(LinkedHashMultimap.create(builder.build()), Maps.newHashMap(com_google_common_collect_immutablemap_builder.build()));
    }

    public void addRecipe(RecipeHolder<?> irecipe) {
        Collection<RecipeHolder<?>> map = this.byType.get(irecipe.value().getType());

        if (this.byKey.containsKey(irecipe.id())) {
            throw new IllegalStateException("Duplicate recipe ignored with ID " + irecipe.id());
        } else {
            map.add(irecipe);
            this.byKey.put(irecipe.id(), irecipe);
        }
    }

    // public boolean removeRecipe(ResourceKey<Recipe<?>> mcKey) {
    //     boolean removed = false;
    //     Iterator<RecipeHolder<?>> iter = this.byType.values().iterator();
    //     while (iter.hasNext()) {
    //         RecipeHolder<?> recipe = iter.next();
    //         if (recipe.id().equals(mcKey)) {
    //             iter.remove();
    //             removed = true;
    //         }
    //     }
    //     removed |= this.byKey.remove(mcKey) != null;
    //
    //     return removed;
    // }
    // CraftBukkit end


    // Paper start - replace removeRecipe implementation
    public <T extends RecipeInput> boolean removeRecipe(ResourceKey<Recipe<T>> mcKey) {
        //noinspection unchecked
        final RecipeHolder<Recipe<T>> remove = (RecipeHolder<Recipe<T>>) this.byKey.remove(mcKey);
        if (remove == null) {
            return false;
        }
        final Collection<? extends RecipeHolder<? extends Recipe<T>>> recipes = this.byType(remove.value().getType());
        if (recipes.remove(remove)) {
            return true;
        }
        return false;
        // Paper end - why are you using a loop???
    }
    // Paper end - replace removeRecipe implementation

    public <I extends RecipeInput, T extends Recipe<I>> Collection<RecipeHolder<T>> byType(RecipeType<T> type) {
        return (Collection) this.byType.get(type); // CraftBukkit - decompile error
    }

    public Collection<RecipeHolder<?>> values() {
        return this.byKey.values();
    }

    @Nullable
    public RecipeHolder<?> byKey(ResourceKey<Recipe<?>> key) {
        return (RecipeHolder) this.byKey.get(key);
    }

    public <I extends RecipeInput, T extends Recipe<I>> Stream<RecipeHolder<T>> getRecipesFor(RecipeType<T> type, I input, Level world) {
        return input.isEmpty() ? Stream.empty() : this.byType(type).stream().filter((recipeholder) -> {
            return recipeholder.value().matches(input, world);
        });
    }
}
