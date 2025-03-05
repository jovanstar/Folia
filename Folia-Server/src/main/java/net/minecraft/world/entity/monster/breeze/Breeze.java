package net.minecraft.world.entity.monster.breeze;

import com.mojang.serialization.Dynamic;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

public class Breeze extends Monster {

    private static final int SLIDE_PARTICLES_AMOUNT = 20;
    private static final int IDLE_PARTICLES_AMOUNT = 1;
    private static final int JUMP_DUST_PARTICLES_AMOUNT = 20;
    private static final int JUMP_TRAIL_PARTICLES_AMOUNT = 3;
    private static final int JUMP_TRAIL_DURATION_TICKS = 5;
    private static final int JUMP_CIRCLE_DISTANCE_Y = 10;
    private static final float FALL_DISTANCE_SOUND_TRIGGER_THRESHOLD = 3.0F;
    private static final int WHIRL_SOUND_FREQUENCY_MIN = 1;
    private static final int WHIRL_SOUND_FREQUENCY_MAX = 80;
    public AnimationState idle = new AnimationState();
    public AnimationState slide = new AnimationState();
    public AnimationState slideBack = new AnimationState();
    public AnimationState longJump = new AnimationState();
    public AnimationState shoot = new AnimationState();
    public AnimationState inhale = new AnimationState();
    private int jumpTrailStartedTick = 0;
    private int soundTick = 0;
    private static final ProjectileDeflection PROJECTILE_DEFLECTION = (iprojectile, entity, randomsource) -> {
        entity.level().playSound((Player) null, entity, SoundEvents.BREEZE_DEFLECT, entity.getSoundSource(), 1.0F, 1.0F);
        ProjectileDeflection.REVERSE.deflect(iprojectile, entity, randomsource);
    };

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MOVEMENT_SPEED, 0.6299999952316284D).add(Attributes.MAX_HEALTH, 30.0D).add(Attributes.FOLLOW_RANGE, 24.0D).add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    public Breeze(EntityType<? extends Monster> type, Level world) {
        super(type, world);
        this.setPathfindingMalus(PathType.DANGER_TRAPDOOR, -1.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0F);
        this.xpReward = 10;
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return BreezeAi.makeBrain(this, this.brainProvider().makeBrain(dynamic));
    }

    @Override
    public Brain<Breeze> getBrain() {
        return (Brain<Breeze>) super.getBrain(); // CraftBukkit - decompile error
    }

    @Override
    protected Brain.Provider<Breeze> brainProvider() {
        return Brain.provider(BreezeAi.MEMORY_TYPES, BreezeAi.SENSOR_TYPES);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        if (this.level().isClientSide() && Breeze.DATA_POSE.equals(data)) {
            this.resetAnimations();
            Pose entitypose = this.getPose();

            switch (entitypose) {
                case SHOOTING:
                    this.shoot.startIfStopped(this.tickCount);
                    break;
                case INHALING:
                    this.inhale.startIfStopped(this.tickCount);
                    break;
                case SLIDING:
                    this.slide.startIfStopped(this.tickCount);
            }
        }

        super.onSyncedDataUpdated(data);
    }

    private void resetAnimations() {
        this.shoot.stop();
        this.idle.stop();
        this.inhale.stop();
        this.longJump.stop();
    }

    @Override
    public void tick() {
        Pose entitypose = this.getPose();

        switch (entitypose) {
            case SHOOTING:
            case INHALING:
            case STANDING:
                this.resetJumpTrail().emitGroundParticles(1 + this.getRandom().nextInt(1));
                break;
            case SLIDING:
                this.emitGroundParticles(20);
                break;
            case LONG_JUMPING:
                this.longJump.startIfStopped(this.tickCount);
                this.emitJumpTrailParticles();
        }

        this.idle.startIfStopped(this.tickCount);
        if (entitypose != Pose.SLIDING && this.slide.isStarted()) {
            this.slideBack.start(this.tickCount);
            this.slide.stop();
        }

        this.soundTick = this.soundTick == 0 ? this.random.nextIntBetweenInclusive(1, 80) : this.soundTick - 1;
        if (this.soundTick == 0) {
            this.playWhirlSound();
        }

        super.tick();
    }

    public Breeze resetJumpTrail() {
        this.jumpTrailStartedTick = 0;
        return this;
    }

    public void emitJumpTrailParticles() {
        if (++this.jumpTrailStartedTick <= 5) {
            BlockState iblockdata = !this.getInBlockState().isAir() ? this.getInBlockState() : this.getBlockStateOn();
            Vec3 vec3d = this.getDeltaMovement();
            Vec3 vec3d1 = this.position().add(vec3d).add(0.0D, 0.10000000149011612D, 0.0D);

            for (int i = 0; i < 3; ++i) {
                this.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, iblockdata), vec3d1.x, vec3d1.y, vec3d1.z, 0.0D, 0.0D, 0.0D);
            }

        }
    }

    public void emitGroundParticles(int count) {
        if (!this.isPassenger()) {
            Vec3 vec3d = this.getBoundingBox().getCenter();
            Vec3 vec3d1 = new Vec3(vec3d.x, this.position().y, vec3d.z);
            BlockState iblockdata = !this.getInBlockState().isAir() ? this.getInBlockState() : this.getBlockStateOn();

            if (iblockdata.getRenderShape() != RenderShape.INVISIBLE) {
                for (int j = 0; j < count; ++j) {
                    this.level().addParticle(new BlockParticleOption(ParticleTypes.BLOCK, iblockdata), vec3d1.x, vec3d1.y, vec3d1.z, 0.0D, 0.0D, 0.0D);
                }

            }
        }
    }

    @Override
    public void playAmbientSound() {
        if (this.getTarget() == null || !this.onGround()) {
            this.level().playLocalSound(this, this.getAmbientSound(), this.getSoundSource(), 1.0F, 1.0F);
        }
    }

    public void playWhirlSound() {
        float f = 0.7F + 0.4F * this.random.nextFloat();
        float f1 = 0.8F + 0.2F * this.random.nextFloat();

        this.level().playLocalSound(this, SoundEvents.BREEZE_WHIRL, this.getSoundSource(), f1, f);
    }

    @Override
    public ProjectileDeflection deflection(Projectile projectile) {
        return projectile.getType() != EntityType.BREEZE_WIND_CHARGE && projectile.getType() != EntityType.WIND_CHARGE ? (this.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES) ? Breeze.PROJECTILE_DEFLECTION : ProjectileDeflection.NONE) : ProjectileDeflection.NONE;
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.BREEZE_DEATH;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BREEZE_HURT;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return this.onGround() ? SoundEvents.BREEZE_IDLE_GROUND : SoundEvents.BREEZE_IDLE_AIR;
    }

    public Optional<LivingEntity> getHurtBy() {
        return this.getBrain().getMemory(MemoryModuleType.HURT_BY).map(DamageSource::getEntity).filter((entity) -> {
            return entity instanceof LivingEntity;
        }).map((entity) -> {
            return (LivingEntity) entity;
        });
    }

    public boolean withinInnerCircleRange(Vec3 pos) {
        Vec3 vec3d1 = this.blockPosition().getCenter();

        return pos.closerThan(vec3d1, 4.0D, 10.0D);
    }

    @Override
    protected void customServerAiStep(ServerLevel world) {
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("breezeBrain");
        this.getBrain().tick(world, this);
        gameprofilerfiller.popPush("breezeActivityUpdate");
        BreezeAi.updateActivity(this);
        gameprofilerfiller.pop();
        super.customServerAiStep(world);
    }

    @Override
    protected void sendDebugPackets() {
        super.sendDebugPackets();
        DebugPackets.sendEntityBrain(this);
        DebugPackets.sendBreezeInfo(this);
    }

    @Override
    public boolean canAttackType(EntityType<?> type) {
        if (this.getTarget() != null) return this.getTarget().getType() == type; // SPIGOT-7957: Allow attack if target from brain was set
        return type == EntityType.PLAYER || type == EntityType.IRON_GOLEM;
    }

    @Override
    public int getMaxHeadYRot() {
        return 30;
    }

    @Override
    public int getHeadRotSpeed() {
        return 25;
    }

    public double getFiringYPosition() {
        return this.getY() + (double) (this.getBbHeight() / 2.0F) + 0.30000001192092896D;
    }

    @Override
    public boolean isInvulnerableTo(ServerLevel world, DamageSource source) {
        return source.getEntity() instanceof Breeze || super.isInvulnerableTo(world, source);
    }

    @Override
    public double getFluidJumpThreshold() {
        return (double) this.getEyeHeight();
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        if (fallDistance > 3.0F) {
            this.playSound(SoundEvents.BREEZE_LAND, 1.0F, 1.0F);
        }

        return super.causeFallDamage(fallDistance, damageMultiplier, damageSource);
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.EVENTS;
    }

    @Nullable
    @Override
    public LivingEntity getTarget() {
        return this.getTargetFromBrain();
    }
}
