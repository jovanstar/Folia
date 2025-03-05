package net.minecraft.core.dispenser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseEvent;
// CraftBukkit end

public class MinecartDispenseItemBehavior extends DefaultDispenseItemBehavior {

    private final DefaultDispenseItemBehavior defaultDispenseItemBehavior = new DefaultDispenseItemBehavior();
    private final EntityType<? extends AbstractMinecart> entityType;

    public MinecartDispenseItemBehavior(EntityType<? extends AbstractMinecart> minecartEntityType) {
        this.entityType = minecartEntityType;
    }

    @Override
    public ItemStack execute(BlockSource pointer, ItemStack stack) {
        Direction enumdirection = (Direction) pointer.state().getValue(DispenserBlock.FACING);
        ServerLevel worldserver = pointer.level();
        Vec3 vec3d = pointer.center();
        double d0 = vec3d.x() + (double) enumdirection.getStepX() * 1.125D;
        double d1 = Math.floor(vec3d.y()) + (double) enumdirection.getStepY();
        double d2 = vec3d.z() + (double) enumdirection.getStepZ() * 1.125D;
        BlockPos blockposition = pointer.pos().relative(enumdirection);
        BlockState iblockdata = worldserver.getBlockState(blockposition);
        double d3;

        if (iblockdata.is(BlockTags.RAILS)) {
            if (MinecartDispenseItemBehavior.getRailShape(iblockdata).isSlope()) {
                d3 = 0.6D;
            } else {
                d3 = 0.1D;
            }
        } else {
            if (!iblockdata.isAir()) {
                return this.defaultDispenseItemBehavior.dispense(pointer, stack);
            }

            BlockState iblockdata1 = worldserver.getBlockState(blockposition.below());

            if (!iblockdata1.is(BlockTags.RAILS)) {
                return this.defaultDispenseItemBehavior.dispense(pointer, stack);
            }

            if (enumdirection != Direction.DOWN && MinecartDispenseItemBehavior.getRailShape(iblockdata1).isSlope()) {
                d3 = -0.4D;
            } else {
                d3 = -0.9D;
            }
        }

        Vec3 vec3d1 = new Vec3(d0, d1 + d3, d2);
        // CraftBukkit start
        // EntityMinecartAbstract entityminecartabstract = EntityMinecartAbstract.createMinecart(worldserver, vec3d1.x, vec3d1.y, vec3d1.z, this.entityType, EntitySpawnReason.DISPENSER, itemstack, (EntityHuman) null);
        ItemStack itemstack1 = stack.copyWithCount(1); // Paper - shrink below and single item in event
        org.bukkit.block.Block block2 = CraftBlock.at(worldserver, pointer.pos());
        CraftItemStack craftItem = CraftItemStack.asCraftMirror(itemstack1);

        BlockDispenseEvent event = new BlockDispenseEvent(block2, craftItem.clone(), new org.bukkit.util.Vector(vec3d1.x, vec3d1.y, vec3d1.z));
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

        itemstack1 = CraftItemStack.asNMSCopy(event.getItem());
        AbstractMinecart entityminecartabstract = AbstractMinecart.createMinecart(worldserver, event.getVelocity().getX(), event.getVelocity().getY(), event.getVelocity().getZ(), this.entityType, EntitySpawnReason.DISPENSER, itemstack1, (Player) null);

        if (entityminecartabstract != null) {
            if (worldserver.addFreshEntity(entityminecartabstract) && shrink) stack.shrink(1); // Paper - if entity add was successful and supposed to shrink
            // CraftBukkit end
        }

        return stack;
    }

    private static RailShape getRailShape(BlockState state) {
        Block block = state.getBlock();
        RailShape blockpropertytrackposition;

        if (block instanceof BaseRailBlock blockminecarttrackabstract) {
            blockpropertytrackposition = (RailShape) state.getValue(blockminecarttrackabstract.getShapeProperty());
        } else {
            blockpropertytrackposition = RailShape.NORTH_SOUTH;
        }

        return blockpropertytrackposition;
    }

    @Override
    protected void playSound(BlockSource pointer) {
        pointer.level().levelEvent(1000, pointer.pos(), 0);
    }
}
