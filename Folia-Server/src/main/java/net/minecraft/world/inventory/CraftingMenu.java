package net.minecraft.world.inventory;

import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RepairItemRecipe;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.craftbukkit.inventory.CraftInventoryCrafting;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
// CraftBukkit end

public class CraftingMenu extends AbstractCraftingMenu {

    private static final int CRAFTING_GRID_WIDTH = 3;
    private static final int CRAFTING_GRID_HEIGHT = 3;
    public static final int RESULT_SLOT = 0;
    private static final int CRAFT_SLOT_START = 1;
    private static final int CRAFT_SLOT_COUNT = 9;
    private static final int CRAFT_SLOT_END = 10;
    private static final int INV_SLOT_START = 10;
    private static final int INV_SLOT_END = 37;
    private static final int USE_ROW_SLOT_START = 37;
    private static final int USE_ROW_SLOT_END = 46;
    public final ContainerLevelAccess access;
    private final Player player;
    private boolean placingRecipe;
    // CraftBukkit start
    private CraftInventoryView bukkitEntity = null;
    // CraftBukkit end

    public CraftingMenu(int syncId, Inventory playerInventory) {
        this(syncId, playerInventory, ContainerLevelAccess.NULL);
    }

    public CraftingMenu(int syncId, Inventory playerInventory, ContainerLevelAccess context) {
        super(MenuType.CRAFTING, syncId, 3, 3, playerInventory); // CraftBukkit - pass player
        this.access = context;
        this.player = playerInventory.player;
        this.addResultSlot(this.player, 124, 35);
        this.addCraftingGridSlots(30, 17);
        this.addStandardInventorySlots(playerInventory, 8, 84);
    }

    protected static void slotChangedCraftingGrid(AbstractContainerMenu handler, ServerLevel world, Player player, CraftingContainer craftingInventory, ResultContainer resultInventory, @Nullable RecipeHolder<CraftingRecipe> recipe) {
        CraftingInput craftinginput = craftingInventory.asCraftInput();
        ServerPlayer entityplayer = (ServerPlayer) player;
        ItemStack itemstack = ItemStack.EMPTY;
        if (recipe == null) recipe = craftingInventory.getCurrentRecipe(); // Paper - Perf: Improve mass crafting; check last recipe used first
        Optional<RecipeHolder<CraftingRecipe>> optional = world.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, craftinginput, world, recipe);
        craftingInventory.setCurrentRecipe(optional.orElse(null)); // CraftBukkit

        if (optional.isPresent()) {
            RecipeHolder<CraftingRecipe> recipeholder1 = (RecipeHolder) optional.get();
            CraftingRecipe recipecrafting = (CraftingRecipe) recipeholder1.value();

            if (resultInventory.setRecipeUsed(entityplayer, recipeholder1)) {
                ItemStack itemstack1 = recipecrafting.assemble(craftinginput, world.registryAccess());

                if (itemstack1.isItemEnabled(world.enabledFeatures())) {
                    itemstack = itemstack1;
                }
            }
        }
        itemstack = org.bukkit.craftbukkit.event.CraftEventFactory.callPreCraftEvent(craftingInventory, resultInventory, itemstack, handler.getBukkitView(), optional.map(RecipeHolder::value).orElse(null) instanceof RepairItemRecipe); // CraftBukkit

        resultInventory.setItem(0, itemstack);
        handler.setRemoteSlot(0, itemstack);
        entityplayer.connection.send(new ClientboundContainerSetSlotPacket(handler.containerId, handler.incrementStateId(), 0, itemstack));
    }

    @Override
    public void slotsChanged(Container inventory) {
        if (!this.placingRecipe) {
            this.access.execute((world, blockposition) -> {
                if (world instanceof ServerLevel worldserver) {
                    CraftingMenu.slotChangedCraftingGrid(this, worldserver, this.player, this.craftSlots, this.resultSlots, (RecipeHolder) null);
                }

            });
        }

    }

    @Override
    public void beginPlacingRecipe() {
        this.placingRecipe = true;
    }

    @Override
    public void finishPlacingRecipe(ServerLevel world, RecipeHolder<CraftingRecipe> recipe) {
        this.placingRecipe = false;
        CraftingMenu.slotChangedCraftingGrid(this, world, this.player, this.craftSlots, this.resultSlots, recipe);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.access.execute((world, blockposition) -> {
            this.clearContainer(player, this.craftSlots);
        });
    }

    @Override
    public boolean stillValid(Player player) {
        if (!this.checkReachable) return true; // CraftBukkit
        return stillValid(this.access, player, Blocks.CRAFTING_TABLE);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot1 = (Slot) this.slots.get(slot);

        if (slot1 != null && slot1.hasItem()) {
            ItemStack itemstack1 = slot1.getItem();

            itemstack = itemstack1.copy();
            if (slot == 0) {
                this.access.execute((world, blockposition) -> {
                    itemstack1.getItem().onCraftedBy(itemstack1, world, player);
                });
                if (!this.moveItemStackTo(itemstack1, 10, 46, true)) {
                    return ItemStack.EMPTY;
                }

                slot1.onQuickCraft(itemstack1, itemstack);
            } else if (slot >= 10 && slot < 46) {
                if (!this.moveItemStackTo(itemstack1, 1, 10, false)) {
                    if (slot < 37) {
                        if (!this.moveItemStackTo(itemstack1, 37, 46, false)) {
                            return ItemStack.EMPTY;
                        }
                    } else if (!this.moveItemStackTo(itemstack1, 10, 37, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            } else if (!this.moveItemStackTo(itemstack1, 10, 46, false)) {
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
            if (slot == 0) {
                player.drop(itemstack1, false);
            }
        }

        return itemstack;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public Slot getResultSlot() {
        return (Slot) this.slots.get(0);
    }

    @Override
    public List<Slot> getInputGridSlots() {
        return this.slots.subList(1, 10);
    }

    @Override
    public RecipeBookType getRecipeBookType() {
        return RecipeBookType.CRAFTING;
    }

    @Override
    protected Player owner() {
        return this.player;
    }

    // CraftBukkit start
    @Override
    public CraftInventoryView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        CraftInventoryCrafting inventory = new CraftInventoryCrafting(this.craftSlots, this.resultSlots);
        this.bukkitEntity = new CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
