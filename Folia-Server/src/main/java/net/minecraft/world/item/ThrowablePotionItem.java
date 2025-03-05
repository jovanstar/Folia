package net.minecraft.world.item;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.level.Level;

public class ThrowablePotionItem extends PotionItem implements ProjectileItem {
    public static float PROJECTILE_SHOOT_POWER = 0.5F;

    public ThrowablePotionItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemStack = user.getItemInHand(hand);
        if (world instanceof ServerLevel serverLevel) {
            // Paper start - PlayerLaunchProjectileEvent
            final Projectile.Delayed<ThrownPotion> thrownPotion = Projectile.spawnProjectileFromRotationDelayed(ThrownPotion::new, serverLevel, itemStack, user, -20.0F, PROJECTILE_SHOOT_POWER, 1.0F);
            // Paper start - PlayerLaunchProjectileEvent
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) user.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemStack), (org.bukkit.entity.Projectile) thrownPotion.projectile().getBukkitEntity());
            if (event.callEvent() && thrownPotion.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemStack.consume(1, user);
                } else if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }

                user.awardStat(Stats.ITEM_USED.get(this));
            } else {
                if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }
                return InteractionResult.FAIL;
            }
            // Paper end - PlayerLaunchProjectileEvent
        }

        // Paper - PlayerLaunchProjectileEvent - move up
        return InteractionResult.SUCCESS;
    }

    @Override
    public Projectile asProjectile(Level world, Position pos, ItemStack stack, Direction direction) {
        return new ThrownPotion(world, pos.x(), pos.y(), pos.z(), stack);
    }

    @Override
    public ProjectileItem.DispenseConfig createDispenseConfig() {
        return ProjectileItem.DispenseConfig.builder()
            .uncertainty(ProjectileItem.DispenseConfig.DEFAULT.uncertainty() * 0.5F)
            .power(ProjectileItem.DispenseConfig.DEFAULT.power() * 1.25F)
            .build();
    }
}
