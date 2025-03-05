package net.minecraft.world.inventory;

import java.util.List;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

public abstract class AbstractCraftingMenu extends RecipeBookMenu {

    private final int width;
    private final int height;
    public final TransientCraftingContainer craftSlots; // CraftBukkit
    public final ResultContainer resultSlots = new ResultContainer();

    public AbstractCraftingMenu(MenuType<?> containers, int i, int j, int k, Inventory playerInventory) { // CraftBukkit
        super(containers, i);
        this.width = j;
        this.height = k;
        // CraftBukkit start
        this.craftSlots = new TransientCraftingContainer(this, j, k, playerInventory.player); // CraftBukkit - pass player
        this.craftSlots.resultInventory = this.resultSlots; // CraftBukkit - let InventoryCrafting know about its result slot
        // CraftBukkit end
    }

    protected Slot addResultSlot(Player player, int x, int y) {
        return this.addSlot(new ResultSlot(player, this.craftSlots, this.resultSlots, 0, x, y));
    }

    protected void addCraftingGridSlots(int x, int y) {
        for (int k = 0; k < this.width; ++k) {
            for (int l = 0; l < this.height; ++l) {
                this.addSlot(new Slot(this.craftSlots, l + k * this.width, x + l * 18, y + k * 18));
            }
        }

    }

    @Override
    public RecipeBookMenu.PostPlaceAction handlePlacement(boolean craftAll, boolean creative, RecipeHolder<?> recipe, ServerLevel world, Inventory inventory) {
        RecipeHolder<CraftingRecipe> recipeholder1 = (RecipeHolder<CraftingRecipe>) recipe; // CraftBukkit - decompile error

        this.beginPlacingRecipe();

        RecipeBookMenu.PostPlaceAction containerrecipebook_a;

        try {
            List<Slot> list = this.getInputGridSlots();

            containerrecipebook_a = ServerPlaceRecipe.placeRecipe(new ServerPlaceRecipe.CraftingMenuAccess<CraftingRecipe>() {
                @Override
                public void fillCraftSlotsStackedContents(StackedItemContents finder) {
                    AbstractCraftingMenu.this.fillCraftSlotsStackedContents(finder);
                }

                @Override
                public void clearCraftingContent() {
                    AbstractCraftingMenu.this.resultSlots.clearContent();
                    AbstractCraftingMenu.this.craftSlots.clearContent();
                }

                @Override
                public boolean recipeMatches(RecipeHolder<CraftingRecipe> entry) {
                    return ((CraftingRecipe) entry.value()).matches(AbstractCraftingMenu.this.craftSlots.asCraftInput(), AbstractCraftingMenu.this.owner().level());
                }
            }, this.width, this.height, list, list, inventory, recipeholder1, craftAll, creative);
        } finally {
            this.finishPlacingRecipe(world, recipeholder1); // CraftBukkit - decompile error
        }

        return containerrecipebook_a;
    }

    protected void beginPlacingRecipe() {}

    protected void finishPlacingRecipe(ServerLevel world, RecipeHolder<CraftingRecipe> recipe) {}

    public abstract Slot getResultSlot();

    public abstract List<Slot> getInputGridSlots();

    public int getGridWidth() {
        return this.width;
    }

    public int getGridHeight() {
        return this.height;
    }

    protected abstract Player owner();

    @Override
    public void fillCraftSlotsStackedContents(StackedItemContents finder) {
        this.craftSlots.fillStackedContents(finder);
    }
}
