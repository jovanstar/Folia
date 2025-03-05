package net.minecraft.world.entity.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

public class MinecartHopper extends AbstractMinecartContainer implements Hopper {
    private boolean enabled = true;
    private boolean consumedItemThisFrame = false;

    public MinecartHopper(EntityType<? extends MinecartHopper> type, Level world) {
        super(type, world);
    }

    @Override
    public BlockState getDefaultDisplayBlockState() {
        return Blocks.HOPPER.defaultBlockState();
    }

    @Override
    public int getDefaultDisplayOffset() {
        return 1;
    }

    @Override
    public int getContainerSize() {
        return 5;
    }

    @Override
    public void activateMinecart(int x, int y, int z, boolean powered) {
        boolean bl = !powered;
        if (bl != this.isEnabled()) {
            this.setEnabled(bl);
        }
        this.immunize();  // Paper
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public double getLevelX() {
        return this.getX();
    }

    @Override
    public double getLevelY() {
        return this.getY() + 0.5;
    }

    @Override
    public double getLevelZ() {
        return this.getZ();
    }

    @Override
    public boolean isGridAligned() {
        return false;
    }

    @Override
    public void tick() {
        this.consumedItemThisFrame = false;
        super.tick();
        this.tryConsumeItems();
    }

    @Override
    protected double makeStepAlongTrack(BlockPos pos, RailShape shape, double remainingMovement) {
        double d = super.makeStepAlongTrack(pos, shape, remainingMovement);
        this.tryConsumeItems();
        return d;
    }

    private void tryConsumeItems() {
        if (!this.level().isClientSide && this.isAlive() && this.isEnabled() && !this.consumedItemThisFrame && this.suckInItems()) {
            this.consumedItemThisFrame = true;
            this.setChanged();
        }
    }

    public boolean suckInItems() {
        if (HopperBlockEntity.suckInItems(this.level(), this)) {
            this.immunize();  // Paper
            return true;
        } else {
            for (ItemEntity itemEntity : this.level()
                .getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(0.25, 0.0, 0.25), EntitySelector.ENTITY_STILL_ALIVE)) {
                if (HopperBlockEntity.addItem(this, itemEntity)) {
                    this.immunize();  // Paper
                    return true;
                }
            }

            return false;
        }
    }

    @Override
    protected Item getDropItem() {
        return Items.HOPPER_MINECART;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(Items.HOPPER_MINECART);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("Enabled", this.enabled);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        this.enabled = !nbt.contains("Enabled") || nbt.getBoolean("Enabled");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory) {
        return new HopperMenu(syncId, playerInventory, this);
    }

    // Paper start
    public void immunize() {
        this.activatedImmunityTick = Math.max(this.activatedImmunityTick, io.papermc.paper.threadedregions.RegionizedServer.getCurrentTick() + 20);
    }
    // Paper end

}
