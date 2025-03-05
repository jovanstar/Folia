package net.minecraft.world.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class ItemUtils {
    public static InteractionResult startUsingInstantly(Level world, Player player, InteractionHand hand) {
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    public static ItemStack createFilledResult(ItemStack inputStack, Player player, ItemStack outputStack, boolean creativeOverride) {
        boolean bl = player.hasInfiniteMaterials();
        if (creativeOverride && bl) {
            if (!player.getInventory().contains(outputStack)) {
                player.getInventory().add(outputStack);
            }

            return inputStack;
        } else {
            inputStack.consume(1, player);
            if (inputStack.isEmpty()) {
                return outputStack;
            } else {
                if (!player.getInventory().add(outputStack)) {
                    player.drop(outputStack, false);
                }

                return inputStack;
            }
        }
    }

    public static ItemStack createFilledResult(ItemStack inputStack, Player player, ItemStack outputStack) {
        return createFilledResult(inputStack, player, outputStack, true);
    }

    public static void onContainerDestroyed(ItemEntity itemEntity, Iterable<ItemStack> contents) {
        Level level = itemEntity.level();
        if (!level.isClientSide) {
            // Paper start - call EntityDropItemEvent
            contents.forEach(stack -> {
                ItemEntity droppedItem = new ItemEntity(level, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(), stack);
                org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(itemEntity.getBukkitEntity(), (org.bukkit.entity.Item) droppedItem.getBukkitEntity());
                if (event.callEvent()) {
                    level.addFreshEntity(droppedItem);
                }
            });
            // Paper end - call EntityDropItemEvent
        }
    }
}
