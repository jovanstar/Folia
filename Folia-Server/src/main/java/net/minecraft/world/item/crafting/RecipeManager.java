package net.minecraft.world.item.crafting;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

// CraftBukkit start
import java.util.Collections;
import net.minecraft.server.MinecraftServer;
// CraftBukkit end
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

public class RecipeManager extends SimplePreparableReloadListener<RecipeMap> implements RecipeAccess {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ResourceKey<RecipePropertySet>, RecipeManager.IngredientExtractor> RECIPE_PROPERTY_SETS = Map.of(RecipePropertySet.SMITHING_ADDITION, (irecipe) -> {
        Optional optional;

        if (irecipe instanceof SmithingRecipe smithingrecipe) {
            optional = smithingrecipe.additionIngredient();
        } else {
            optional = Optional.empty();
        }

        return optional;
    }, RecipePropertySet.SMITHING_BASE, (irecipe) -> {
        Optional optional;

        if (irecipe instanceof SmithingRecipe smithingrecipe) {
            optional = smithingrecipe.baseIngredient();
        } else {
            optional = Optional.empty();
        }

        return optional;
    }, RecipePropertySet.SMITHING_TEMPLATE, (irecipe) -> {
        Optional optional;

        if (irecipe instanceof SmithingRecipe smithingrecipe) {
            optional = smithingrecipe.templateIngredient();
        } else {
            optional = Optional.empty();
        }

        return optional;
    }, RecipePropertySet.FURNACE_INPUT, RecipeManager.forSingleInput(RecipeType.SMELTING), RecipePropertySet.BLAST_FURNACE_INPUT, RecipeManager.forSingleInput(RecipeType.BLASTING), RecipePropertySet.SMOKER_INPUT, RecipeManager.forSingleInput(RecipeType.SMOKING), RecipePropertySet.CAMPFIRE_INPUT, RecipeManager.forSingleInput(RecipeType.CAMPFIRE_COOKING));
    private static final FileToIdConverter RECIPE_LISTER = FileToIdConverter.registry(Registries.RECIPE);
    private final HolderLookup.Provider registries;
    public RecipeMap recipes;
    private Map<ResourceKey<RecipePropertySet>, RecipePropertySet> propertySets;
    private SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes;
    private List<RecipeManager.ServerDisplayInfo> allDisplays;
    private Map<ResourceKey<Recipe<?>>, List<RecipeManager.ServerDisplayInfo>> recipeToDisplay;

    public RecipeManager(HolderLookup.Provider registries) {
        this.recipes = RecipeMap.EMPTY;
        this.propertySets = Map.of();
        this.stonecutterRecipes = SelectableRecipe.SingleInputSet.empty();
        this.allDisplays = List.of();
        this.recipeToDisplay = Map.of();
        this.registries = registries;
    }

    @Override
    protected RecipeMap prepare(ResourceManager manager, ProfilerFiller profiler) {
        SortedMap<ResourceLocation, Recipe<?>> sortedmap = new TreeMap();

        SimpleJsonResourceReloadListener.scanDirectory(manager, RecipeManager.RECIPE_LISTER, this.registries.createSerializationContext(JsonOps.INSTANCE), Recipe.CODEC, sortedmap);
        List<RecipeHolder<?>> list = new ArrayList(sortedmap.size());

        sortedmap.forEach((minecraftkey, irecipe) -> {
            ResourceKey<Recipe<?>> resourcekey = ResourceKey.create(Registries.RECIPE, minecraftkey);
            RecipeHolder<?> recipeholder = new RecipeHolder<>(resourcekey, irecipe);

            list.add(recipeholder);
        });
        return RecipeMap.create(list);
    }

    protected void apply(RecipeMap prepared, ResourceManager manager, ProfilerFiller profiler) {
        this.recipes = prepared;
        RecipeManager.LOGGER.info("Loaded {} recipes", prepared.values().size());
    }

    // CraftBukkit start
    public void addRecipe(RecipeHolder<?> irecipe) {
        org.spigotmc.AsyncCatcher.catchOp("Recipe Add"); // Spigot
        this.recipes.addRecipe(irecipe);
        this.finalizeRecipeLoading();
    }

    private FeatureFlagSet featureflagset;

    public void finalizeRecipeLoading() {
        if (this.featureflagset != null) {
            this.finalizeRecipeLoading(this.featureflagset);

            MinecraftServer.getServer().getPlayerList().reloadRecipes();
        }
    }

