package net.minecraft.world.entity.vehicle;

import com.mojang.datafixers.util.Pair;
import io.netty.buffer.ByteBuf;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
// CraftBukkit end

public class NewMinecartBehavior extends MinecartBehavior {

    public static final int POS_ROT_LERP_TICKS = 3;
    public static final double ON_RAIL_Y_OFFSET = 0.1D;
    public static final double OPPOSING_SLOPES_REST_AT_SPEED_THRESHOLD = 0.005D;
    @Nullable
    private NewMinecartBehavior.StepPartialTicks cacheIndexAlpha;
    private int cachedLerpDelay;
    private float cachedPartialTick;
    private int lerpDelay = 0;
    public final List<NewMinecartBehavior.MinecartStep> lerpSteps = new LinkedList();
    public final List<NewMinecartBehavior.MinecartStep> currentLerpSteps = new LinkedList();
    public double currentLerpStepsTotalWeight = 0.0D;
    public NewMinecartBehavior.MinecartStep oldLerp;

    public NewMinecartBehavior(AbstractMinecart minecart) {
        super(minecart);
        this.oldLerp = NewMinecartBehavior.MinecartStep.ZERO;
    }

    @Override
    public void tick() {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            BlockPos blockposition = this.minecart.getCurrentBlockPosOrRailBelow();
            BlockState iblockdata = this.level().getBlockState(blockposition);

            if (this.minecart.isFirstTick()) {
                this.minecart.setOnRails(BaseRailBlock.isRail(iblockdata));
                this.adjustToRails(blockposition, iblockdata, true);
            }

            this.minecart.applyGravity();
            this.minecart.moveAlongTrack(worldserver);
        } else {
            this.lerpClientPositionAndRotation();
            boolean flag = BaseRailBlock.isRail(this.level().getBlockState(this.minecart.getCurrentBlockPosOrRailBelow()));

            this.minecart.setOnRails(flag);
        }
    }

    private void lerpClientPositionAndRotation() {
        if (--this.lerpDelay <= 0) {
            this.setOldLerpValues();
            this.currentLerpSteps.clear();
            if (!this.lerpSteps.isEmpty()) {
                this.currentLerpSteps.addAll(this.lerpSteps);
                this.lerpSteps.clear();
                this.currentLerpStepsTotalWeight = 0.0D;

                NewMinecartBehavior.MinecartStep newminecartbehavior_a;

                for (Iterator iterator = this.currentLerpSteps.iterator(); iterator.hasNext(); this.currentLerpStepsTotalWeight += (double) newminecartbehavior_a.weight) {
                    newminecartbehavior_a = (NewMinecartBehavior.MinecartStep) iterator.next();
                }

                this.lerpDelay = this.currentLerpStepsTotalWeight == 0.0D ? 0 : 3;
            }
        }

        if (this.cartHasPosRotLerp()) {
            this.setPos(this.getCartLerpPosition(1.0F));
            this.setDeltaMovement(this.getCartLerpMovements(1.0F));
            this.setXRot(this.getCartLerpXRot(1.0F));
            this.setYRot(this.getCartLerpYRot(1.0F));
        }

    }

    public void setOldLerpValues() {
        this.oldLerp = new NewMinecartBehavior.MinecartStep(this.position(), this.getDeltaMovement(), this.getYRot(), this.getXRot(), 0.0F);
    }

    public boolean cartHasPosRotLerp() {
        return !this.currentLerpSteps.isEmpty();
    }

    public float getCartLerpXRot(float tickDelta) {
        NewMinecartBehavior.StepPartialTicks newminecartbehavior_b = this.getCurrentLerpStep(tickDelta);

        return Mth.rotLerp(newminecartbehavior_b.partialTicksInStep, newminecartbehavior_b.previousStep.xRot, newminecartbehavior_b.currentStep.xRot);
    }

    public float getCartLerpYRot(float tickDelta) {
        NewMinecartBehavior.StepPartialTicks newminecartbehavior_b = this.getCurrentLerpStep(tickDelta);

        return Mth.rotLerp(newminecartbehavior_b.partialTicksInStep, newminecartbehavior_b.previousStep.yRot, newminecartbehavior_b.currentStep.yRot);
    }

    public Vec3 getCartLerpPosition(float tickDelta) {
        NewMinecartBehavior.StepPartialTicks newminecartbehavior_b = this.getCurrentLerpStep(tickDelta);

        return Mth.lerp((double) newminecartbehavior_b.partialTicksInStep, newminecartbehavior_b.previousStep.position, newminecartbehavior_b.currentStep.position);
    }

    public Vec3 getCartLerpMovements(float tickDelta) {
        NewMinecartBehavior.StepPartialTicks newminecartbehavior_b = this.getCurrentLerpStep(tickDelta);

        return Mth.lerp((double) newminecartbehavior_b.partialTicksInStep, newminecartbehavior_b.previousStep.movement, newminecartbehavior_b.currentStep.movement);
    }

    private NewMinecartBehavior.StepPartialTicks getCurrentLerpStep(float tickDelta) {
        if (tickDelta == this.cachedPartialTick && this.lerpDelay == this.cachedLerpDelay && this.cacheIndexAlpha != null) {
            return this.cacheIndexAlpha;
        } else {
            float f1 = ((float) (3 - this.lerpDelay) + tickDelta) / 3.0F;
            float f2 = 0.0F;
            float f3 = 1.0F;
            boolean flag = false;

            int i;

            for (i = 0; i < this.currentLerpSteps.size(); ++i) {
                float f4 = ((NewMinecartBehavior.MinecartStep) this.currentLerpSteps.get(i)).weight;

                if (f4 > 0.0F) {
                    f2 += f4;
                    if ((double) f2 >= this.currentLerpStepsTotalWeight * (double) f1) {
                        float f5 = f2 - f4;

                        f3 = (float) (((double) f1 * this.currentLerpStepsTotalWeight - (double) f5) / (double) f4);
                        flag = true;
                        break;
                    }
                }
            }

            if (!flag) {
                i = this.currentLerpSteps.size() - 1;
            }

            NewMinecartBehavior.MinecartStep newminecartbehavior_a = (NewMinecartBehavior.MinecartStep) this.currentLerpSteps.get(i);
            NewMinecartBehavior.MinecartStep newminecartbehavior_a1 = i > 0 ? (NewMinecartBehavior.MinecartStep) this.currentLerpSteps.get(i - 1) : this.oldLerp;

            this.cacheIndexAlpha = new NewMinecartBehavior.StepPartialTicks(f3, newminecartbehavior_a, newminecartbehavior_a1);
            this.cachedLerpDelay = this.lerpDelay;
            this.cachedPartialTick = tickDelta;
            return this.cacheIndexAlpha;
        }
    }

    public void adjustToRails(BlockPos pos, BlockState blockState, boolean ignoreWeight) {
        if (BaseRailBlock.isRail(blockState)) {
            RailShape blockpropertytrackposition = (RailShape) blockState.getValue(((BaseRailBlock) blockState.getBlock()).getShapeProperty());
            Pair<Vec3i, Vec3i> pair = AbstractMinecart.exits(blockpropertytrackposition);
            Vec3 vec3d = (new Vec3((Vec3i) pair.getFirst())).scale(0.5D);
            Vec3 vec3d1 = (new Vec3((Vec3i) pair.getSecond())).scale(0.5D);
            Vec3 vec3d2 = vec3d.horizontal();
            Vec3 vec3d3 = vec3d1.horizontal();

            if (this.getDeltaMovement().length() > 9.999999747378752E-6D && this.getDeltaMovement().dot(vec3d2) < this.getDeltaMovement().dot(vec3d3) || this.isDecending(vec3d3, blockpropertytrackposition)) {
                Vec3 vec3d4 = vec3d2;

                vec3d2 = vec3d3;
                vec3d3 = vec3d4;
            }

            float f = 180.0F - (float) (Math.atan2(vec3d2.z, vec3d2.x) * 180.0D / Math.PI);

            f += this.minecart.isFlipped() ? 180.0F : 0.0F;
            Vec3 vec3d5 = this.position();
            boolean flag1 = vec3d.x() != vec3d1.x() && vec3d.z() != vec3d1.z();
            Vec3 vec3d6;
            Vec3 vec3d7;

            if (flag1) {
                vec3d6 = vec3d1.subtract(vec3d);
                Vec3 vec3d8 = vec3d5.subtract(pos.getBottomCenter()).subtract(vec3d);
                Vec3 vec3d9 = vec3d6.scale(vec3d6.dot(vec3d8) / vec3d6.dot(vec3d6));

                vec3d7 = pos.getBottomCenter().add(vec3d).add(vec3d9);
                f = 180.0F - (float) (Math.atan2(vec3d9.z, vec3d9.x) * 180.0D / Math.PI);
                f += this.minecart.isFlipped() ? 180.0F : 0.0F;
            } else {
                boolean flag2 = vec3d.subtract(vec3d1).x != 0.0D;
                boolean flag3 = vec3d.subtract(vec3d1).z != 0.0D;

                vec3d7 = new Vec3(flag3 ? pos.getCenter().x : vec3d5.x, (double) pos.getY(), flag2 ? pos.getCenter().z : vec3d5.z);
            }

            vec3d6 = vec3d7.subtract(vec3d5);
            this.setPos(vec3d5.add(vec3d6));
            float f1 = 0.0F;
            boolean flag4 = vec3d.y() != vec3d1.y();

            if (flag4) {
                Vec3 vec3d10 = pos.getBottomCenter().add(vec3d3);
                double d0 = vec3d10.distanceTo(this.position());

                this.setPos(this.position().add(0.0D, d0 + 0.1D, 0.0D));
                f1 = this.minecart.isFlipped() ? 45.0F : -45.0F;
            } else {
                this.setPos(this.position().add(0.0D, 0.1D, 0.0D));
            }

            this.setRotation(f, f1);
            double d1 = vec3d5.distanceTo(this.position());

            if (d1 > 0.0D) {
                this.lerpSteps.add(new NewMinecartBehavior.MinecartStep(this.position(), this.getDeltaMovement(), this.getYRot(), this.getXRot(), ignoreWeight ? 0.0F : (float) d1));
            }

        }
    }

    private void setRotation(float yaw, float pitch) {
        double d0 = (double) Math.abs(yaw - this.getYRot());

        if (d0 >= 175.0D && d0 <= 185.0D) {
            this.minecart.setFlipped(!this.minecart.isFlipped());
            yaw -= 180.0F;
            pitch *= -1.0F;
        }

        pitch = Math.clamp(pitch, -45.0F, 45.0F);
        this.setXRot(pitch % 360.0F);
        this.setYRot(yaw % 360.0F);
    }

    @Override
    public void moveAlongTrack(ServerLevel world) {
        for (NewMinecartBehavior.TrackIteration newminecartbehavior_c = new NewMinecartBehavior.TrackIteration(); newminecartbehavior_c.shouldIterate() && this.minecart.isAlive(); newminecartbehavior_c.firstIteration = false) {
            Vec3 vec3d = this.getDeltaMovement();
            BlockPos blockposition = this.minecart.getCurrentBlockPosOrRailBelow();
            BlockState iblockdata = this.level().getBlockState(blockposition);
            boolean flag = BaseRailBlock.isRail(iblockdata);

            if (this.minecart.isOnRails() != flag) {
                this.minecart.setOnRails(flag);
                this.adjustToRails(blockposition, iblockdata, false);
            }

            Vec3 vec3d1;

            if (flag) {
                this.minecart.resetFallDistance();
                this.minecart.setOldPosAndRot();
                if (iblockdata.is(Blocks.ACTIVATOR_RAIL)) {
                    this.minecart.activateMinecart(blockposition.getX(), blockposition.getY(), blockposition.getZ(), (Boolean) iblockdata.getValue(PoweredRailBlock.POWERED));
                }

                RailShape blockpropertytrackposition = (RailShape) iblockdata.getValue(((BaseRailBlock) iblockdata.getBlock()).getShapeProperty());

                vec3d1 = this.calculateTrackSpeed(world, vec3d.horizontal(), newminecartbehavior_c, blockposition, iblockdata, blockpropertytrackposition);
                if (newminecartbehavior_c.firstIteration) {
                    newminecartbehavior_c.movementLeft = vec3d1.horizontalDistance();
                } else {
                    newminecartbehavior_c.movementLeft += vec3d1.horizontalDistance() - vec3d.horizontalDistance();
                }

                this.setDeltaMovement(vec3d1);
                newminecartbehavior_c.movementLeft = this.minecart.makeStepAlongTrack(blockposition, blockpropertytrackposition, newminecartbehavior_c.movementLeft);
            } else {
                this.minecart.comeOffTrack(world);
                newminecartbehavior_c.movementLeft = 0.0D;
            }

            Vec3 vec3d2 = this.position();

            vec3d1 = vec3d2.subtract(this.minecart.oldPosition());
            double d0 = vec3d1.length();

            if (d0 > 9.999999747378752E-6D) {
                if (vec3d1.horizontalDistanceSqr() > 9.999999747378752E-6D) {
                    float f = 180.0F - (float) (Math.atan2(vec3d1.z, vec3d1.x) * 180.0D / Math.PI);
                    float f1 = this.minecart.onGround() && !this.minecart.isOnRails() ? 0.0F : 90.0F - (float) (Math.atan2(vec3d1.horizontalDistance(), vec3d1.y) * 180.0D / Math.PI);

                    f += this.minecart.isFlipped() ? 180.0F : 0.0F;
                    f1 *= this.minecart.isFlipped() ? -1.0F : 1.0F;
                    this.setRotation(f, f1);
                } else if (!this.minecart.isOnRails()) {
                    this.setXRot(this.minecart.onGround() ? 0.0F : Mth.rotLerp(0.2F, this.getXRot(), 0.0F));
                }

                this.lerpSteps.add(new NewMinecartBehavior.MinecartStep(vec3d2, this.getDeltaMovement(), this.getYRot(), this.getXRot(), (float) Math.min(d0, this.getMaxSpeed(world))));
            } else if (vec3d.horizontalDistanceSqr() > 0.0D) {
                this.lerpSteps.add(new NewMinecartBehavior.MinecartStep(vec3d2, this.getDeltaMovement(), this.getYRot(), this.getXRot(), 1.0F));
            }

            if (d0 > 9.999999747378752E-6D || newminecartbehavior_c.firstIteration) {
                this.minecart.applyEffectsFromBlocks();
                this.minecart.applyEffectsFromBlocks();
            }
        }

    }

    private Vec3 calculateTrackSpeed(ServerLevel world, Vec3 horizontalVelocity, NewMinecartBehavior.TrackIteration iteration, BlockPos pos, BlockState railState, RailShape railShape) {
        Vec3 vec3d1 = horizontalVelocity;
        Vec3 vec3d2;

        if (!iteration.hasGainedSlopeSpeed) {
            vec3d2 = this.calculateSlopeSpeed(horizontalVelocity, railShape);
            if (vec3d2.horizontalDistanceSqr() != horizontalVelocity.horizontalDistanceSqr()) {
                iteration.hasGainedSlopeSpeed = true;
                vec3d1 = vec3d2;
            }
        }

        if (iteration.firstIteration) {
            vec3d2 = this.calculatePlayerInputSpeed(vec3d1);
            if (vec3d2.horizontalDistanceSqr() != vec3d1.horizontalDistanceSqr()) {
                iteration.hasHalted = true;
                vec3d1 = vec3d2;
            }
        }

        if (!iteration.hasHalted) {
            vec3d2 = this.calculateHaltTrackSpeed(vec3d1, railState);
            if (vec3d2.horizontalDistanceSqr() != vec3d1.horizontalDistanceSqr()) {
                iteration.hasHalted = true;
                vec3d1 = vec3d2;
            }
        }

        if (iteration.firstIteration) {
            vec3d1 = this.minecart.applyNaturalSlowdown(vec3d1);
            if (vec3d1.lengthSqr() > 0.0D) {
                double d0 = Math.min(vec3d1.length(), this.minecart.getMaxSpeed(world));

                vec3d1 = vec3d1.normalize().scale(d0);
            }
        }

        if (!iteration.hasBoosted) {
            vec3d2 = this.calculateBoostTrackSpeed(vec3d1, pos, railState);
            if (vec3d2.horizontalDistanceSqr() != vec3d1.horizontalDistanceSqr()) {
                iteration.hasBoosted = true;
                vec3d1 = vec3d2;
            }
        }

        return vec3d1;
    }

    private Vec3 calculateSlopeSpeed(Vec3 horizontalVelocity, RailShape railShape) {
        double d0 = Math.max(0.0078125D, horizontalVelocity.horizontalDistance() * 0.02D);

        if (this.minecart.isInWater()) {
            d0 *= 0.2D;
        }

        Vec3 vec3d1;

        switch (railShape) {
            case ASCENDING_EAST:
                vec3d1 = horizontalVelocity.add(-d0, 0.0D, 0.0D);
                break;
            case ASCENDING_WEST:
                vec3d1 = horizontalVelocity.add(d0, 0.0D, 0.0D);
                break;
            case ASCENDING_NORTH:
                vec3d1 = horizontalVelocity.add(0.0D, 0.0D, d0);
                break;
            case ASCENDING_SOUTH:
                vec3d1 = horizontalVelocity.add(0.0D, 0.0D, -d0);
                break;
            default:
                vec3d1 = horizontalVelocity;
        }

        return vec3d1;
    }

    private Vec3 calculatePlayerInputSpeed(Vec3 horizontalVelocity) {
        Entity entity = this.minecart.getFirstPassenger();

        if (entity instanceof ServerPlayer entityplayer) {
            Vec3 vec3d1 = entityplayer.getLastClientMoveIntent();

            if (vec3d1.lengthSqr() > 0.0D) {
                Vec3 vec3d2 = vec3d1.normalize();
                double d0 = horizontalVelocity.horizontalDistanceSqr();

                if (vec3d2.lengthSqr() > 0.0D && d0 < 0.01D) {
                    return horizontalVelocity.add((new Vec3(vec3d2.x, 0.0D, vec3d2.z)).normalize().scale(0.001D));
                }
            }

            return horizontalVelocity;
        } else {
            return horizontalVelocity;
        }
    }

    private Vec3 calculateHaltTrackSpeed(Vec3 velocity, BlockState railState) {
        return railState.is(Blocks.POWERED_RAIL) && !(Boolean) railState.getValue(PoweredRailBlock.POWERED) ? (velocity.length() < 0.03D ? Vec3.ZERO : velocity.scale(0.5D)) : velocity;
    }

    private Vec3 calculateBoostTrackSpeed(Vec3 velocity, BlockPos railPos, BlockState railState) {
        if (railState.is(Blocks.POWERED_RAIL) && (Boolean) railState.getValue(PoweredRailBlock.POWERED)) {
            if (velocity.length() > 0.01D) {
                return velocity.normalize().scale(velocity.length() + 0.06D);
            } else {
                Vec3 vec3d1 = this.minecart.getRedstoneDirection(railPos);

                return vec3d1.lengthSqr() <= 0.0D ? velocity : vec3d1.scale(velocity.length() + 0.2D);
            }
        } else {
            return velocity;
        }
    }

    @Override
    public double stepAlongTrack(BlockPos blockPos, RailShape railShape, double remainingMovement) {
        if (remainingMovement < 9.999999747378752E-6D) {
            return 0.0D;
        } else {
            Vec3 vec3d = this.position();
            Pair<Vec3i, Vec3i> pair = AbstractMinecart.exits(railShape);
            Vec3i baseblockposition = (Vec3i) pair.getFirst();
            Vec3i baseblockposition1 = (Vec3i) pair.getSecond();
            Vec3 vec3d1 = this.getDeltaMovement().horizontal();

            if (vec3d1.length() < 9.999999747378752E-6D) {
                this.setDeltaMovement(Vec3.ZERO);
                return 0.0D;
            } else {
                boolean flag = baseblockposition.getY() != baseblockposition1.getY();
                Vec3 vec3d2 = (new Vec3(baseblockposition1)).scale(0.5D).horizontal();
                Vec3 vec3d3 = (new Vec3(baseblockposition)).scale(0.5D).horizontal();

                if (vec3d1.dot(vec3d3) < vec3d1.dot(vec3d2)) {
                    vec3d3 = vec3d2;
                }

                Vec3 vec3d4 = blockPos.getBottomCenter().add(vec3d3).add(0.0D, 0.1D, 0.0D).add(vec3d3.normalize().scale(9.999999747378752E-6D));

                if (flag && !this.isDecending(vec3d1, railShape)) {
                    vec3d4 = vec3d4.add(0.0D, 1.0D, 0.0D);
                }

                Vec3 vec3d5 = vec3d4.subtract(this.position()).normalize();

                vec3d1 = vec3d5.scale(vec3d1.length() / vec3d5.horizontalDistance());
                Vec3 vec3d6 = vec3d.add(vec3d1.normalize().scale(remainingMovement * (double) (flag ? Mth.SQRT_OF_TWO : 1.0F)));

                if (vec3d.distanceToSqr(vec3d4) <= vec3d.distanceToSqr(vec3d6)) {
                    remainingMovement = vec3d4.subtract(vec3d6).horizontalDistance();
                    vec3d6 = vec3d4;
                } else {
                    remainingMovement = 0.0D;
                }

                this.minecart.move(MoverType.SELF, vec3d6.subtract(vec3d));
                BlockState iblockdata = this.level().getBlockState(BlockPos.containing(vec3d6));

                if (flag) {
                    if (BaseRailBlock.isRail(iblockdata)) {
                        RailShape blockpropertytrackposition1 = (RailShape) iblockdata.getValue(((BaseRailBlock) iblockdata.getBlock()).getShapeProperty());

                        if (this.restAtVShape(railShape, blockpropertytrackposition1)) {
                            return 0.0D;
                        }
                    }

                    double d1 = vec3d4.horizontal().distanceTo(this.position().horizontal());
                    double d2 = vec3d4.y + (this.isDecending(vec3d1, railShape) ? d1 : -d1);

                    if (this.position().y < d2) {
                        this.setPos(this.position().x, d2, this.position().z);
                    }
                }

                if (this.position().distanceTo(vec3d) < 9.999999747378752E-6D && vec3d6.distanceTo(vec3d) > 9.999999747378752E-6D) {
                    this.setDeltaMovement(Vec3.ZERO);
                    return 0.0D;
                } else {
                    this.setDeltaMovement(vec3d1);
                    return remainingMovement;
                }
            }
        }
    }

    private boolean restAtVShape(RailShape currentRailShape, RailShape newRailShape) {
        if (this.getDeltaMovement().lengthSqr() < 0.005D && newRailShape.isSlope() && this.isDecending(this.getDeltaMovement(), currentRailShape) && !this.isDecending(this.getDeltaMovement(), newRailShape)) {
            this.setDeltaMovement(Vec3.ZERO);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public double getMaxSpeed(ServerLevel world) {
        // CraftBukkit start
        Double maxSpeed = this.minecart.maxSpeed;
        if (maxSpeed != null) {
            return (this.minecart.isInWater() ? maxSpeed / 2.0D : maxSpeed);
        }
        // CraftBukkit end
        return (double) world.getGameRules().getInt(GameRules.RULE_MINECART_MAX_SPEED) * (this.minecart.isInWater() ? 0.5D : 1.0D) / 20.0D;
    }

    private boolean isDecending(Vec3 velocity, RailShape railShape) {
        boolean flag;

        switch (railShape) {
            case ASCENDING_EAST:
                flag = velocity.x < 0.0D;
                break;
            case ASCENDING_WEST:
                flag = velocity.x > 0.0D;
                break;
            case ASCENDING_NORTH:
                flag = velocity.z > 0.0D;
                break;
            case ASCENDING_SOUTH:
                flag = velocity.z < 0.0D;
                break;
            default:
                flag = false;
        }

        return flag;
    }

    @Override
    public double getSlowdownFactor() {
        if (this.minecart.frictionState == net.kyori.adventure.util.TriState.FALSE) return 1; // Paper
        return this.minecart.isVehicle() || !this.minecart.slowWhenEmpty ? 0.997D : 0.975D; // CraftBukkit - add !this.slowWhenEmpty
    }

    @Override
    public boolean pushAndPickupEntities() {
        boolean flag = this.pickupEntities(this.minecart.getBoundingBox().inflate(0.2D, 0.0D, 0.2D));

        if (!this.minecart.horizontalCollision && !this.minecart.verticalCollision) {
            return false;
        } else {
            boolean flag1 = this.pushEntities(this.minecart.getBoundingBox().inflate(1.0E-7D));

            return flag && !flag1;
        }
    }

    public boolean pickupEntities(AABB box) {
        if (this.minecart.isRideable() && !this.minecart.isVehicle()) {
            List<Entity> list = this.level().getEntities((Entity) this.minecart, box, EntitySelector.pushableBy(this.minecart));

            if (!list.isEmpty()) {
                Iterator iterator = list.iterator();

                while (iterator.hasNext()) {
                    Entity entity = (Entity) iterator.next();

                    if (!(entity instanceof Player) && !(entity instanceof IronGolem) && !(entity instanceof AbstractMinecart) && !this.minecart.isVehicle() && !entity.isPassenger()) {
                        // CraftBukkit start
                        VehicleEntityCollisionEvent collisionEvent = new VehicleEntityCollisionEvent((Vehicle) this.minecart.getBukkitEntity(), entity.getBukkitEntity());
                        this.level().getCraftServer().getPluginManager().callEvent(collisionEvent);

                        if (collisionEvent.isCancelled()) {
                            continue;
                        }
                        // CraftBukkit end
                        boolean flag = entity.startRiding(this.minecart);

                        if (flag) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    public boolean pushEntities(AABB box) {
        boolean flag = false;

        if (this.minecart.isRideable()) {
            List<Entity> list = this.level().getEntities((Entity) this.minecart, box, EntitySelector.pushableBy(this.minecart));

            if (!list.isEmpty()) {
                Iterator iterator = list.iterator();

                while (iterator.hasNext()) {
                    Entity entity = (Entity) iterator.next();

                    if (entity instanceof Player || entity instanceof IronGolem || entity instanceof AbstractMinecart || this.minecart.isVehicle() || entity.isPassenger()) {
                        // CraftBukkit start
                        if (!this.minecart.isPassengerOfSameVehicle(entity)) {
                            VehicleEntityCollisionEvent collisionEvent = new VehicleEntityCollisionEvent((Vehicle) this.minecart.getBukkitEntity(), entity.getBukkitEntity());
                            this.level().getCraftServer().getPluginManager().callEvent(collisionEvent);

                            if (collisionEvent.isCancelled()) {
                                continue;
                            }
                        }
                        // CraftBukkit end
                        entity.push((Entity) this.minecart);
                        flag = true;
                    }
                }
            }
        } else {
            Iterator iterator1 = this.level().getEntities(this.minecart, box).iterator();

            while (iterator1.hasNext()) {
                Entity entity1 = (Entity) iterator1.next();

                if (!this.minecart.hasPassenger(entity1) && entity1.isPushable() && entity1 instanceof AbstractMinecart) {
                    // CraftBukkit start
                    VehicleEntityCollisionEvent collisionEvent = new VehicleEntityCollisionEvent((Vehicle) this.minecart.getBukkitEntity(), entity1.getBukkitEntity());
                    this.level().getCraftServer().getPluginManager().callEvent(collisionEvent);

                    if (collisionEvent.isCancelled()) {
                        continue;
                    }
                    // CraftBukkit end
                    entity1.push((Entity) this.minecart);
                    flag = true;
                }
            }
        }

        return flag;
    }

    public static record MinecartStep(Vec3 position, Vec3 movement, float yRot, float xRot, float weight) {

        public static final StreamCodec<ByteBuf, NewMinecartBehavior.MinecartStep> STREAM_CODEC = StreamCodec.composite(Vec3.STREAM_CODEC, NewMinecartBehavior.MinecartStep::position, Vec3.STREAM_CODEC, NewMinecartBehavior.MinecartStep::movement, ByteBufCodecs.ROTATION_BYTE, NewMinecartBehavior.MinecartStep::yRot, ByteBufCodecs.ROTATION_BYTE, NewMinecartBehavior.MinecartStep::xRot, ByteBufCodecs.FLOAT, NewMinecartBehavior.MinecartStep::weight, NewMinecartBehavior.MinecartStep::new);
        public static NewMinecartBehavior.MinecartStep ZERO = new NewMinecartBehavior.MinecartStep(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F, 0.0F);
    }

    private static record StepPartialTicks(float partialTicksInStep, NewMinecartBehavior.MinecartStep currentStep, NewMinecartBehavior.MinecartStep previousStep) {

    }

    private static class TrackIteration {

        double movementLeft = 0.0D;
        boolean firstIteration = true;
        boolean hasGainedSlopeSpeed = false;
        boolean hasHalted = false;
        boolean hasBoosted = false;

        TrackIteration() {}

        public boolean shouldIterate() {
            return this.firstIteration || this.movementLeft > 9.999999747378752E-6D;
        }
    }
}
