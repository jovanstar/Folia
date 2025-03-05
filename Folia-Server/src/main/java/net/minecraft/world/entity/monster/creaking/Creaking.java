package net.minecraft.world.entity.monster.creaking;

import com.mojang.serialization.Dynamic;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CreakingHeartBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class Creaking extends Monster {

    private static final EntityDataAccessor<Boolean> CAN_MOVE = SynchedEntityData.defineId(Creaking.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> IS_ACTIVE = SynchedEntityData.defineId(Creaking.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> IS_TEARING_DOWN = SynchedEntityData.defineId(Creaking.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Optional<BlockPos>> HOME_POS = SynchedEntityData.defineId(Creaking.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final int ATTACK_ANIMATION_DURATION = 15;
    private static final int MAX_HEALTH = 1;
    private static final float ATTACK_DAMAGE = 3.0F;
    private static final float FOLLOW_RANGE = 32.0F;
    private static final float ACTIVATION_RANGE_SQ = 144.0F;
    public static final int ATTACK_INTERVAL = 40;
    private static final float MOVEMENT_SPEED_WHEN_FIGHTING = 0.4F;
    public static final float SPEED_MULTIPLIER_WHEN_IDLING = 0.3F;
    public static final int CREAKING_ORANGE = 16545810;
    public static final int CREAKING_GRAY = 6250335;
    public static final int INVULNERABILITY_ANIMATION_DURATION = 8;
    public static final int TWITCH_DEATH_DURATION = 45;
    private static final int MAX_PLAYER_STUCK_COUNTER = 4;
    private int attackAnimationRemainingTicks;
    public final AnimationState attackAnimationState = new AnimationState();
    public final AnimationState invulnerabilityAnimationState = new AnimationState();
    public final AnimationState deathAnimationState = new AnimationState();
    private int invulnerabilityAnimationRemainingTicks;
    private boolean eyesGlowing;
    private int nextFlickerTime;
    private int playerStuckCounter;

    public Creaking(EntityType<? extends Creaking> type, Level world) {
        super(type, world);
        this.lookControl = new Creaking.CreakingLookControl(this);
        this.moveControl = new Creaking.CreakingMoveControl(this);
        this.jumpControl = new Creaking.CreakingJumpControl(this);
        GroundPathNavigation navigation = (GroundPathNavigation) this.getNavigation();

        navigation.setCanFloat(true);
        this.xpReward = 0;
    }

    public void setTransient(BlockPos homePos) {
        this.setHomePos(homePos);
        this.setPathfindingMalus(PathType.DAMAGE_OTHER, 8.0F);
        this.setPathfindingMalus(PathType.POWDER_SNOW, 8.0F);
        this.setPathfindingMalus(PathType.LAVA, 8.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, 0.0F);
        this.setPathfindingMalus(PathType.DANGER_FIRE, 0.0F);
    }

    public boolean isHeartBound() {
        return this.getHomePos() != null;
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        return new Creaking.CreakingBodyRotationControl(this);
    }

    @Override
    protected Brain.Provider<Creaking> brainProvider() {
        return CreakingAi.brainProvider();
    }

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return CreakingAi.makeBrain(this.brainProvider().makeBrain(dynamic));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(Creaking.CAN_MOVE, true);
        builder.define(Creaking.IS_ACTIVE, false);
        builder.define(Creaking.IS_TEARING_DOWN, false);
        builder.define(Creaking.HOME_POS, Optional.empty());
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 1.0D).add(Attributes.MOVEMENT_SPEED, 0.4000000059604645D).add(Attributes.ATTACK_DAMAGE, 3.0D).add(Attributes.FOLLOW_RANGE, 32.0D).add(Attributes.STEP_HEIGHT, 1.0625D);
    }

    public boolean canMove() {
        return (Boolean) this.entityData.get(Creaking.CAN_MOVE);
    }

    @Override
    public boolean doHurtTarget(ServerLevel world, Entity target) {
        if (!(target instanceof LivingEntity)) {
            return false;
        } else {
            this.attackAnimationRemainingTicks = 15;
            this.level().broadcastEntityEvent(this, (byte) 4);
            return super.doHurtTarget(world, target);
        }
    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        BlockPos blockposition = this.getHomePos();

        if (blockposition != null && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (!this.isInvulnerableTo(world, source) && this.invulnerabilityAnimationRemainingTicks <= 0 && !this.isDeadOrDying()) {
                Player entityhuman = this.blameSourceForDamage(source);
                Entity entity = source.getDirectEntity();

                if (!(entity instanceof LivingEntity) && !(entity instanceof Projectile) && entityhuman == null) {
                    return false;
                } else {
                    this.invulnerabilityAnimationRemainingTicks = 8;
                    this.level().broadcastEntityEvent(this, (byte) 66);
                    BlockEntity tileentity = this.level().getBlockEntity(blockposition);

                    if (tileentity instanceof CreakingHeartBlockEntity) {
                        CreakingHeartBlockEntity creakingheartblockentity = (CreakingHeartBlockEntity) tileentity;

                        if (creakingheartblockentity.isProtector(this)) {
                            if (entityhuman != null) {
                                creakingheartblockentity.creakingHurt();
                            }

                            this.playHurtSound(source);
                        }
                    }

                    return true;
                }
            } else {
                return false;
            }
        } else {
            return super.hurtServer(world, source, amount);
        }
    }

    public Player blameSourceForDamage(DamageSource damageSource) {
        this.resolveMobResponsibleForDamage(damageSource);
        return this.resolvePlayerResponsibleForDamage(damageSource);
    }

    @Override
    public boolean isPushable() {
        return super.isPushable() && this.canMove();
    }

    @Override
    public void push(double deltaX, double deltaY, double deltaZ, @Nullable Entity pushingEntity) { // Paper - add push source entity param
        if (this.canMove()) {
            super.push(deltaX, deltaY, deltaZ, pushingEntity); // Paper - add push source entity param
        }
    }

    @Override
    public Brain<Creaking> getBrain() {
        return (Brain<Creaking>) super.getBrain(); // CraftBukkit - decompile error
    }

    @Override
    protected void customServerAiStep(ServerLevel world) {
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("creakingBrain");
        this.getBrain().tick((ServerLevel) this.level(), this);
        gameprofilerfiller.pop();
        CreakingAi.updateActivity(this);
    }

    @Override
    public void aiStep() {
        if (this.invulnerabilityAnimationRemainingTicks > 0) {
            --this.invulnerabilityAnimationRemainingTicks;
        }

        if (this.attackAnimationRemainingTicks > 0) {
            --this.attackAnimationRemainingTicks;
        }

        if (!this.level().isClientSide) {
            boolean flag = (Boolean) this.entityData.get(Creaking.CAN_MOVE);
            boolean flag1 = this.checkCanMove();

            if (flag1 != flag) {
                this.gameEvent(GameEvent.ENTITY_ACTION);
                if (flag1) {
                    this.makeSound(SoundEvents.CREAKING_UNFREEZE);
                } else {
                    this.stopInPlace();
                    this.makeSound(SoundEvents.CREAKING_FREEZE);
                }
            }

            this.entityData.set(Creaking.CAN_MOVE, flag1);
        }

        super.aiStep();
    }

    @Override
    public void tick() {
        if (!this.level().isClientSide) {
            BlockPos blockposition = this.getHomePos();

            if (blockposition != null) {
                boolean flag;
                label21:
                {
                    BlockEntity tileentity = this.level().getBlockEntity(blockposition);

                    if (tileentity instanceof CreakingHeartBlockEntity) {
                        CreakingHeartBlockEntity creakingheartblockentity = (CreakingHeartBlockEntity) tileentity;

                        if (creakingheartblockentity.isProtector(this)) {
                            flag = true;
                            break label21;
                        }
                    }

                    flag = false;
                }

                boolean flag1 = flag;

                if (!flag1) {
                    this.setHealth(0.0F);
                }
            }
        }

        super.tick();
        if (this.level().isClientSide) {
            this.setupAnimationStates();
            this.checkEyeBlink();
        }

    }

    @Override
    protected void tickDeath() {
        if (this.isHeartBound() && this.isTearingDown()) {
            ++this.deathTime;
            if (!this.level().isClientSide() && this.deathTime > 45 && !this.isRemoved()) {
                this.tearDown();
            }
        } else {
            super.tickDeath();
        }

    }

    @Override
    protected void updateWalkAnimation(float posDelta) {
        float f1 = Math.min(posDelta * 25.0F, 3.0F);

        this.walkAnimation.update(f1, 0.4F, 1.0F);
    }

    private void setupAnimationStates() {
        this.attackAnimationState.animateWhen(this.attackAnimationRemainingTicks > 0, this.tickCount);
        this.invulnerabilityAnimationState.animateWhen(this.invulnerabilityAnimationRemainingTicks > 0, this.tickCount);
        this.deathAnimationState.animateWhen(this.isTearingDown(), this.tickCount);
    }

    public void tearDown() {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            AABB axisalignedbb = this.getBoundingBox();
            Vec3 vec3d = axisalignedbb.getCenter();
            double d0 = axisalignedbb.getXsize() * 0.3D;
            double d1 = axisalignedbb.getYsize() * 0.3D;
            double d2 = axisalignedbb.getZsize() * 0.3D;

            worldserver.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, Blocks.PALE_OAK_WOOD.defaultBlockState()), vec3d.x, vec3d.y, vec3d.z, 100, d0, d1, d2, 0.0D);
            worldserver.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK_CRUMBLE, (BlockState) Blocks.CREAKING_HEART.defaultBlockState().setValue(CreakingHeartBlock.ACTIVE, true)), vec3d.x, vec3d.y, vec3d.z, 10, d0, d1, d2, 0.0D);
        }

        this.makeSound(this.getDeathSound());
        this.remove(Entity.RemovalReason.DISCARDED, null); // CraftBukkit - add Bukkit remove cause
    }

    public void creakingDeathEffects(DamageSource damageSource) {
        this.blameSourceForDamage(damageSource);
        this.die(damageSource);
        this.makeSound(SoundEvents.CREAKING_TWITCH);
    }

    @Override
    public void handleEntityEvent(byte status) {
        if (status == 66) {
            this.invulnerabilityAnimationRemainingTicks = 8;
            this.playHurtSound(this.damageSources().generic());
        } else if (status == 4) {
            this.attackAnimationRemainingTicks = 15;
            this.playAttackSound();
        } else {
            super.handleEntityEvent(status);
        }

    }

    @Override
    public boolean fireImmune() {
        return this.isHeartBound() || super.fireImmune();
    }

    @Override
    public boolean canBeNameTagged() {
        return !this.isHeartBound() && super.canBeNameTagged();
    }

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return !this.isHeartBound() && super.canAddPassenger(passenger);
    }

    @Override
    protected boolean couldAcceptPassenger() {
        return !this.isHeartBound() && super.couldAcceptPassenger();
    }

    @Override
    protected void addPassenger(Entity passenger) {
        if (this.isHeartBound()) {
            throw new IllegalStateException("Should never addPassenger without checking couldAcceptPassenger()");
        }
    }

    @Override
    public boolean canUsePortal(boolean allowVehicles) {
        return !this.isHeartBound() && super.canUsePortal(allowVehicles);
    }

    @Override
    protected PathNavigation createNavigation(Level world) {
        return new Creaking.CreakingPathNavigation(this, world);
    }

    public boolean playerIsStuckInYou() {
        List<Player> list = (List) this.brain.getMemory(MemoryModuleType.NEAREST_PLAYERS).orElse(List.of());

        if (list.isEmpty()) {
            this.playerStuckCounter = 0;
            return false;
        } else {
            AABB axisalignedbb = this.getBoundingBox();
            Iterator iterator = list.iterator();

            Player entityhuman;

            do {
                if (!iterator.hasNext()) {
                    this.playerStuckCounter = 0;
                    return false;
                }

                entityhuman = (Player) iterator.next();
            } while (!axisalignedbb.contains(entityhuman.getEyePosition()));

            ++this.playerStuckCounter;
            return this.playerStuckCounter > 4;
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        if (nbt.contains("home_pos")) {
            this.setTransient((BlockPos) NbtUtils.readBlockPos(nbt, "home_pos").orElseThrow());
        }

    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        BlockPos blockposition = this.getHomePos();

        if (blockposition != null) {
            nbt.put("home_pos", NbtUtils.writeBlockPos(blockposition));
        }

    }

    public void setHomePos(BlockPos pos) {
        this.entityData.set(Creaking.HOME_POS, Optional.of(pos));
    }

    @Nullable
    public BlockPos getHomePos() {
        return (BlockPos) ((Optional) this.entityData.get(Creaking.HOME_POS)).orElse((Object) null);
    }

    public void setTearingDown() {
        this.entityData.set(Creaking.IS_TEARING_DOWN, true);
    }

    public boolean isTearingDown() {
        return (Boolean) this.entityData.get(Creaking.IS_TEARING_DOWN);
    }

    public boolean hasGlowingEyes() {
        return this.eyesGlowing;
    }

    public void checkEyeBlink() {
        if (this.deathTime > this.nextFlickerTime) {
            this.nextFlickerTime = this.deathTime + this.getRandom().nextIntBetweenInclusive(this.eyesGlowing ? 2 : this.deathTime / 4, this.eyesGlowing ? 8 : this.deathTime / 2);
            this.eyesGlowing = !this.eyesGlowing;
        }

    }

    @Override
    public void playAttackSound() {
        this.makeSound(SoundEvents.CREAKING_ATTACK);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return this.isActive() ? null : SoundEvents.CREAKING_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.CREAKING_SWAY;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.CREAKING_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.CREAKING_STEP, 0.15F, 1.0F);
    }

    @Nullable
    @Override
    public LivingEntity getTarget() {
        return this.getTargetFromBrain();
    }

    @Override
    protected void sendDebugPackets() {
        super.sendDebugPackets();
        DebugPackets.sendEntityBrain(this);
    }

    @Override
    public void knockback(double strength, double x, double z, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause cause) { // Paper - knockback events
        if (this.canMove()) {
            super.knockback(strength, x, z, attacker, cause); // Paper - knockback events
        }
    }

    public boolean checkCanMove() {
        List<Player> list = (List) this.brain.getMemory(MemoryModuleType.NEAREST_PLAYERS).orElse(List.of());
        boolean flag = this.isActive();

        if (list.isEmpty()) {
            if (flag) {
                this.deactivate();
            }

            return true;
        } else {
            boolean flag1 = false;
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                Player entityhuman = (Player) iterator.next();

                if (this.canAttack(entityhuman) && !this.isAlliedTo((Entity) entityhuman)) {
                    flag1 = true;
                    if ((!flag || LivingEntity.PLAYER_NOT_WEARING_DISGUISE_ITEM.test(entityhuman)) && this.isLookingAtMe(entityhuman, 0.5D, false, true, new double[]{this.getEyeY(), this.getY() + 0.5D * (double) this.getScale(), (this.getEyeY() + this.getY()) / 2.0D})) {
                        if (flag) {
                            return false;
                        }

                        if (entityhuman.distanceToSqr((Entity) this) < 144.0D) {
                            this.activate(entityhuman);
                            return false;
                        }
                    }
                }
            }

            if (!flag1 && flag) {
                this.deactivate();
            }

            return true;
        }
    }

    public void activate(Player player) {
        this.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, player); // CraftBukkit - decompile error
        this.gameEvent(GameEvent.ENTITY_ACTION);
        this.makeSound(SoundEvents.CREAKING_ACTIVATE);
        this.setIsActive(true);
    }

    public void deactivate() {
        this.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        this.gameEvent(GameEvent.ENTITY_ACTION);
        this.makeSound(SoundEvents.CREAKING_DEACTIVATE);
        this.setIsActive(false);
    }

    public void setIsActive(boolean active) {
        this.entityData.set(Creaking.IS_ACTIVE, active);
    }

    public boolean isActive() {
        return (Boolean) this.entityData.get(Creaking.IS_ACTIVE);
    }

    @Override
    public float getWalkTargetValue(BlockPos pos, LevelReader world) {
        return 0.0F;
    }

    private class CreakingLookControl extends LookControl {

        public CreakingLookControl(final Creaking creaking) {
            super(creaking);
        }

        @Override
        public void tick() {
            if (Creaking.this.canMove()) {
                super.tick();
            }

        }
    }

    private class CreakingMoveControl extends MoveControl {

        public CreakingMoveControl(final Creaking creaking) {
            super(creaking);
        }

        @Override
        public void tick() {
            if (Creaking.this.canMove()) {
                super.tick();
            }

        }
    }

    private class CreakingJumpControl extends JumpControl {

        public CreakingJumpControl(final Creaking creaking) {
            super(creaking);
        }

        @Override
        public void tick() {
            if (Creaking.this.canMove()) {
                super.tick();
            } else {
                Creaking.this.setJumping(false);
            }

        }
    }

    private class CreakingBodyRotationControl extends BodyRotationControl {

        public CreakingBodyRotationControl(final Creaking creaking) {
            super(creaking);
        }

        @Override
        public void clientTick() {
            if (Creaking.this.canMove()) {
                super.clientTick();
            }

        }
    }

    private class CreakingPathNavigation extends GroundPathNavigation {

        CreakingPathNavigation(final Creaking creaking, final Level world) {
            super(creaking, world);
        }

        @Override
        public void tick() {
            if (Creaking.this.canMove()) {
                super.tick();
            }

        }

        @Override
        protected PathFinder createPathFinder(int range) {
            this.nodeEvaluator = Creaking.this.new HomeNodeEvaluator();
            this.nodeEvaluator.setCanPassDoors(true);
            return new PathFinder(this.nodeEvaluator, range);
        }
    }

    private class HomeNodeEvaluator extends WalkNodeEvaluator {

        private static final int MAX_DISTANCE_TO_HOME_SQ = 1024;

        HomeNodeEvaluator() {}

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            BlockPos blockposition = Creaking.this.getHomePos();

            if (blockposition == null) {
                return super.getPathType(context, x, y, z);
            } else {
                double d0 = blockposition.distSqr(new Vec3i(x, y, z));

                return d0 > 1024.0D && d0 >= blockposition.distSqr(context.mobPosition()) ? PathType.BLOCKED : super.getPathType(context, x, y, z);
            }
        }
    }
}
