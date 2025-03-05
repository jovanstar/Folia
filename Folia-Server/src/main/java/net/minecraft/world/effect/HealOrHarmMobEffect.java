package net.minecraft.world.effect;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

class HealOrHarmMobEffect extends InstantenousMobEffect {

    private final boolean isHarm;

    public HealOrHarmMobEffect(MobEffectCategory category, int color, boolean damage) {
        super(category, color);
        this.isHarm = damage;
    }

    @Override
    public boolean applyEffectTick(ServerLevel world, LivingEntity entity, int amplifier) {
        if (this.isHarm == entity.isInvertedHealAndHarm()) {
            entity.heal((float) Math.max(4 << amplifier, 0), org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC); // CraftBukkit
        } else {
            entity.hurtServer(world, entity.damageSources().magic(), (float) (6 << amplifier));
        }

        return true;
    }

    @Override
    public void applyInstantenousEffect(ServerLevel world, @Nullable Entity effectEntity, @Nullable Entity attacker, LivingEntity target, int amplifier, double proximity) {
        int j;

        if (this.isHarm == target.isInvertedHealAndHarm()) {
            j = (int) (proximity * (double) (4 << amplifier) + 0.5D);
            target.heal((float) j, org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason.MAGIC); // CraftBukkit
        } else {
            j = (int) (proximity * (double) (6 << amplifier) + 0.5D);
            if (effectEntity == null) {
                target.hurtServer(world, target.damageSources().magic(), (float) j);
            } else {
                target.hurtServer(world, target.damageSources().indirectMagic(effectEntity, attacker), (float) j);
            }
        }

    }
}
