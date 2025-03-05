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
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.level.Level;

public class SnowballItem extends Item implements ProjectileItem {

    public static float PROJECTILE_SHOOT_POWER = 1.5F;

    public SnowballItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);

        // CraftBukkit start - moved down
        // world.playSound((EntityHuman) null, entityhuman.getX(), entityhuman.getY(), entityhuman.getZ(), SoundEffects.SNOWBALL_THROW, SoundCategory.NEUTRAL, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));
        if (world instanceof ServerLevel worldserver) {
            // Paper start - PlayerLaunchProjectileEvent
            final Projectile.Delayed<Snowball> snowball = Projectile.spawnProjectileFromRotationDelayed(Snowball::new, worldserver, itemstack, user, 0.0F, SnowballItem.PROJECTILE_SHOOT_POWER, 1.0F);
            com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent event = new com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent((org.bukkit.entity.Player) user.getBukkitEntity(), org.bukkit.craftbukkit.inventory.CraftItemStack.asCraftMirror(itemstack), (org.bukkit.entity.Projectile) snowball.projectile().getBukkitEntity());
            if (event.callEvent() && snowball.attemptSpawn()) {
                user.awardStat(Stats.ITEM_USED.get(this));
                if (event.shouldConsume()) {
                    itemstack.consume(1, user);
                } else if (user instanceof net.minecraft.server.level.ServerPlayer) {
                    ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
                }
            // Paper end - PlayerLaunchProjectileEvent

                world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), SoundEvents.SNOWBALL_THROW, SoundSource.NEUTRAL, 0.5F, 0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F));
            } else { if (user instanceof net.minecraft.server.level.ServerPlayer) { // Paper - PlayerLaunchProjectileEvent - return fail
                ((net.minecraft.server.level.ServerPlayer) user).getBukkitEntity().updateInventory();
            } return InteractionResult.FAIL; } // Paper - PlayerLaunchProjectileEvent - return fail
            // CraftBukkit end
        }

        // Paper - PlayerLaunchProjectileEvent - moved up
        // itemstack.consume(1, entityhuman); // CraftBukkit - moved up
        return InteractionResult.SUCCESS;
    }

    @Override
    public Projectile asProjectile(Level world, Position pos, ItemStack stack, Direction direction) {
        return new Snowball(world, pos.x(), pos.y(), pos.z(), stack);
    }
}
