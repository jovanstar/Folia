package net.minecraft.world.inventory;

import java.util.List;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.bukkit.craftbukkit.inventory.CraftInventoryFurnace;
import org.bukkit.craftbukkit.inventory.view.CraftFurnaceView;
// CraftBukkit end

public abstract class AbstractFurnaceMenu extends RecipeBookMenu {

    public static final int INGREDIENT_SLOT = 0;
    public static final int FUEL_SLOT = 1;
    public static final int RESULT_SLOT = 2;
    public static final int SLOT_COUNT = 3;
    public static final int DATA_COUNT = 4;
    private static final int INV_SLOT_START = 3;
    private static final int INV_SLOT_END = 30;
    private static final int USE_ROW_SLOT_START = 30;
    private static final int USE_ROW_SLOT_END = 39;
    final Container container;
    private final ContainerData data;
    protected final Level level;
    private final RecipeType<? extends AbstractCookingRecipe> recipeType;
    private final RecipePropertySet acceptedInputs;
    private final RecipeBookType recipeBookType;

    // CraftBukkit start
    private CraftFurnaceView bukkitEntity = null;
    private Inventory player;

    @Override
    public CraftFurnaceView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        CraftInventoryFurnace inventory = new CraftInventoryFurnace((AbstractFurnaceBlockEntity) this.container);
        this.bukkitEntity = new CraftFurnaceView(this.player.player.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end

    protected AbstractFurnaceMenu(MenuType<?> type, RecipeType<? extends AbstractCookingRecipe> recipeType, ResourceKey<RecipePropertySet> recipePropertySetKey, RecipeBookType category, int syncId, Inventory platerInventory) {
        this(type, recipeType, recipePropertySetKey, category, syncId, platerInventory, new SimpleContainer(3), new SimpleContainerData(4));
    }

    protected AbstractFurnaceMenu(MenuType<?> type, RecipeType<? extends AbstractCookingRecipe> recipeType, ResourceKey<RecipePropertySet> recipePropertySetKey, RecipeBookType category, int syncId, Inventory platerInventory, Container inventory, ContainerData propertyDelegate) {
        super(type, syncId);
        this.recipeType = recipeType;
        this.recipeBookType = category;
        checkContainerSize(inventory, 3);
        checkContainerDataCount(propertyDelegate, 4);
        this.container = inventory;
        this.data = propertyDelegate;
        this.level = platerInventory.player.level();
        this.acceptedInputs = this.level.recipeAccess().propertySet(recipePropertySetKey);
        this.addSlot(new Slot(inventory, 0, 56, 17));
        this.addSlot(new FurnaceFuelSlot(this, inventory, 1, 56, 53));
        this.addSlot(new FurnaceResultSlot(platerInventory.player, inventory, 2, 116, 35));
        this.player = platerInventory; // CraftBukkit - save player
        this.addStandardInventorySlots(platerInventory, 8, 84);
        this.addDataSlots(propertyDelegate);
    }

    @Override
    public void fillCraftSlotsStackedContents(StackedItemContents finder) {
        if (this.container instanceof StackedContentsCompatible) {
            ((StackedContentsCompatible) this.container).fillStackedContents(finder);
        }

    }

    public Slot getResultSlot() {
        return (Slot) this.slots.get(2);
    }

    @Override
    public boolean stillValid(Player player) {
        if (!this.checkReachable) return true; // CraftBukkit
        return this.container.stillValid(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot1 = (Slot) this.slots.get(slot);

        if (slot1 != null && slot1.hasItem()) {
            ItemStack itemstack1 = slot1.getItem();

            itemstack = itemstack1.copy();
            if (slot == 2) {
                if (!this.moveItemStackTo(itemstack1, 3, 39, true)) {
                    return ItemStack.EMPTY;
                }

                slot1.onQuickCraft(itemstack1, itemstack);
            } else if (slot != 1 && slot != 0) {
                if (this.canSmelt(itemstack1)) {
                    if (!this.moveItemStackTo(itemstack1, 0, 1, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (this.isFuel(itemstack1)) {
                    if (!this.moveItemStackTo(itemstack1, 1, 2, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slot >= 3 && slot < 30) {
                    if (!this.moveItemStackTo(itemstack1, 30, 39, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (slot >= 30 && slot < 39 && !this.moveItemStackTo(itemstack1, 3, 30, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(itemstack1, 3, 39, false)) {
                return ItemStack.EMPTY;
            }

            if (itemstack1.isEmpty()) {
                slot1.setByPlayer(ItemStack.EMPTY);
            } else {
                slot1.setChanged();
            }

            if (itemstack1.getCount() == itemstack.getCount()) {
                return ItemStack.EMPTY;
            }

            slot1.onTake(player, itemstack1);
        }

        return itemstack;
    }

    protected boolean canSmelt(ItemStack itemStack) {
        return this.acceptedInputs.test(itemStack);
    }

    protected boolean isFuel(ItemStack item) {
        return this.level.fuelValues().isFuel(item);
    }

    public float getBurnProgress() {
        int i = this.data.get(2);
        int j = this.data.get(3);

        return j != 0 && i != 0 ? Mth.clamp((float) i / (float) j, 0.0F, 1.0F) : 0.0F;
    }

    public float getLitProgress() {
        int i = this.data.get(1);

        if (i == 0) {
            i = 200;
        }

        return Mth.clamp((float) this.data.get(0) / (float) i, 0.0F, 1.0F);
    }

    public boolean isLit() {
        return this.data.get(0) > 0;
    }

    @Override
    public RecipeBookType getRecipeBookType() {
        return this.recipeBookType;
    }

    @Override
    public RecipeBookMenu.PostPlaceAction handlePlacement(boolean craftAll, boolean creative, RecipeHolder<?> recipe, final ServerLevel world, Inventory inventory) {
        final List<Slot> list = List.of(this.getSlot(0), this.getSlot(2));

        return ServerPlaceRecipe.placeRecipe(new ServerPlaceRecipe.CraftingMenuAccess<AbstractCookingRecipe>() {
            @Override
            public void fillCraftSlotsStackedContents(StackedItemContents finder) {
                AbstractFurnaceMenu.this.fillCraftSlotsStackedContents(finder);
            }

            @Override
            public void clearCraftingContent() {
                list.forEach((slot) -> {
                    slot.set(ItemStack.EMPTY);
                });
            }

            @Override
            public boolean recipeMatches(RecipeHolder<AbstractCookingRecipe> entry) {
                return ((AbstractCookingRecipe) entry.value()).matches(new SingleRecipeInput(AbstractFurnaceMenu.this.container.getItem(0)), world);
            }
        }, 1, 1, List.of(this.getSlot(0)), list, inventory, (RecipeHolder<AbstractCookingRecipe>) recipe, craftAll, creative); // CraftBukkit - decompile error
    }
}
