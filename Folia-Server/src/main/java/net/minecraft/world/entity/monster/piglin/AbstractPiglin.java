package net.minecraft.world.entity.monster.piglin;

import com.google.common.annotations.VisibleForTesting;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.GoalUtils;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathType;

public abstract class AbstractPiglin extends Monster {

    protected static final EntityDataAccessor<Boolean> DATA_IMMUNE_TO_ZOMBIFICATION = SynchedEntityData.defineId(AbstractPiglin.class, EntityDataSerializers.BOOLEAN);
    public static final int CONVERSION_TIME = 300;
    public int timeInOverworld;

    public AbstractPiglin(EntityType<? extends AbstractPiglin> type, Level world) {
        super(type, world);
        this.setCanPickUpLoot(true);
        this.applyOpenDoorsAbility();
        this.setPathfindingMalus(PathType.DANGER_FIRE, 16.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0F);
    }

    private void applyOpenDoorsAbility() {
        if (GoalUtils.hasGroundPathNavigation(this)) {
            ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        }

    }

    protected abstract boolean canHunt();

    public void setImmuneToZombification(boolean immuneToZombification) {
        this.getEntityData().set(AbstractPiglin.DATA_IMMUNE_TO_ZOMBIFICATION, immuneToZombification);
    }

    public boolean isImmuneToZombification() {
        return (Boolean) this.getEntityData().get(AbstractPiglin.DATA_IMMUNE_TO_ZOMBIFICATION);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AbstractPiglin.DATA_IMMUNE_TO_ZOMBIFICATION, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        if (this.isImmuneToZombification()) {
            nbt.putBoolean("IsImmuneToZombification", true);
        }

        nbt.putInt("TimeInOverworld", this.timeInOverworld);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        this.setImmuneToZombification(nbt.getBoolean("IsImmuneToZombification"));
        this.timeInOverworld = nbt.getInt("TimeInOverworld");
    }

    @Override
    protected void customServerAiStep(ServerLevel world) {
        super.customServerAiStep(world);
        if (this.isConverting()) {
            ++this.timeInOverworld;
        } else {
            this.timeInOverworld = 0;
        }

        if (this.timeInOverworld > 300) {
            this.playConvertedSound();
            this.finishConversion(world);
        }

    }

    @VisibleForTesting
    public void setTimeInOverworld(int timeInOverworld) {
        this.timeInOverworld = timeInOverworld;
    }

    public boolean isConverting() {
        return !this.level().dimensionType().piglinSafe() && !this.isImmuneToZombification() && !this.isNoAi();
    }

    protected void finishConversion(ServerLevel world) {
        net.minecraft.world.entity.Entity converted = this.convertTo(EntityType.ZOMBIFIED_PIGLIN, ConversionParams.single(this, true, true), (entitypigzombie) -> { // Paper - Fix issues with mob conversion; reset to prevent event spam
            entitypigzombie.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200, 0));
        }, org.bukkit.event.entity.EntityTransformEvent.TransformReason.PIGLIN_ZOMBIFIED, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.PIGLIN_ZOMBIFIED); // CraftBukkit - add spawn and transform reasons

        // Paper start - Fix issues with mob conversion; reset to prevent event spam
        if (converted == null) {
            this.timeInOverworld = 0;
        }
        // Paper end - Fix issues with mob conversion
    }

    public boolean isAdult() {
        return !this.isBaby();
    }

    public abstract PiglinArmPose getArmPose();

    @Nullable
    @Override
    public LivingEntity getTarget() {
        return this.getTargetFromBrain();
    }

    protected boolean isHoldingMeleeWeapon() {
        return this.getMainHandItem().has(DataComponents.TOOL);
    }

    @Override
    public void playAmbientSound() {
        if (PiglinAi.isIdle(this)) {
            super.playAmbientSound();
        }

    }

    @Override
    protected void sendDebugPackets() {
        super.sendDebugPackets();
        DebugPackets.sendEntityBrain(this);
    }

    protected abstract void playConvertedSound();
}
