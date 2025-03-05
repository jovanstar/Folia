package net.minecraft.world.item.crafting;

import com.google.common.annotations.VisibleForTesting;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftShapedRecipe;
import org.bukkit.inventory.RecipeChoice;
// CraftBukkit end

public class ShapedRecipe implements CraftingRecipe {

    final ShapedRecipePattern pattern;
    final ItemStack result;
    final String group;
    final CraftingBookCategory category;
    final boolean showNotification;
    @Nullable
    private PlacementInfo placementInfo;

    public ShapedRecipe(String group, CraftingBookCategory category, ShapedRecipePattern raw, ItemStack result, boolean showNotification) {
        this.group = group;
        this.category = category;
        this.pattern = raw;
        this.result = result;
        this.showNotification = showNotification;
    }

    public ShapedRecipe(String group, CraftingBookCategory category, ShapedRecipePattern raw, ItemStack result) {
        this(group, category, raw, result, true);
    }

    // CraftBukkit start
    @Override
    public org.bukkit.inventory.ShapedRecipe toBukkitRecipe(NamespacedKey id) {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result);
        CraftShapedRecipe recipe = new CraftShapedRecipe(id, result, this);
        recipe.setGroup(this.group);
        recipe.setCategory(CraftRecipe.getCategory(this.category()));

        switch (this.pattern.height()) {
        case 1:
            switch (this.pattern.width()) {
            case 1:
                recipe.shape("a");
                break;
            case 2:
                recipe.shape("ab");
                break;
            case 3:
                recipe.shape("abc");
                break;
            }
            break;
        case 2:
            switch (this.pattern.width()) {
            case 1:
                recipe.shape("a","b");
                break;
            case 2:
                recipe.shape("ab","cd");
                break;
            case 3:
                recipe.shape("abc","def");
                break;
            }
            break;
        case 3:
            switch (this.pattern.width()) {
            case 1:
                recipe.shape("a","b","c");
                break;
            case 2:
                recipe.shape("ab","cd","ef");
                break;
            case 3:
                recipe.shape("abc","def","ghi");
                break;
            }
            break;
        }
        char c = 'a';
        for (Optional<Ingredient> list : this.pattern.ingredients()) {
            RecipeChoice choice = CraftRecipe.toBukkit(list);
            if (choice != RecipeChoice.empty()) { // Paper
                recipe.setIngredient(c, choice);
            }

            c++;
        }
        return recipe;
    }
    // CraftBukkit end

    @Override
    public RecipeSerializer<? extends ShapedRecipe> getSerializer() {
        return RecipeSerializer.SHAPED_RECIPE;
    }

    @Override
    public String group() {
        return this.group;
    }

    @Override
    public CraftingBookCategory category() {
        return this.category;
    }

    @VisibleForTesting
    public List<Optional<Ingredient>> getIngredients() {
        return this.pattern.ingredients();
    }

    @Override
    public PlacementInfo placementInfo() {
        if (this.placementInfo == null) {
            this.placementInfo = PlacementInfo.createFromOptionals(this.pattern.ingredients());
        }

        return this.placementInfo;
    }

    @Override
    public boolean showNotification() {
        return this.showNotification;
    }

    public boolean matches(CraftingInput input, Level world) {
        return this.pattern.matches(input);
    }

    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return this.result.copy();
    }

    public int getWidth() {
        return this.pattern.width();
    }

    public int getHeight() {
        return this.pattern.height();
    }

    @Override
    public List<RecipeDisplay> display() {
        return List.of(new ShapedCraftingRecipeDisplay(this.pattern.width(), this.pattern.height(), this.pattern.ingredients().stream().map((optional) -> {
            return (SlotDisplay) optional.map(Ingredient::display).orElse(SlotDisplay.Empty.INSTANCE);
        }).toList(), new SlotDisplay.ItemStackSlotDisplay(this.result), new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
    }

    public static class Serializer implements RecipeSerializer<ShapedRecipe> {

        public static final MapCodec<ShapedRecipe> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(Codec.STRING.optionalFieldOf("group", "").forGetter((shapedrecipes) -> {
                return shapedrecipes.group;
            }), CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter((shapedrecipes) -> {
                return shapedrecipes.category;
            }), ShapedRecipePattern.MAP_CODEC.forGetter((shapedrecipes) -> {
                return shapedrecipes.pattern;
            }), ItemStack.STRICT_CODEC.fieldOf("result").forGetter((shapedrecipes) -> {
                return shapedrecipes.result;
            }), Codec.BOOL.optionalFieldOf("show_notification", true).forGetter((shapedrecipes) -> {
                return shapedrecipes.showNotification;
            })).apply(instance, ShapedRecipe::new);
        });
        public static final StreamCodec<RegistryFriendlyByteBuf, ShapedRecipe> STREAM_CODEC = StreamCodec.of(ShapedRecipe.Serializer::toNetwork, ShapedRecipe.Serializer::fromNetwork);

        public Serializer() {}

        @Override
        public MapCodec<ShapedRecipe> codec() {
            return ShapedRecipe.Serializer.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, ShapedRecipe> streamCodec() {
            return ShapedRecipe.Serializer.STREAM_CODEC;
        }

        private static ShapedRecipe fromNetwork(RegistryFriendlyByteBuf buf) {
            String s = buf.readUtf();
            CraftingBookCategory craftingbookcategory = (CraftingBookCategory) buf.readEnum(CraftingBookCategory.class);
            ShapedRecipePattern shapedrecipepattern = (ShapedRecipePattern) ShapedRecipePattern.STREAM_CODEC.decode(buf);
            ItemStack itemstack = (ItemStack) ItemStack.STREAM_CODEC.decode(buf);
            boolean flag = buf.readBoolean();

            return new ShapedRecipe(s, craftingbookcategory, shapedrecipepattern, itemstack, flag);
        }

        private static void toNetwork(RegistryFriendlyByteBuf buf, ShapedRecipe recipe) {
            buf.writeUtf(recipe.group);
            buf.writeEnum(recipe.category);
            ShapedRecipePattern.STREAM_CODEC.encode(buf, recipe.pattern);
            ItemStack.STREAM_CODEC.encode(buf, recipe.result);
            buf.writeBoolean(recipe.showNotification);
        }
    }
}
