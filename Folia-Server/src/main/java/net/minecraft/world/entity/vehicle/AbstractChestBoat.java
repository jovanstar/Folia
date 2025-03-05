package net.minecraft.world.entity.vehicle;

import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HasCustomInventoryScreen;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.LootTable;

// CraftBukkit start
import java.util.List;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.inventory.InventoryHolder;
// CraftBukkit end

public abstract class AbstractChestBoat extends AbstractBoat implements HasCustomInventoryScreen, ContainerEntity {

    private static final int CONTAINER_SIZE = 27;
    private NonNullList<ItemStack> itemStacks;
    @Nullable
    private ResourceKey<LootTable> lootTable;
    private long lootTableSeed;

    public AbstractChestBoat(EntityType<? extends AbstractChestBoat> type, Level world, Supplier<Item> itemSupplier) {
        super(type, world, itemSupplier);
        this.itemStacks = NonNullList.withSize(27, ItemStack.EMPTY);
    }

    @Override
    protected float getSinglePassengerXOffset() {
        return 0.15F;
    }

    @Override
    protected int getMaxPassengers() {
        return 1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        this.addChestVehicleSaveData(nbt, this.registryAccess());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        this.readChestVehicleSaveData(nbt, this.registryAccess());
    }

    @Override
    public void destroy(ServerLevel world, DamageSource damageSource) {
        this.destroy(world, this.getDropItem());
        this.chestVehicleDestroyed(damageSource, world, this);
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        // CraftBukkit start - add Bukkit remove cause
        this.remove(reason, null);
    }

    @Override
    public void remove(Entity.RemovalReason entity_removalreason, EntityRemoveEvent.Cause cause) {
        // CraftBukkit end
        if (!this.level().isClientSide && entity_removalreason.shouldDestroy()) {
            Containers.dropContents(this.level(), (Entity) this, (Container) this);
        }

        super.remove(entity_removalreason, cause); // CraftBukkit - add Bukkit remove cause
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        InteractionResult enuminteractionresult;

        if (!player.isSecondaryUseActive()) {
            enuminteractionresult = super.interact(player, hand);
            if (enuminteractionresult != InteractionResult.PASS) {
                return enuminteractionresult;
            }
        }

        if (this.canAddPassenger(player) && !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        } else {
            enuminteractionresult = this.interactWithContainerVehicle(player);
            if (enuminteractionresult.consumesAction()) {
                Level world = player.level();

                if (world instanceof ServerLevel) {
                    ServerLevel worldserver = (ServerLevel) world;

                    this.gameEvent(GameEvent.CONTAINER_OPEN, player);
                    PiglinAi.angerNearbyPiglins(worldserver, player, true);
                }
            }

            return enuminteractionresult;
        }
    }

    @Override
    public void openCustomInventoryScreen(Player player) {
        // Paper - fix inventory open cancel - moved into below if
        Level world = player.level();

        if (world instanceof ServerLevel worldserver && player.openMenu(this).isPresent()) { // Paper - Fix InventoryOpenEvent cancellation
            this.gameEvent(GameEvent.CONTAINER_OPEN, player);
            PiglinAi.angerNearbyPiglins(worldserver, player, true);
        }

    }

    @Override
    public void clearContent() {
        this.clearChestVehicleContent();
    }

    @Override
    public int getContainerSize() {
        return 27;
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.getChestVehicleItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return this.removeChestVehicleItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return this.removeChestVehicleItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        this.setChestVehicleItem(slot, stack);
    }

    @Override
    public SlotAccess getSlot(int mappedIndex) {
        return this.getChestVehicleSlot(mappedIndex);
    }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) {
        return this.isChestVehicleStillValid(player);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        if (this.lootTable != null && player.isSpectator()) { // Paper - LootTable API (TODO spectators can open chests that aren't ready to be re-generated but this doesn't support that)
            return null;
        } else {
            this.unpackLootTable(playerInventory.player);
            return ChestMenu.threeRows(syncId, playerInventory, this);
        }
    }

    public void unpackLootTable(@Nullable Player player) {
        this.unpackChestVehicleLootTable(player);
    }

    @Nullable
    @Override
    public ResourceKey<LootTable> getContainerLootTable() {
        return this.lootTable;
    }

    @Override
    public void setContainerLootTable(@Nullable ResourceKey<LootTable> lootTable) {
        this.lootTable = lootTable;
    }

    @Override
    public long getContainerLootTableSeed() {
        return this.lootTableSeed;
    }

    @Override
    public void setContainerLootTableSeed(long lootTableSeed) {
        this.lootTableSeed = lootTableSeed;
    }

    @Override
    public NonNullList<ItemStack> getItemStacks() {
        return this.itemStacks;
    }

    @Override
    public void clearItemStacks() {
        this.itemStacks = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
    }

    @Override
    public void stopOpen(Player player) {
        this.level().gameEvent((Holder) GameEvent.CONTAINER_CLOSE, this.position(), GameEvent.Context.of((Entity) player));
    }

    // Paper start - LootTable API
    final com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData = new com.destroystokyo.paper.loottable.PaperLootableInventoryData();

    @Override
    public com.destroystokyo.paper.loottable.PaperLootableInventoryData lootableData() {
        return this.lootableData;
    }
    // Paper end - LootTable API
    // CraftBukkit start
    public List<HumanEntity> transaction = new java.util.ArrayList<HumanEntity>();
    private int maxStack = MAX_STACK;

    @Override
    public List<ItemStack> getContents() {
        return this.itemStacks;
    }

    @Override
    public void onOpen(CraftHumanEntity who) {
        this.transaction.add(who);
    }

    @Override
    public void onClose(CraftHumanEntity who) {
        this.transaction.remove(who);
    }

    @Override
    public List<HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public InventoryHolder getOwner() {
        org.bukkit.entity.Entity entity = this.getBukkitEntity();
        if (entity instanceof InventoryHolder) return (InventoryHolder) entity;
        return null;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    @Override
    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }

    @Override
    public Location getLocation() {
        return this.getBukkitEntity().getLocation();
    }
    // CraftBukkit end
}
