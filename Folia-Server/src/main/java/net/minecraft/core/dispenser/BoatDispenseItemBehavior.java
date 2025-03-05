package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseEvent;
// CraftBukkit end

public class BoatDispenseItemBehavior extends DefaultDispenseItemBehavior {

    private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();
    private final EntityType<? extends AbstractBoat> type;

    public BoatDispenseItemBehavior(EntityType<? extends AbstractBoat> boatType) {
        this.type = boatType;
    }

    @Override
    public ItemStack execute(BlockSource pointer, ItemStack stack) {
        Direction enumdirection = (Direction) pointer.state().getValue(DispenserBlock.FACING);
        ServerLevel worldserver = pointer.level();
        Vec3 vec3d = pointer.center();
        double d0 = 0.5625D + (double) this.type.getWidth() / 2.0D;
        double d1 = vec3d.x() + (double) enumdirection.getStepX() * d0;
        double d2 = vec3d.y() + (double) ((float) enumdirection.getStepY() * 1.125F);
        double d3 = vec3d.z() + (double) enumdirection.getStepZ() * d0;
        BlockPos blockposition = pointer.pos().relative(enumdirection);
        double d4;

        if (worldserver.getFluidState(blockposition).is(FluidTags.WATER)) {
            d4 = 1.0D;
        } else {
            if (!worldserver.getBlockState(blockposition).isAir() || !worldserver.getFluidState(blockposition.below()).is(FluidTags.WATER)) {
                return this.defaultDispenseItemBehavior.dispense(pointer, stack);
            }

            d4 = 0.0D;
        }

        // CraftBukkit start
        ItemStack itemstack1 = stack.copyWithCount(1); // Paper - shrink at end and single item in event
        org.bukkit.block.Block block = CraftBlock.at(worldserver, pointer.pos());
        CraftItemStack craftItem = CraftItemStack.asCraftMirror(itemstack1);

        BlockDispenseEvent event = new BlockDispenseEvent(block, craftItem.clone(), new org.bukkit.util.Vector(d1, d2 + d4, d3));
        if (!DispenserBlock.eventFired.get().booleanValue()) { // Folia - region threading
            worldserver.getCraftServer().getPluginManager().callEvent(event);
        }

        if (event.isCancelled()) {
            // stack.grow(1); // Paper - shrink below
            return stack;
        }

        boolean shrink = true; // Paper
        if (!event.getItem().equals(craftItem)) {
            shrink = false; // Paper - shrink below
            // Chain to handler for new item
            ItemStack eventStack = CraftItemStack.asNMSCopy(event.getItem());
            DispenseItemBehavior idispensebehavior = DispenserBlock.getDispenseBehavior(pointer, eventStack); // Paper - Fix NPE with equippable and items without behavior
            if (idispensebehavior != DispenseItemBehavior.NOOP && idispensebehavior != this) {
                idispensebehavior.dispense(pointer, eventStack);
                return stack;
            }
        }
        // CraftBukkit end
        AbstractBoat abstractboat = (AbstractBoat) this.type.create(worldserver, EntitySpawnReason.DISPENSER);

        if (abstractboat != null) {
            abstractboat.setInitialPos(event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ()); // CraftBukkit
            EntityType.createDefaultStackConfig(worldserver, stack, (Player) null).accept(abstractboat);
            abstractboat.setYRot(enumdirection.toYRot());
            if (worldserver.addFreshEntity(abstractboat) && shrink) stack.shrink(1); // Paper - if entity add was successful and supposed to shrink
        }

        return stack;
    }

    @Override
    protected void playSound(BlockSource pointer) {
        pointer.level().levelEvent(1000, pointer.pos(), 0);
    }
}