    public void finalizeRecipeLoading(FeatureFlagSet features) {
        this.featureflagset = features;
        // CraftBukkit end
        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> list = new ArrayList();
        List<RecipeManager.IngredientCollector> list1 = RecipeManager.RECIPE_PROPERTY_SETS.entrySet().stream().map((entry) -> {
            return new RecipeManager.IngredientCollector((ResourceKey) entry.getKey(), (RecipeManager.IngredientExtractor) entry.getValue());
        }).toList();

        this.recipes.values().forEach((recipeholder) -> {
            Recipe<?> irecipe = recipeholder.value();

            if (!irecipe.isSpecial() && irecipe.placementInfo().isImpossibleToPlace()) {
                RecipeManager.LOGGER.warn("Recipe {} can't be placed due to empty ingredients and will be ignored", recipeholder.id().location());
            } else {
                list1.forEach((craftingmanager_b) -> {
                    craftingmanager_b.accept(irecipe);
                });
                if (irecipe instanceof StonecutterRecipe) {
                    StonecutterRecipe recipestonecutting = (StonecutterRecipe) irecipe;

                    if (RecipeManager.isIngredientEnabled(features, recipestonecutting.input()) && recipestonecutting.resultDisplay().isEnabled(features)) {
                        list.add(new SelectableRecipe.SingleInputEntry<StonecutterRecipe>(recipestonecutting.input(), new SelectableRecipe<>(recipestonecutting.resultDisplay(), Optional.of((RecipeHolder<StonecutterRecipe>) recipeholder)))); // CraftBukkit - decompile error
                    }
                }

            }
        });
        this.propertySets = (Map) list1.stream().collect(Collectors.toUnmodifiableMap((craftingmanager_b) -> {
            return craftingmanager_b.key;
        }, (craftingmanager_b) -> {
            return craftingmanager_b.asPropertySet(features);
        }));
        this.stonecutterRecipes = new SelectableRecipe.SingleInputSet<>(list);
        this.allDisplays = RecipeManager.unpackRecipeInfo(this.recipes.values(), features);
        this.recipeToDisplay = (Map) this.allDisplays.stream().collect(Collectors.groupingBy((craftingmanager_d) -> {
            return craftingmanager_d.parent.id();
        }, IdentityHashMap::new, Collectors.toList()));
    }

    static List<Ingredient> filterDisabled(FeatureFlagSet features, List<Ingredient> ingredients) {
        ingredients.removeIf((recipeitemstack) -> {
            return !RecipeManager.isIngredientEnabled(features, recipeitemstack);
        });
        return ingredients;
    }

    private static boolean isIngredientEnabled(FeatureFlagSet features, Ingredient ingredient) {
        return ingredient.items().allMatch((holder) -> {
            return ((Item) holder.value()).isEnabled(features);
        });
    }

    public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(RecipeType<T> type, I input, Level world, @Nullable ResourceKey<Recipe<?>> recipe) {
        RecipeHolder<T> recipeholder = recipe != null ? this.byKeyTyped(type, recipe) : null;

        return this.getRecipeFor(type, input, world, recipeholder);
    }

    public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(RecipeType<T> type, I input, Level world, @Nullable RecipeHolder<T> recipe) {
        return recipe != null && recipe.value().matches(input, world) ? Optional.of(recipe) : this.getRecipeFor(type, input, world);
    }

    public <I extends RecipeInput, T extends Recipe<I>> Optional<RecipeHolder<T>> getRecipeFor(RecipeType<T> type, I input, Level world) {
        // CraftBukkit start
        List<RecipeHolder<T>> list = this.recipes.getRecipesFor(type, input, world).toList();
        return (list.isEmpty()) ? Optional.empty() : Optional.of(list.getLast()); // CraftBukkit - SPIGOT-4638: last recipe gets priority
        // CraftBukkit end
    }

    public Optional<RecipeHolder<?>> byKey(ResourceKey<Recipe<?>> key) {
        return Optional.ofNullable(this.recipes.byKey(key));
    }

    @Nullable
    private <T extends Recipe<?>> RecipeHolder<T> byKeyTyped(RecipeType<T> type, ResourceKey<Recipe<?>> key) {
        RecipeHolder<?> recipeholder = this.recipes.byKey(key);

        return recipeholder != null && recipeholder.value().getType().equals(type) ? (RecipeHolder) recipeholder : null; // CraftBukkit - decompile error
    }

    public Map<ResourceKey<RecipePropertySet>, RecipePropertySet> getSynchronizedItemProperties() {
        return this.propertySets;
    }

    public SelectableRecipe.SingleInputSet<StonecutterRecipe> getSynchronizedStonecutterRecipes() {
        return this.stonecutterRecipes;
    }

    @Override
    public RecipePropertySet propertySet(ResourceKey<RecipePropertySet> key) {
        return (RecipePropertySet) this.propertySets.getOrDefault(key, RecipePropertySet.EMPTY);
    }

    @Override
    public SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutterRecipes() {
        return this.stonecutterRecipes;
    }

    public Collection<RecipeHolder<?>> getRecipes() {
        return this.recipes.values();
    }

    @Nullable
    public RecipeManager.ServerDisplayInfo getRecipeFromDisplay(RecipeDisplayId id) {
        return (RecipeManager.ServerDisplayInfo) this.allDisplays.get(id.index());
    }

    public void listDisplaysForRecipe(ResourceKey<Recipe<?>> key, Consumer<RecipeDisplayEntry> action) {
        List<RecipeManager.ServerDisplayInfo> list = (List) this.recipeToDisplay.get(key);

        if (list != null) {
            list.forEach((craftingmanager_d) -> {
                action.accept(craftingmanager_d.display);
            });
        }

    }

