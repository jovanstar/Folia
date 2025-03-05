package net.minecraft.world.entity.monster;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

public class Husk extends Zombie {

    public Husk(EntityType<? extends Husk> type, Level world) {
        super(type, world);
    }

    public static boolean checkHuskSpawnRules(EntityType<Husk> type, ServerLevelAccessor world, EntitySpawnReason spawnReason, BlockPos pos, RandomSource random) {
        return checkMonsterSpawnRules(type, world, spawnReason, pos, random) && (EntitySpawnReason.isSpawner(spawnReason) || world.canSeeSky(pos));
    }

    @Override
    public boolean isSunSensitive() {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.HUSK_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.HUSK_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.HUSK_DEATH;
    }

    @Override
    protected SoundEvent getStepSound() {
        return SoundEvents.HUSK_STEP;
    }

    @Override
    public boolean doHurtTarget(ServerLevel world, Entity target) {
        boolean flag = super.doHurtTarget(world, target);

        if (flag && this.getMainHandItem().isEmpty() && target instanceof LivingEntity) {
            float f = this.level().getCurrentDifficultyAt(this.blockPosition()).getEffectiveDifficulty();

            ((LivingEntity) target).addEffect(new MobEffectInstance(MobEffects.HUNGER, 140 * (int) f), this, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.ATTACK); // CraftBukkit
        }

        return flag;
    }

    @Override
    protected boolean convertsInWater() {
        return true;
    }

    @Override
    protected void doUnderWaterConversion() {
        this.convertToZombieType(EntityType.ZOMBIE);
        if (!this.isSilent()) {
            this.level().levelEvent((Player) null, 1041, this.blockPosition(), 0);
        }

    }

    @Override
    protected ItemStack getSkull() {
        return ItemStack.EMPTY;
    }
}
