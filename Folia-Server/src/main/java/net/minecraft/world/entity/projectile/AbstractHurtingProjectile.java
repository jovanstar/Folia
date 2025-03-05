package net.minecraft.world.entity.projectile;

import javax.annotation.Nullable;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.event.entity.EntityRemoveEvent;
// CraftBukkit end

public abstract class AbstractHurtingProjectile extends Projectile {

    public static final double INITAL_ACCELERATION_POWER = 0.1D;
    public static final double DEFLECTION_SCALE = 0.5D;
    public double accelerationPower;
    public float bukkitYield = 1; // CraftBukkit
    public boolean isIncendiary = true; // CraftBukkit

    protected AbstractHurtingProjectile(EntityType<? extends AbstractHurtingProjectile> type, Level world) {
        super(type, world);
        this.accelerationPower = 0.1D;
    }

    protected AbstractHurtingProjectile(EntityType<? extends AbstractHurtingProjectile> type, double x, double y, double z, Level world) {
        this(type, world);
        this.setPos(x, y, z);
    }

    public AbstractHurtingProjectile(EntityType<? extends AbstractHurtingProjectile> type, double x, double y, double z, Vec3 velocity, Level world) {
        this(type, world);
        this.moveTo(x, y, z, this.getYRot(), this.getXRot());
        this.reapplyPosition();
        this.assignDirectionalMovement(velocity, this.accelerationPower);
    }

    public AbstractHurtingProjectile(EntityType<? extends AbstractHurtingProjectile> type, LivingEntity owner, Vec3 velocity, Level world) {
        this(type, owner.getX(), owner.getY(), owner.getZ(), velocity, world);
        this.setOwner(owner);
        this.setRot(owner.getYRot(), owner.getXRot());
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        double d1 = this.getBoundingBox().getSize() * 4.0D;

        if (Double.isNaN(d1)) {
            d1 = 4.0D;
        }

        d1 *= 64.0D;
        return distance < d1 * d1;
    }

    protected ClipContext.Block getClipType() {
        return ClipContext.Block.COLLIDER;
    }

    @Override
    public void tick() {
        Entity entity = this.getOwner();

        this.applyInertia();
        if (!this.level().isClientSide && (entity != null && entity.isRemoved() || !this.level().hasChunkAt(this.blockPosition()))) {
            this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        } else {
            HitResult movingobjectposition = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity, this.getClipType());
            Vec3 vec3d;

            if (movingobjectposition.getType() != HitResult.Type.MISS) {
                vec3d = movingobjectposition.getLocation();
            } else {
                vec3d = this.position().add(this.getDeltaMovement());
            }

            ProjectileUtil.rotateTowardsMovement(this, 0.2F);
            this.setPos(vec3d);
            this.applyEffectsFromBlocks();
            super.tick();
            // Folia start - region threading - make sure entities do not move into regions they do not own
            if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((net.minecraft.server.level.ServerLevel)this.level(), this.position(), this.getDeltaMovement(), 1)) {
                return;
            }
            // Folia end - region threading - make sure entities do not move into regions they do not own
            if (this.shouldBurn()) {
                this.igniteForSeconds(1.0F);
            }

            if (movingobjectposition.getType() != HitResult.Type.MISS && this.isAlive()) {
                this.preHitTargetOrDeflectSelf(movingobjectposition); // CraftBukkit - projectile hit event
            }

            this.createParticleTrail();
        }
    }

    private void applyInertia() {
        Vec3 vec3d = this.getDeltaMovement();
        Vec3 vec3d1 = this.position();
        float f;

        if (this.isInWater()) {
            for (int i = 0; i < 4; ++i) {
                float f1 = 0.25F;

                this.level().addParticle(ParticleTypes.BUBBLE, vec3d1.x - vec3d.x * 0.25D, vec3d1.y - vec3d.y * 0.25D, vec3d1.z - vec3d.z * 0.25D, vec3d.x, vec3d.y, vec3d.z);
            }

            f = this.getLiquidInertia();
        } else {
            f = this.getInertia();
        }

        this.setDeltaMovement(vec3d.add(vec3d.normalize().scale(this.accelerationPower)).scale((double) f));
    }

    private void createParticleTrail() {
        ParticleOptions particleparam = this.getTrailParticle();
        Vec3 vec3d = this.position();

        if (particleparam != null) {
            this.level().addParticle(particleparam, vec3d.x, vec3d.y + 0.5D, vec3d.z, 0.0D, 0.0D, 0.0D);
        }

    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean canHitEntity(Entity entity) {
        return super.canHitEntity(entity) && !entity.noPhysics;
    }

    protected boolean shouldBurn() {
        return true;
    }

    @Nullable
    protected ParticleOptions getTrailParticle() {
        return ParticleTypes.SMOKE;
    }

    protected float getInertia() {
        return 0.95F;
    }

    protected float getLiquidInertia() {
        return 0.8F;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putDouble("acceleration_power", this.accelerationPower);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        if (nbt.contains("acceleration_power", 6)) {
            this.accelerationPower = nbt.getDouble("acceleration_power");
        }

    }

    @Override
    public float getLightLevelDependentMagicValue() {
        return 1.0F;
    }

    public void assignDirectionalMovement(Vec3 velocity, double accelerationPower) {
        this.setDeltaMovement(velocity.normalize().scale(accelerationPower));
        this.hasImpulse = true;
    }

    @Override
    protected void onDeflection(@Nullable Entity deflector, boolean fromAttack) {
        super.onDeflection(deflector, fromAttack);
        if (fromAttack) {
            this.accelerationPower = 0.1D;
        } else {
            this.accelerationPower *= 0.5D;
        }

    }
}
