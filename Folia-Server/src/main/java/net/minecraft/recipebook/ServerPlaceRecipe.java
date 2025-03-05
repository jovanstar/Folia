package net.minecraft.recipebook;

import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;

public class ServerPlaceRecipe<R extends Recipe<?>> {
    private static final int ITEM_NOT_FOUND = -1;
    private final Inventory inventory;
    private final ServerPlaceRecipe.CraftingMenuAccess<R> menu;
    private final boolean useMaxItems;
    private final int gridWidth;
    private final int gridHeight;
    private final List<Slot> inputGridSlots;
    private final List<Slot> slotsToClear;

    public static <I extends RecipeInput, R extends Recipe<I>> RecipeBookMenu.PostPlaceAction placeRecipe(
        ServerPlaceRecipe.CraftingMenuAccess<R> handler,
        int width,
        int height,
        List<Slot> inputSlots,
        List<Slot> slotsToReturn,
        Inventory inventory,
        RecipeHolder<R> recipe,
        boolean craftAll,
        boolean creative
    ) {
        ServerPlaceRecipe<R> serverPlaceRecipe = new ServerPlaceRecipe<>(handler, inventory, craftAll, width, height, inputSlots, slotsToReturn);
        if (!creative && !serverPlaceRecipe.testClearGrid()) {
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        } else {
            StackedItemContents stackedItemContents = new StackedItemContents();
            stackedItemContents.initializeExtras(recipe.value(), null); // Paper - Improve exact choice recipe ingredients
            inventory.fillStackedContents(stackedItemContents);
            handler.fillCraftSlotsStackedContents(stackedItemContents);
            return serverPlaceRecipe.tryPlaceRecipe(recipe, stackedItemContents);
        }
    }

    private ServerPlaceRecipe(
        ServerPlaceRecipe.CraftingMenuAccess<R> handler,
        Inventory inventory,
        boolean craftAll,
        int width,
        int height,
        List<Slot> inputSlots,
        List<Slot> slotsToReturn
    ) {
        this.menu = handler;
        this.inventory = inventory;
        this.useMaxItems = craftAll;
        this.gridWidth = width;
        this.gridHeight = height;
        this.inputGridSlots = inputSlots;
        this.slotsToClear = slotsToReturn;
    }

    private RecipeBookMenu.PostPlaceAction tryPlaceRecipe(RecipeHolder<R> recipe, StackedItemContents finder) {
        if (finder.canCraft(recipe.value(), null)) {
            this.placeRecipe(recipe, finder);
            this.inventory.setChanged();
            return RecipeBookMenu.PostPlaceAction.NOTHING;
        } else {
            this.clearGrid();
            this.inventory.setChanged();
            return RecipeBookMenu.PostPlaceAction.PLACE_GHOST_RECIPE;
        }
    }

    private void clearGrid() {
        for (Slot slot : this.slotsToClear) {
            ItemStack itemStack = slot.getItem().copy();
            this.inventory.placeItemBackInInventory(itemStack, false);
            slot.set(itemStack);
        }

        this.menu.clearCraftingContent();
    }

    private void placeRecipe(RecipeHolder<R> recipe, StackedItemContents finder) {
        boolean bl = this.menu.recipeMatches(recipe);
        int i = finder.getBiggestCraftableStack(recipe.value(), null);
        if (bl) {
            for (Slot slot : this.inputGridSlots) {
                ItemStack itemStack = slot.getItem();
                if (!itemStack.isEmpty() && Math.min(i, itemStack.getMaxStackSize()) < itemStack.getCount() + 1) {
                    return;
                }
            }
        }

        int j = this.calculateAmountToCraft(i, bl);
        List<io.papermc.paper.inventory.recipe.ItemOrExact> list = new ArrayList<>(); // Paper - Improve exact choice recipe ingredients
        if (finder.canCraft(recipe.value(), j, list::add)) {
            int k = clampToMaxStackSize(j, list);
            if (k != j) {
                list.clear();
                if (!finder.canCraft(recipe.value(), k, list::add)) {
                    return;
                }
            }

            this.clearGrid();
            PlaceRecipeHelper.placeRecipe(
                this.gridWidth, this.gridHeight, recipe.value(), recipe.value().placementInfo().slotsToIngredientIndex(), (slotx, index, x, y) -> {
                    if (slotx != -1) {
                        Slot slot2 = this.inputGridSlots.get(index);
                        io.papermc.paper.inventory.recipe.ItemOrExact holder = list.get(slotx); // Paper - Improve exact choice recipe ingredients
                        int jx = k;

                        while (jx > 0) {
                            jx = this.moveItemToGrid(slot2, holder, jx);
                            if (jx == -1) {
                                return;
                            }
                        }
                    }
                }
            );
        }
    }

