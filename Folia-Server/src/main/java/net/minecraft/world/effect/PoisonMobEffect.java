package net.minecraft.world.effect;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

public class PoisonMobEffect extends MobEffect {

    public static final int DAMAGE_INTERVAL = 25;

    protected PoisonMobEffect(MobEffectCategory category, int color) {
        super(category, color);
    }

    @Override
    public boolean applyEffectTick(ServerLevel world, LivingEntity entity, int amplifier) {
        if (entity.getHealth() > 1.0F) {
            entity.hurtServer(world, entity.damageSources().poison(), 1.0F);  // CraftBukkit - DamageSource.MAGIC -> CraftEventFactory.POISON
        }

        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        int k = 25 >> amplifier;

        return k > 0 ? duration % k == 0 : true;
    }
}
