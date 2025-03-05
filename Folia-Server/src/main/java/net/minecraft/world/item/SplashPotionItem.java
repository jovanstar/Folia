package net.minecraft.world.item;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class SplashPotionItem extends ThrowablePotionItem {
    public SplashPotionItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        // Paper start - PlayerLaunchProjectileEvent
        final InteractionResult wrapper = super.use(world, user, hand);
        if (wrapper instanceof InteractionResult.Fail) return wrapper;
        // Paper end - PlayerLaunchProjectileEvent
        world.playSound(
            null,
            user.getX(),
            user.getY(),
            user.getZ(),
            SoundEvents.SPLASH_POTION_THROW,
            SoundSource.PLAYERS,
            0.5F,
            0.4F / (world.getRandom().nextFloat() * 0.4F + 0.8F)
        );
        return wrapper; // Paper - PlayerLaunchProjectileEvent
    }
}
