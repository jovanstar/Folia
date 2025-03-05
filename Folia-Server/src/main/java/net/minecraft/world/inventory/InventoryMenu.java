package net.minecraft.world.inventory;

import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.bukkit.craftbukkit.inventory.CraftInventoryCrafting;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
// CraftBukkit end

public class InventoryMenu extends AbstractCraftingMenu {

    public static final int CONTAINER_ID = 0;
    public static final int RESULT_SLOT = 0;
    private static final int CRAFTING_GRID_WIDTH = 2;
    private static final int CRAFTING_GRID_HEIGHT = 2;
    public static final int CRAFT_SLOT_START = 1;
    public static final int CRAFT_SLOT_COUNT = 4;
    public static final int CRAFT_SLOT_END = 5;
    public static final int ARMOR_SLOT_START = 5;
    public static final int ARMOR_SLOT_COUNT = 4;
    public static final int ARMOR_SLOT_END = 9;
    public static final int INV_SLOT_START = 9;
    public static final int INV_SLOT_END = 36;
    public static final int USE_ROW_SLOT_START = 36;
    public static final int USE_ROW_SLOT_END = 45;
    public static final int SHIELD_SLOT = 45;
    public static final ResourceLocation EMPTY_ARMOR_SLOT_HELMET = ResourceLocation.withDefaultNamespace("container/slot/helmet");
    public static final ResourceLocation EMPTY_ARMOR_SLOT_CHESTPLATE = ResourceLocation.withDefaultNamespace("container/slot/chestplate");
    public static final ResourceLocation EMPTY_ARMOR_SLOT_LEGGINGS = ResourceLocation.withDefaultNamespace("container/slot/leggings");
    public static final ResourceLocation EMPTY_ARMOR_SLOT_BOOTS = ResourceLocation.withDefaultNamespace("container/slot/boots");
    public static final ResourceLocation EMPTY_ARMOR_SLOT_SHIELD = ResourceLocation.withDefaultNamespace("container/slot/shield");
    private static final Map<EquipmentSlot, ResourceLocation> TEXTURE_EMPTY_SLOTS = Map.of(EquipmentSlot.FEET, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, EquipmentSlot.LEGS, InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, EquipmentSlot.CHEST, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE, EquipmentSlot.HEAD, InventoryMenu.EMPTY_ARMOR_SLOT_HELMET);
    private static final EquipmentSlot[] SLOT_IDS = new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    public final boolean active;
    private final Player owner;
    // CraftBukkit start
    private CraftInventoryView bukkitEntity = null;
    // CraftBukkit end

    public InventoryMenu(Inventory inventory, boolean onServer, final Player owner) {
        // CraftBukkit start
        super((MenuType) null, 0, 2, 2, inventory); // CraftBukkit - save player
        this.setTitle(Component.translatable("container.crafting")); // SPIGOT-4722: Allocate title for player inventory
        // CraftBukkit end
        this.active = onServer;
        this.owner = owner;
        this.addResultSlot(owner, 154, 28);
        this.addCraftingGridSlots(98, 18);

        for (int i = 0; i < 4; ++i) {
            EquipmentSlot enumitemslot = InventoryMenu.SLOT_IDS[i];
            ResourceLocation minecraftkey = (ResourceLocation) InventoryMenu.TEXTURE_EMPTY_SLOTS.get(enumitemslot);

            this.addSlot(new ArmorSlot(inventory, owner, enumitemslot, 39 - i, 8, 8 + i * 18, minecraftkey));
        }

        this.addStandardInventorySlots(inventory, 8, 84);
        this.addSlot(new Slot(inventory, 40, 77, 62) { // CraftBukkit - decompile error
            @Override
            public void setByPlayer(ItemStack stack, ItemStack previousStack) {
                owner.onEquipItem(EquipmentSlot.OFFHAND, previousStack, stack);
                super.setByPlayer(stack, previousStack);
            }

            @Override
            public ResourceLocation getNoItemIcon() {
                return InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD;
            }
        });
    }

    public static boolean isHotbarSlot(int slot) {
        return slot >= 36 && slot < 45 || slot == 45;
    }

    @Override
    public void slotsChanged(Container inventory) {
        Level world = this.owner.level();

        if (world instanceof ServerLevel worldserver) {
            CraftingMenu.slotChangedCraftingGrid(this, worldserver, this.owner, this.craftSlots, this.resultSlots, (RecipeHolder) null);
        }

    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        this.resultSlots.clearContent();
        if (!player.level().isClientSide) {
            this.clearContainer(player, this.craftSlots);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        ItemStack itemstack = ItemStack.EMPTY;
        Slot slot1 = (Slot) this.slots.get(slot);

        if (slot1.hasItem()) {
            ItemStack itemstack1 = slot1.getItem();

            itemstack = itemstack1.copy();
            EquipmentSlot enumitemslot = player.getEquipmentSlotForItem(itemstack);

            if (slot == 0) {
                if (!this.moveItemStackTo(itemstack1, 9, 45, true)) {
                    return ItemStack.EMPTY;
                }

                slot1.onQuickCraft(itemstack1, itemstack);
            } else if (slot >= 1 && slot < 5) {
                if (!this.moveItemStackTo(itemstack1, 9, 45, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slot >= 5 && slot < 9) {
                if (!this.moveItemStackTo(itemstack1, 9, 45, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (enumitemslot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && !((Slot) this.slots.get(8 - enumitemslot.getIndex())).hasItem()) {
                int j = 8 - enumitemslot.getIndex();

                if (!this.moveItemStackTo(itemstack1, j, j + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (enumitemslot == EquipmentSlot.OFFHAND && !((Slot) this.slots.get(45)).hasItem()) {
                if (!this.moveItemStackTo(itemstack1, 45, 46, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slot >= 9 && slot < 36) {
                if (!this.moveItemStackTo(itemstack1, 36, 45, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (slot >= 36 && slot < 45) {
                if (!this.moveItemStackTo(itemstack1, 9, 36, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(itemstack1, 9, 45, false)) {
                return ItemStack.EMPTY;
            }

            if (itemstack1.isEmpty()) {
                slot1.setByPlayer(ItemStack.EMPTY, itemstack);
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
        return this.slots.subList(1, 5);
    }

    public CraftingContainer getCraftSlots() {
        return this.craftSlots;
    }

    @Override
    public RecipeBookType getRecipeBookType() {
        return RecipeBookType.CRAFTING;
    }

    @Override
    protected Player owner() {
        return this.owner;
    }

    // CraftBukkit start
    @Override
    public CraftInventoryView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        CraftInventoryCrafting inventory = new CraftInventoryCrafting(this.craftSlots, this.resultSlots);
        this.bukkitEntity = new CraftInventoryView(this.owner.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
