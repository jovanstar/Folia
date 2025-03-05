package net.minecraft.world.item;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.level.Level;

public class EggItem extends Item implements ProjectileItem {

    public static float PROJECTILE_SHOOT_POWER = 1.5F;

    public EggItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);

        // world.playSound((EntityHuman) null, entityhuman.getX(), entityhuman.getY(), entityhuman.getZ(), SoundEffects.EGG_THROW, SoundCategory.PLAYERS, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F)); // CraftBukkit - moved down
        if (world instanceof ServerLevel worldserver) {
            // CraftBukkit start
            // Paper start - PlayerLaunchProjectileEvent
            final Projectile.Delayed<ThrownEgg> thrownEgg = Projectile.spawnProjectileFromRotationDelayed(ThrownEgg::new, worldserver, itemstack, user, 0.0F, EggItem.PROJECTILE_SHOOT_POWER, 1.0F);
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) user.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemstack), (org.bukkit.entity.Projectile) thrownEgg.projectile().getBukkitEntity());
            if (event.callEvent() && thrownEgg.attemptSpawn()) {
                if (event.shouldConsume()) {
                    itemstack.consume(1, user);
                } else if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }

                world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), SoundEvents.EGG_THROW, SoundSource.PLAYERS, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));
                user.awardStat(Stats.ITEM_USED.get(this));
            } else {
                // Paper end - PlayerLaunchProjectileEvent
                if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }
                return InteractionResult.FAIL;
            }
            // CraftBukkit end
        }
        world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), SoundEvents.EGG_THROW, SoundSource.PLAYERS, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));
        // Paper - PlayerLaunchProjectileEvent - moved up
        return InteractionResult.SUCCESS;
    }

    @Override
    public Projectile asProjectile(Level world, Position pos, ItemStack stack, Direction direction) {
        return new ThrownEgg(world, pos.x(), pos.y(), pos.z(), stack);
    }
}
