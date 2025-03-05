package net.minecraft.world.entity.player;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;

public class StackedItemContents {
    // Paper start - Improve exact choice recipe ingredients
    private final StackedContents<io.papermc.paper.inventory.recipe.ItemOrExact> raw = new StackedContents<>();
    @Nullable
    private io.papermc.paper.inventory.recipe.StackedContentsExtrasMap extrasMap = null;
    // Paper start - Improve exact choice recipe ingredients

    public void accountSimpleStack(ItemStack item) {
        if (this.extrasMap != null && this.extrasMap.accountStack(item, Math.min(64, item.getCount()))) return; // Paper - Improve exact choice recipe ingredients; max of 64 due to accountStack method below
        if (Inventory.isUsableForCrafting(item)) {
            this.accountStack(item);
        }
    }

    public void accountStack(ItemStack item) {
        this.accountStack(item, item.getMaxStackSize());
    }

    public void accountStack(ItemStack item, int maxCount) {
        if (!item.isEmpty()) {
            int i = Math.min(maxCount, item.getCount());
            if (this.extrasMap != null && !item.getComponentsPatch().isEmpty() && this.extrasMap.accountStack(item, i)) return; // Paper - Improve exact choice recipe ingredients; if an exact ingredient, don't include it
            this.raw.account(new io.papermc.paper.inventory.recipe.ItemOrExact.Item(item.getItemHolder()), i);
        }
    }

    // Paper start - Improve exact choice recipe ingredients
    public void initializeExtras(final Recipe<?> recipe, @Nullable final net.minecraft.world.item.crafting.CraftingInput input) {
        if (this.extrasMap == null) {
            this.extrasMap = new io.papermc.paper.inventory.recipe.StackedContentsExtrasMap(this.raw);
        }
        this.extrasMap.initialize(recipe);
        if (input != null) this.extrasMap.accountInput(input);
    }

    public void resetExtras() {
        if (this.extrasMap != null && !this.raw.amounts.isEmpty()) {
            this.extrasMap.resetExtras();
        }
    }
    // Paper end - Improve exact choice recipe ingredients

    public boolean canCraft(Recipe<?> recipe, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback) { // Paper - Improve exact choice recipe ingredients
        return this.canCraft(recipe, 1, itemCallback);
    }

    public boolean canCraft(Recipe<?> recipe, int quantity, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback) { // Paper - Improve exact choice recipe ingredients
        PlacementInfo placementInfo = recipe.placementInfo();
        return !placementInfo.isImpossibleToPlace() && this.canCraft(placementInfo.ingredients(), quantity, itemCallback);
    }

    public boolean canCraft(
        List<? extends StackedContents.IngredientInfo<io.papermc.paper.inventory.recipe.ItemOrExact>> rawIngredients, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback // Paper - Improve exact choice recipe ingredients
    ) {
        return this.canCraft(rawIngredients, 1, itemCallback);
    }

    private boolean canCraft(
        List<? extends StackedContents.IngredientInfo<io.papermc.paper.inventory.recipe.ItemOrExact>> rawIngredients, int quantity, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback // Paper - Improve exact choice recipe ingredients
    ) {
        return this.raw.tryPick(rawIngredients, quantity, itemCallback);
    }

    public int getBiggestCraftableStack(Recipe<?> recipe, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback) { // Paper - Improve exact choice recipe ingredients
        return this.getBiggestCraftableStack(recipe, Integer.MAX_VALUE, itemCallback);
    }

    public int getBiggestCraftableStack(Recipe<?> recipe, int max, @Nullable StackedContents.Output<io.papermc.paper.inventory.recipe.ItemOrExact> itemCallback) { // Paper - Improve exact choice recipe ingredients
        return this.raw.tryPickAll(recipe.placementInfo().ingredients(), max, itemCallback);
    }

    public void clear() {
        this.raw.clear();
    }
}
