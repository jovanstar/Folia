package net.minecraft.core.dispenser;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.phys.AABB;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockDispenseArmorEvent;
// CraftBukkit end

public class EquipmentDispenseItemBehavior extends DefaultDispenseItemBehavior {

    public static final EquipmentDispenseItemBehavior INSTANCE = new EquipmentDispenseItemBehavior();

    public EquipmentDispenseItemBehavior() {}

    @Override
    protected ItemStack execute(BlockSource pointer, ItemStack stack) {
        return EquipmentDispenseItemBehavior.dispenseEquipment(pointer, stack, this) ? stack : super.execute(pointer, stack); // Paper - fix possible StackOverflowError
    }

    @Deprecated @io.papermc.paper.annotation.DoNotUse // Paper
    public static boolean dispenseEquipment(BlockSource pointer, ItemStack armor) {
        // Paper start
        return dispenseEquipment(pointer, armor, null);
    }
    public static boolean dispenseEquipment(BlockSource pointer, ItemStack stack, @javax.annotation.Nullable DispenseItemBehavior currentBehavior) {
        BlockPos blockposition = pointer.pos().relative((Direction) pointer.state().getValue(DispenserBlock.FACING));
        List<LivingEntity> list = pointer.level().getEntitiesOfClass(LivingEntity.class, new AABB(blockposition), (entityliving) -> {
            return entityliving.canEquipWithDispenser(stack);
        });

        if (list.isEmpty()) {
            return false;
        } else {
            LivingEntity entityliving = (LivingEntity) list.getFirst();
            EquipmentSlot enumitemslot = entityliving.getEquipmentSlotForItem(stack);
            ItemStack itemstack1 = stack.copyWithCount(1); // Paper - shrink below and single item in event

            // CraftBukkit start
            Level world = pointer.level();
            org.bukkit.block.Block block = CraftBlock.at(world, pointer.pos());
            CraftItemStack craftItem = CraftItemStack.asCraftMirror(itemstack1);

            BlockDispenseArmorEvent event = new BlockDispenseArmorEvent(block, craftItem.clone(), (org.bukkit.craftbukkit.entity.CraftLivingEntity) entityliving.getBukkitEntity());
            if (!DispenserBlock.eventFired.get().booleanValue()) { // Folia - region threading
                world.getCraftServer().getPluginManager().callEvent(event);
            }

            if (event.isCancelled()) {
                // stack.grow(1); // Paper - shrink below
                return false;
            }

            boolean shrink = true; // Paper
            if (!event.getItem().equals(craftItem)) {
                shrink = false; // Paper - shrink below
                // Chain to handler for new item
                ItemStack eventStack = CraftItemStack.asNMSCopy(event.getItem());
                DispenseItemBehavior idispensebehavior = DispenserBlock.getDispenseBehavior(pointer, eventStack); // Paper - Fix NPE with equippable and items without behavior
                if (idispensebehavior != DispenseItemBehavior.NOOP && (currentBehavior == null || idispensebehavior != currentBehavior)) { // Paper - fix possible StackOverflowError
                    idispensebehavior.dispense(pointer, eventStack);
                    return true;
                }
            }

            entityliving.setItemSlot(enumitemslot, CraftItemStack.asNMSCopy(event.getItem()));
            // CraftBukkit end
            if (entityliving instanceof Mob) {
                Mob entityinsentient = (Mob) entityliving;

                entityinsentient.setDropChance(enumitemslot, 2.0F);
                entityinsentient.setPersistenceRequired();
            }

            if (shrink) stack.shrink(1); // Paper - shrink here
            return true;
        }
    }
}