    @VisibleForTesting
    protected static RecipeHolder<?> fromJson(ResourceKey<Recipe<?>> key, JsonObject json, HolderLookup.Provider registries) {
        Recipe<?> irecipe = (Recipe) Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow(JsonParseException::new);

        return new RecipeHolder<>(key, irecipe);
    }

    // CraftBukkit start
    public boolean removeRecipe(ResourceKey<Recipe<?>> mcKey) {
        boolean removed = this.recipes.removeRecipe((ResourceKey<Recipe<RecipeInput>>) (ResourceKey) mcKey); // Paper - generic fix
        if (removed) {
            this.finalizeRecipeLoading();
        }

        return removed;
    }

    public void clearRecipes() {
        this.recipes = RecipeMap.create(Collections.emptyList());
        this.finalizeRecipeLoading();
    }
    // CraftBukkit end

    public static <I extends RecipeInput, T extends Recipe<I>> RecipeManager.CachedCheck<I, T> createCheck(final RecipeType<T> type) {
        return new RecipeManager.CachedCheck<I, T>() {
            @Nullable
            private ResourceKey<Recipe<?>> lastRecipe;

            @Override
            public Optional<RecipeHolder<T>> getRecipeFor(I input, ServerLevel world) {
                RecipeManager craftingmanager = world.recipeAccess();
                Optional<RecipeHolder<T>> optional = craftingmanager.getRecipeFor(type, input, world, this.lastRecipe);

                if (optional.isPresent()) {
                    RecipeHolder<T> recipeholder = (RecipeHolder) optional.get();

                    this.lastRecipe = recipeholder.id();
                    return Optional.of(recipeholder);
                } else {
                    return Optional.empty();
                }
            }
        };
    }

    private static List<RecipeManager.ServerDisplayInfo> unpackRecipeInfo(Iterable<RecipeHolder<?>> recipes, FeatureFlagSet enabledFeatures) {
        List<RecipeManager.ServerDisplayInfo> list = new ArrayList();
        Object2IntMap<String> object2intmap = new Object2IntOpenHashMap();
        Iterator iterator = recipes.iterator();

        while (iterator.hasNext()) {
            RecipeHolder<?> recipeholder = (RecipeHolder) iterator.next();
            Recipe<?> irecipe = recipeholder.value();
            OptionalInt optionalint;

            if (irecipe.group().isEmpty()) {
                optionalint = OptionalInt.empty();
            } else {
                optionalint = OptionalInt.of(object2intmap.computeIfAbsent(irecipe.group(), (object) -> {
                    return object2intmap.size();
                }));
            }

            Optional optional;

            if (irecipe.isSpecial()) {
                optional = Optional.empty();
            } else {
                optional = Optional.of(irecipe.placementInfo().ingredients());
            }

            Iterator iterator1 = irecipe.display().iterator();

            while (iterator1.hasNext()) {
                RecipeDisplay recipedisplay = (RecipeDisplay) iterator1.next();

                if (recipedisplay.isEnabled(enabledFeatures)) {
                    int i = list.size();
                    RecipeDisplayId recipedisplayid = new RecipeDisplayId(i);
                    RecipeDisplayEntry recipedisplayentry = new RecipeDisplayEntry(recipedisplayid, recipedisplay, optionalint, irecipe.recipeBookCategory(), optional);

                    list.add(new RecipeManager.ServerDisplayInfo(recipedisplayentry, recipeholder));
                }
            }
        }

        return list;
    }

    private static RecipeManager.IngredientExtractor forSingleInput(RecipeType<? extends SingleItemRecipe> expectedType) {
        return (irecipe) -> {
            Optional optional;

            if (irecipe.getType() == expectedType && irecipe instanceof SingleItemRecipe recipesingleitem) {
                optional = Optional.of(recipesingleitem.input());
            } else {
                optional = Optional.empty();
            }

            return optional;
        };
    }

    public static record ServerDisplayInfo(RecipeDisplayEntry display, RecipeHolder<?> parent) {

    }

    @FunctionalInterface
    public interface IngredientExtractor {

        Optional<Ingredient> apply(Recipe<?> recipe);
    }

    public static class IngredientCollector implements Consumer<Recipe<?>> {

        final ResourceKey<RecipePropertySet> key;
        private final RecipeManager.IngredientExtractor extractor;
        private final List<Ingredient> ingredients = new ArrayList();

        protected IngredientCollector(ResourceKey<RecipePropertySet> propertySetKey, RecipeManager.IngredientExtractor ingredientGetter) {
            this.key = propertySetKey;
            this.extractor = ingredientGetter;
        }

        public void accept(Recipe<?> irecipe) {
            Optional optional = this.extractor.apply(irecipe);
            List list = this.ingredients;

            Objects.requireNonNull(this.ingredients);
            optional.ifPresent(list::add);
        }

        public RecipePropertySet asPropertySet(FeatureFlagSet enabledFeatures) {
            return RecipePropertySet.create(RecipeManager.filterDisabled(enabledFeatures, this.ingredients));
        }
    }

    public interface CachedCheck<I extends RecipeInput, T extends Recipe<I>> {

        Optional<RecipeHolder<T>> getRecipeFor(I input, ServerLevel world);
    }
}
