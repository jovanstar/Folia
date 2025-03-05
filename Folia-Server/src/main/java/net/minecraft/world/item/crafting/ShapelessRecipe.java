package net.minecraft.world.item.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftShapelessRecipe;
// CraftBukkit end

public class ShapelessRecipe implements CraftingRecipe {

    final String group;
    final CraftingBookCategory category;
    final ItemStack result;
    final List<Ingredient> ingredients;
    @Nullable
    private PlacementInfo placementInfo;

    public ShapelessRecipe(String group, CraftingBookCategory category, ItemStack result, List<Ingredient> ingredients) {
        this.group = group;
        this.category = category;
        this.result = result;
        this.ingredients = ingredients;
    }

    // CraftBukkit start
    @SuppressWarnings("unchecked")
    @Override
    public org.bukkit.inventory.ShapelessRecipe toBukkitRecipe(NamespacedKey id) {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result);
        CraftShapelessRecipe recipe = new CraftShapelessRecipe(id, result, this);
        recipe.setGroup(this.group);
        recipe.setCategory(CraftRecipe.getCategory(this.category()));

        for (Ingredient list : this.ingredients) {
            recipe.addIngredient(CraftRecipe.toBukkit(list));
        }
        return recipe;
    }
    // CraftBukkit end

    @Override
    public RecipeSerializer<ShapelessRecipe> getSerializer() {
        return RecipeSerializer.SHAPELESS_RECIPE;
    }

    @Override
    public String group() {
        return this.group;
    }

    @Override
    public CraftingBookCategory category() {
        return this.category;
    }

    @Override
    public PlacementInfo placementInfo() {
        if (this.placementInfo == null) {
            this.placementInfo = PlacementInfo.create(this.ingredients);
        }

        return this.placementInfo;
    }

    public boolean matches(CraftingInput input, Level world) {
        // Paper start - Improve exact choice recipe ingredients & unwrap ternary
        if (input.ingredientCount() != this.ingredients.size()) {
            return false;
        }
        if (input.size() == 1 && this.ingredients.size() == 1) {
            return this.ingredients.getFirst().test(input.getItem(0));
        }
        input.stackedContents().initializeExtras(this, input);
        boolean canCraft = input.stackedContents().canCraft(this, null);
        input.stackedContents().resetExtras();
        return canCraft;
        // Paper end - Improve exact choice recipe ingredients & unwrap ternary
    }

    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return this.result.copy();
    }

    @Override
    public List<RecipeDisplay> display() {
        return List.of(new ShapelessCraftingRecipeDisplay(this.ingredients.stream().map(Ingredient::display).toList(), new SlotDisplay.ItemStackSlotDisplay(this.result), new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
    }

    public static class Serializer implements RecipeSerializer<ShapelessRecipe> {

        private static final MapCodec<ShapelessRecipe> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(Codec.STRING.optionalFieldOf("group", "").forGetter((shapelessrecipes) -> {
                return shapelessrecipes.group;
            }), CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter((shapelessrecipes) -> {
                return shapelessrecipes.category;
            }), ItemStack.STRICT_CODEC.fieldOf("result").forGetter((shapelessrecipes) -> {
                return shapelessrecipes.result;
            }), Ingredient.CODEC.listOf(1, 9).fieldOf("ingredients").forGetter((shapelessrecipes) -> {
                return shapelessrecipes.ingredients;
            })).apply(instance, ShapelessRecipe::new);
        });
        public static final StreamCodec<RegistryFriendlyByteBuf, ShapelessRecipe> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, (shapelessrecipes) -> {
            return shapelessrecipes.group;
        }, CraftingBookCategory.STREAM_CODEC, (shapelessrecipes) -> {
            return shapelessrecipes.category;
        }, ItemStack.STREAM_CODEC, (shapelessrecipes) -> {
            return shapelessrecipes.result;
        }, Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), (shapelessrecipes) -> {
            return shapelessrecipes.ingredients;
        }, ShapelessRecipe::new);

        public Serializer() {}

        @Override
        public MapCodec<ShapelessRecipe> codec() {
            return ShapelessRecipe.Serializer.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, ShapelessRecipe> streamCodec() {
            return ShapelessRecipe.Serializer.STREAM_CODEC;
        }
    }
}