    // Paper start - Improve exact choice recipe ingredients
    private static int clampToMaxStackSize(int count, List<io.papermc.paper.inventory.recipe.ItemOrExact> entries) {
        for (io.papermc.paper.inventory.recipe.ItemOrExact holder : entries) {
            count = Math.min(count, holder.getMaxStackSize());
            // Paper end - Improve exact choice recipe ingredients
        }

        return count;
    }

    private int calculateAmountToCraft(int forCraftAll, boolean match) {
        if (this.useMaxItems) {
            return forCraftAll;
        } else if (match) {
            int i = Integer.MAX_VALUE;

            for (Slot slot : this.inputGridSlots) {
                ItemStack itemStack = slot.getItem();
                if (!itemStack.isEmpty() && i > itemStack.getCount()) {
                    i = itemStack.getCount();
                }
            }

            if (i != Integer.MAX_VALUE) {
                i++;
            }

            return i;
        } else {
            return 1;
        }
    }

    private int moveItemToGrid(Slot slot, io.papermc.paper.inventory.recipe.ItemOrExact item, int count) { // Paper - Improve exact choice recipe ingredients
        ItemStack itemStack = slot.getItem();
        int i = this.inventory.findSlotMatchingCraftingIngredient(item, itemStack);
        if (i == -1) {
            return -1;
        } else {
            ItemStack itemStack2 = this.inventory.getItem(i);
            ItemStack itemStack3;
            if (count < itemStack2.getCount()) {
                itemStack3 = this.inventory.removeItem(i, count);
            } else {
                itemStack3 = this.inventory.removeItemNoUpdate(i);
            }

            int j = itemStack3.getCount();
            if (itemStack.isEmpty()) {
                slot.set(itemStack3);
            } else {
                itemStack.grow(j);
            }

            return count - j;
        }
    }

    private boolean testClearGrid() {
        List<ItemStack> list = Lists.newArrayList();
        int i = this.getAmountOfFreeSlotsInInventory();

        for (Slot slot : this.inputGridSlots) {
            ItemStack itemStack = slot.getItem().copy();
            if (!itemStack.isEmpty()) {
                int j = this.inventory.getSlotWithRemainingSpace(itemStack);
                if (j == -1 && list.size() <= i) {
                    for (ItemStack itemStack2 : list) {
                        if (ItemStack.isSameItem(itemStack2, itemStack)
                            && itemStack2.getCount() != itemStack2.getMaxStackSize()
                            && itemStack2.getCount() + itemStack.getCount() <= itemStack2.getMaxStackSize()) {
                            itemStack2.grow(itemStack.getCount());
                            itemStack.setCount(0);
                            break;
                        }
                    }

                    if (!itemStack.isEmpty()) {
                        if (list.size() >= i) {
                            return false;
                        }

                        list.add(itemStack);
                    }
                } else if (j == -1) {
                    return false;
                }
            }
        }

        return true;
    }

    private int getAmountOfFreeSlotsInInventory() {
        int i = 0;

        for (ItemStack itemStack : this.inventory.items) {
            if (itemStack.isEmpty()) {
                i++;
            }
        }

        return i;
    }

    public interface CraftingMenuAccess<T extends Recipe<?>> {
        void fillCraftSlotsStackedContents(StackedItemContents finder);

        void clearCraftingContent();

        boolean recipeMatches(RecipeHolder<T> entry);
    }
}
