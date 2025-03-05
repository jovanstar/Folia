package net.minecraft.world.item.crafting;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftCampfireRecipe;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class CampfireCookingRecipe extends AbstractCookingRecipe {

    public CampfireCookingRecipe(String group, CookingBookCategory category, Ingredient ingredient, ItemStack result, float experience, int cookingTime) {
        super(group, category, ingredient, result, experience, cookingTime);
    }

    @Override
    protected Item furnaceIcon() {
        return Items.CAMPFIRE;
    }

    @Override
    public RecipeSerializer<CampfireCookingRecipe> getSerializer() {
        return RecipeSerializer.CAMPFIRE_COOKING_RECIPE;
    }

    @Override
    public RecipeType<CampfireCookingRecipe> getType() {
        return RecipeType.CAMPFIRE_COOKING;
    }

    @Override
    public RecipeBookCategory recipeBookCategory() {
        return RecipeBookCategories.CAMPFIRE;
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe(NamespacedKey id) {
        CraftItemStack result = CraftItemStack.asCraftMirror(this.result());

        CraftCampfireRecipe recipe = new CraftCampfireRecipe(id, result, CraftRecipe.toBukkit(this.input()), this.experience(), this.cookingTime());
        recipe.setGroup(this.group());
        recipe.setCategory(CraftRecipe.getCategory(this.category()));

        return recipe;
    }
    // CraftBukkit end
}
