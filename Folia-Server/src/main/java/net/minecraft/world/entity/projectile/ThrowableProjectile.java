package net.minecraft.world.entity.projectile;

import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public abstract class ThrowableProjectile extends Projectile {

    private static final float MIN_CAMERA_DISTANCE_SQUARED = 12.25F;

    protected ThrowableProjectile(EntityType<? extends ThrowableProjectile> type, Level world) {
        super(type, world);
    }

    protected ThrowableProjectile(EntityType<? extends ThrowableProjectile> type, double x, double y, double z, Level world) {
        this(type, world);
        this.setPos(x, y, z);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        if (this.tickCount < 2 && distance < 12.25D) {
            return false;
        } else {
            double d1 = this.getBoundingBox().getSize() * 4.0D;

            if (Double.isNaN(d1)) {
                d1 = 4.0D;
            }

            d1 *= 64.0D;
            return distance < d1 * d1;
        }
    }

    @Override
    public boolean canUsePortal(boolean allowVehicles) {
        return true;
    }

    @Override
    public void tick() {
        // Folia start - region threading - make sure entities do not move into regions they do not own
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((net.minecraft.server.level.ServerLevel)this.level(), this.position(), this.getDeltaMovement(), 1)) {
            return;
        }
        // Folia end - region threading - make sure entities do not move into regions they do not own
        this.handleFirstTickBubbleColumn();
        this.applyGravity();
        this.applyInertia();
        HitResult movingobjectposition = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        Vec3 vec3d;

        if (movingobjectposition.getType() != HitResult.Type.MISS) {
            vec3d = movingobjectposition.getLocation();
        } else {
            vec3d = this.position().add(this.getDeltaMovement());
        }

        this.setPos(vec3d);
        this.updateRotation();
        this.applyEffectsFromBlocks();
        super.tick();
        if (movingobjectposition.getType() != HitResult.Type.MISS && this.isAlive()) {
            this.preHitTargetOrDeflectSelf(movingobjectposition); // CraftBukkit - projectile hit event
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

            f = 0.8F;
        } else {
            f = 0.99F;
        }

        this.setDeltaMovement(vec3d.scale((double) f));
    }

    private void handleFirstTickBubbleColumn() {
        if (this.firstTick) {
            Iterator iterator = BlockPos.betweenClosed(this.getBoundingBox()).iterator();

            while (iterator.hasNext()) {
                BlockPos blockposition = (BlockPos) iterator.next();
                BlockState iblockdata = this.level().getBlockState(blockposition);

                if (iblockdata.is(Blocks.BUBBLE_COLUMN)) {
                    iblockdata.entityInside(this.level(), blockposition, this);
                }
            }
        }

    }

    @Override
    protected double getDefaultGravity() {
        return 0.03D;
    }
}
