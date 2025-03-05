package net.minecraft.world.item.crafting;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmokingRecipe;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class SmokingRecipe extends AbstractCookingRecipe {

    public SmokingRecipe(String group, CookingBookCategory category, Ingredient ingredient, ItemStack result, float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    protected Item furnaceIcon() {
        return Items.SMOKER;
    }

    @Override
    public RecipeType<SmokingRecipe> getType() {
        return RecipeType.SMOKING;
    }

    @Override
    public RecipeSerializer<SmokingRecipe> getSerializer() {
        return RecipeSerializer.SMOKING_RECIPE;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.SMOKER_FOOD;
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe(NamespacedKey id) {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result());

        CraftSmokingRecipe recipe = new CraftSmokingRecipe(id, result, CraftRecipe.toBukkit(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
