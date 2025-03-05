package net.minecraft.world.entity.vehicle;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.UnmodifiableIterator;
import com.mojang.datafixers.util.Pair;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.BlockUtil;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.Location;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.util.Vector;
// CraftBukkit end

public abstract class AbstractMinecart extends VehicleEntity {

    private static final Vec3 LOWERED_PASSENGER_ATTACHMENT = new Vec3(0.0D, 0.0D, 0.0D);
    private static final EntityDataAccessor<Integer> DATA_ID_DISPLAY_BLOCK = SynchedEntityData.defineId(AbstractMinecart.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ID_DISPLAY_OFFSET = SynchedEntityData.defineId(AbstractMinecart.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_ID_CUSTOM_DISPLAY = SynchedEntityData.defineId(AbstractMinecart.class, EntityDataSerializers.BOOLEAN);
    private static final ImmutableMap<Pose, ImmutableList<Integer>> POSE_DISMOUNT_HEIGHTS = ImmutableMap.of(Pose.STANDING, ImmutableList.of(0, 1, -1), Pose.CROUCHING, ImmutableList.of(0, 1, -1), Pose.SWIMMING, ImmutableList.of(0, 1));
    protected static final float WATER_SLOWDOWN_FACTOR = 0.95F;
    private boolean onRails;
    private boolean flipped;
    private final MinecartBehavior behavior;
    private static final Map<RailShape, Pair<Vec3i, Vec3i>> EXITS = (Map) Util.make(Maps.newEnumMap(RailShape.class), (enummap) -> {
        Vec3i baseblockposition = Direction.WEST.getUnitVec3i();
        Vec3i baseblockposition1 = Direction.EAST.getUnitVec3i();
        Vec3i baseblockposition2 = Direction.NORTH.getUnitVec3i();
        Vec3i baseblockposition3 = Direction.SOUTH.getUnitVec3i();
        Vec3i baseblockposition4 = baseblockposition.below();
        Vec3i baseblockposition5 = baseblockposition1.below();
        Vec3i baseblockposition6 = baseblockposition2.below();
        Vec3i baseblockposition7 = baseblockposition3.below();

        enummap.put(RailShape.NORTH_SOUTH, Pair.of(baseblockposition2, baseblockposition3));
        enummap.put(RailShape.EAST_WEST, Pair.of(baseblockposition, baseblockposition1));
        enummap.put(RailShape.ASCENDING_EAST, Pair.of(baseblockposition4, baseblockposition1));
        enummap.put(RailShape.ASCENDING_WEST, Pair.of(baseblockposition, baseblockposition5));
        enummap.put(RailShape.ASCENDING_NORTH, Pair.of(baseblockposition2, baseblockposition7));
        enummap.put(RailShape.ASCENDING_SOUTH, Pair.of(baseblockposition6, baseblockposition3));
        enummap.put(RailShape.SOUTH_EAST, Pair.of(baseblockposition3, baseblockposition1));
        enummap.put(RailShape.SOUTH_WEST, Pair.of(baseblockposition3, baseblockposition));
        enummap.put(RailShape.NORTH_WEST, Pair.of(baseblockposition2, baseblockposition));
        enummap.put(RailShape.NORTH_EAST, Pair.of(baseblockposition2, baseblockposition1));
    });

    // CraftBukkit start
    public boolean slowWhenEmpty = true;
    private double derailedX = 0.5;
    private double derailedY = 0.5;
    private double derailedZ = 0.5;
    private double flyingX = 0.95;
    private double flyingY = 0.95;
    private double flyingZ = 0.95;
    public Double maxSpeed;
    // CraftBukkit end
    public net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API

    protected AbstractMinecart(EntityType<?> type, Level world) {
        super(type, world);
        this.blocksBuilding = true;
        if (AbstractMinecart.useExperimentalMovement(world)) {
            this.behavior = new NewMinecartBehavior(this);
        } else {
            this.behavior = new OldMinecartBehavior(this);
        }

    }

    protected AbstractMinecart(EntityType<?> type, Level world, double x, double y, double z) {
        this(type, world);
        this.setInitialPos(x, y, z);
    }

    public void setInitialPos(double x, double y, double z) {
        this.setPos(x, y, z);
        this.xo = x;
        this.yo = y;
        this.zo = z;
    }

    @Nullable
    public static <T extends AbstractMinecart> T createMinecart(Level world, double x, double y, double z, EntityType<T> type, EntitySpawnReason reason, ItemStack stack, @Nullable Player player) {
        T t0 = (T) type.create(world, reason); // CraftBukkit - decompile error

        if (t0 != null) {
            t0.setInitialPos(x, y, z);
            EntityType.createDefaultStackConfig(world, stack, player).accept(t0);
            MinecartBehavior minecartbehavior = t0.getBehavior();

            if (minecartbehavior instanceof NewMinecartBehavior) {
                NewMinecartBehavior newminecartbehavior = (NewMinecartBehavior) minecartbehavior;
                BlockPos blockposition = t0.getCurrentBlockPosOrRailBelow();
                BlockState iblockdata = world.getBlockState(blockposition);

                newminecartbehavior.adjustToRails(blockposition, iblockdata, true);
            }
        }

        return t0;
    }

    public MinecartBehavior getBehavior() {
        return this.behavior;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.EVENTS;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(AbstractMinecart.DATA_ID_DISPLAY_BLOCK, Block.getId(Blocks.AIR.defaultBlockState()));
        builder.define(AbstractMinecart.DATA_ID_DISPLAY_OFFSET, 6);
        builder.define(AbstractMinecart.DATA_ID_CUSTOM_DISPLAY, false);
    }

    @Override
    public boolean canCollideWith(Entity other) {
        // Paper start - fix VehicleEntityCollisionEvent not called when colliding with player
        boolean collides = AbstractBoat.canVehicleCollide(this, other);
        if (!collides) {
            return false;
        }
        org.bukkit.event.vehicle.VehicleEntityCollisionEvent collisionEvent = new org.bukkit.event.vehicle.VehicleEntityCollisionEvent((org.bukkit.entity.Vehicle) getBukkitEntity(), other.getBukkitEntity());

        return collisionEvent.callEvent();
        // Paper end - fix VehicleEntityCollisionEvent not called when colliding with player
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) { // Paper - Climbing should not bypass cramming gamerule
        return true;
    }

    @Override
    public Vec3 getRelativePortalPosition(Direction.Axis portalAxis, BlockUtil.FoundRectangle portalRect) {
        return LivingEntity.resetForwardDirectionOfRelativePortalPosition(super.getRelativePortalPosition(portalAxis, portalRect));
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, EntityDimensions dimensions, float scaleFactor) {
        boolean flag = passenger instanceof Villager || passenger instanceof WanderingTrader;

        return flag ? AbstractMinecart.LOWERED_PASSENGER_ATTACHMENT : super.getPassengerAttachmentPoint(passenger, dimensions, scaleFactor);
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        Direction enumdirection = this.getMotionDirection();

        if (enumdirection.getAxis() == Direction.Axis.Y) {
            return super.getDismountLocationForPassenger(passenger);
        } else {
            int[][] aint = DismountHelper.offsetsForDirection(enumdirection);
            BlockPos blockposition = this.blockPosition();
            BlockPos.MutableBlockPos blockposition_mutableblockposition = new BlockPos.MutableBlockPos();
            ImmutableList<Pose> immutablelist = passenger.getDismountPoses();
            UnmodifiableIterator unmodifiableiterator = immutablelist.iterator();

            while (unmodifiableiterator.hasNext()) {
                Pose entitypose = (Pose) unmodifiableiterator.next();
                EntityDimensions entitysize = passenger.getDimensions(entitypose);
                float f = Math.min(entitysize.width(), 1.0F) / 2.0F;
                UnmodifiableIterator unmodifiableiterator1 = ((ImmutableList) AbstractMinecart.POSE_DISMOUNT_HEIGHTS.get(entitypose)).iterator();

                while (unmodifiableiterator1.hasNext()) {
                    int i = (Integer) unmodifiableiterator1.next();
                    int[][] aint1 = aint;
                    int j = aint.length;

                    for (int k = 0; k < j; ++k) {
                        int[] aint2 = aint1[k];

                        blockposition_mutableblockposition.set(blockposition.getX() + aint2[0], blockposition.getY() + i, blockposition.getZ() + aint2[1]);
                        double d0 = this.level().getBlockFloorHeight(DismountHelper.nonClimbableShape(this.level(), blockposition_mutableblockposition), () -> {
                            return DismountHelper.nonClimbableShape(this.level(), blockposition_mutableblockposition.below());
                        });

                        if (DismountHelper.isBlockFloorValid(d0)) {
                            AABB axisalignedbb = new AABB((double) (-f), 0.0D, (double) (-f), (double) f, (double) entitysize.height(), (double) f);
                            Vec3 vec3d = Vec3.upFromBottomCenterOf(blockposition_mutableblockposition, d0);

                            if (DismountHelper.canDismountTo(this.level(), passenger, axisalignedbb.move(vec3d))) {
                                passenger.setPose(entitypose);
                                return vec3d;
                            }
                        }
                    }
                }
            }

            double d1 = this.getBoundingBox().maxY;

            blockposition_mutableblockposition.set((double) blockposition.getX(), d1, (double) blockposition.getZ());
            UnmodifiableIterator unmodifiableiterator2 = immutablelist.iterator();

            while (unmodifiableiterator2.hasNext()) {
                Pose entitypose1 = (Pose) unmodifiableiterator2.next();
                double d2 = (double) passenger.getDimensions(entitypose1).height();
                int l = Mth.ceil(d1 - (double) blockposition_mutableblockposition.getY() + d2);
                double d3 = DismountHelper.findCeilingFrom(blockposition_mutableblockposition, l, (blockposition1) -> {
                    return this.level().getBlockState(blockposition1).getCollisionShape(this.level(), blockposition1);
                });

                if (d1 + d2 <= d3) {
                    passenger.setPose(entitypose1);
                    break;
                }
            }

            return super.getDismountLocationForPassenger(passenger);
        }
    }

    @Override
    protected float getBlockSpeedFactor() {
        BlockState iblockdata = this.level().getBlockState(this.blockPosition());

        return iblockdata.is(BlockTags.RAILS) ? 1.0F : super.getBlockSpeedFactor();
    }

    @Override
    public void animateHurt(float yaw) {
        this.setHurtDir(-this.getHurtDir());
        this.setHurtTime(10);
        this.setDamage(this.getDamage() + this.getDamage() * 10.0F);
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    public static Pair<Vec3i, Vec3i> exits(RailShape shape) {
        return (Pair) AbstractMinecart.EXITS.get(shape);
    }

    @Override
    public Direction getMotionDirection() {
        return this.behavior.getMotionDirection();
    }

    @Override
    protected double getDefaultGravity() {
        return this.isInWater() ? 0.005D : 0.04D;
    }

    @Override
    public void tick() {
        // CraftBukkit start
        double prevX = this.getX();
        double prevY = this.getY();
        double prevZ = this.getZ();
        float prevYaw = this.getYRot();
        float prevPitch = this.getXRot();
        // CraftBukkit end

        if (this.getHurtTime() > 0) {
            this.setHurtTime(this.getHurtTime() - 1);
        }

        if (this.getDamage() > 0.0F) {
            this.setDamage(this.getDamage() - 1.0F);
        }

        this.checkBelowWorld();
        // this.handlePortal(); // CraftBukkit - handled in postTick
        this.behavior.tick();
        // CraftBukkit start
        org.bukkit.World bworld = this.level().getWorld();
        Location from = new Location(bworld, prevX, prevY, prevZ, prevYaw, prevPitch);
        Location to = CraftLocation.toBukkit(this.position(), bworld, this.getYRot(), this.getXRot());
        Vehicle vehicle = (Vehicle) this.getBukkitEntity();

        this.level().getCraftServer().getPluginManager().callEvent(new org.bukkit.event.vehicle.VehicleUpdateEvent(vehicle));

        if (!from.equals(to)) {
            this.level().getCraftServer().getPluginManager().callEvent(new org.bukkit.event.vehicle.VehicleMoveEvent(vehicle, from, to));
        }
        // CraftBukkit end
        this.updateInWaterStateAndDoFluidPushing();
        if (this.isInLava()) {
            this.lavaHurt();
            this.fallDistance *= 0.5F;
        }

        this.firstTick = false;
    }

    public boolean isFirstTick() {
        return this.firstTick;
    }

    public BlockPos getCurrentBlockPosOrRailBelow() {
        int i = Mth.floor(this.getX());
        int j = Mth.floor(this.getY());
        int k = Mth.floor(this.getZ());

        if (AbstractMinecart.useExperimentalMovement(this.level())) {
            double d0 = this.getY() - 0.1D - 9.999999747378752E-6D;

            if (this.level().getBlockState(BlockPos.containing((double) i, d0, (double) k)).is(BlockTags.RAILS)) {
                j = Mth.floor(d0);
            }
        } else if (this.level().getBlockState(new BlockPos(i, j - 1, k)).is(BlockTags.RAILS)) {
            --j;
        }

        return new BlockPos(i, j, k);
    }

    protected double getMaxSpeed(ServerLevel world) {
        return this.behavior.getMaxSpeed(world);
    }

    public void activateMinecart(int x, int y, int z, boolean powered) {}

    @Override
    public void lerpPositionAndRotationStep(int step, double x, double y, double z, double yaw, double pitch) {
        super.lerpPositionAndRotationStep(step, x, y, z, yaw, pitch);
    }

    @Override
    public void applyGravity() {
        super.applyGravity();
    }

    @Override
    public void reapplyPosition() {
        super.reapplyPosition();
    }

    @Override
    public boolean updateInWaterStateAndDoFluidPushing() {
        return super.updateInWaterStateAndDoFluidPushing();
    }

    @Override
    public Vec3 getKnownMovement() {
        return this.behavior.getKnownMovement(super.getKnownMovement());
    }

    @Override
    public void cancelLerp() {
        this.behavior.cancelLerp();
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int interpolationSteps) {
        this.behavior.lerpTo(x, y, z, yaw, pitch, interpolationSteps);
    }

    @Override
    public double lerpTargetX() {
        return this.behavior.lerpTargetX();
    }

    @Override
    public double lerpTargetY() {
        return this.behavior.lerpTargetY();
    }

    @Override
    public double lerpTargetZ() {
        return this.behavior.lerpTargetZ();
    }

    @Override
    public float lerpTargetXRot() {
        return this.behavior.lerpTargetXRot();
    }

    @Override
    public float lerpTargetYRot() {
        return this.behavior.lerpTargetYRot();
    }

    @Override
    public void lerpMotion(double x, double y, double z) {
        this.behavior.lerpMotion(x, y, z);
    }

    protected void moveAlongTrack(ServerLevel world) {
        this.behavior.moveAlongTrack(world);
    }

    protected void comeOffTrack(ServerLevel world) {
        double d0 = this.getMaxSpeed(world);
        Vec3 vec3d = this.getDeltaMovement();

        this.setDeltaMovement(Mth.clamp(vec3d.x, -d0, d0), vec3d.y, Mth.clamp(vec3d.z, -d0, d0));
        if (this.onGround()) {
            // CraftBukkit start - replace magic numbers with our variables
            this.setDeltaMovement(new Vec3(this.getDeltaMovement().x * this.derailedX, this.getDeltaMovement().y * this.derailedY, this.getDeltaMovement().z * this.derailedZ));
            // CraftBukkit end
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        if (!this.onGround()) {
            // CraftBukkit start - replace magic numbers with our variables
            this.setDeltaMovement(new Vec3(this.getDeltaMovement().x * this.flyingX, this.getDeltaMovement().y * this.flyingY, this.getDeltaMovement().z * this.flyingZ));
            // CraftBukkit end
        }

    }

    protected double makeStepAlongTrack(BlockPos pos, RailShape shape, double remainingMovement) {
        return this.behavior.stepAlongTrack(pos, shape, remainingMovement);
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        if (AbstractMinecart.useExperimentalMovement(this.level())) {
            Vec3 vec3d1 = this.position().add(movement);

            super.move(type, movement);
            boolean flag = this.behavior.pushAndPickupEntities();

            if (flag) {
                super.move(type, vec3d1.subtract(this.position()));
            }

            if (type.equals(MoverType.PISTON)) {
                this.onRails = false;
            }
        } else {
            super.move(type, movement);
            this.applyEffectsFromBlocks();
        }

    }

    @Override
    public void applyEffectsFromBlocks() {
        if (!AbstractMinecart.useExperimentalMovement(this.level())) {
            this.applyEffectsFromBlocks(this.position(), this.position());
        } else {
            super.applyEffectsFromBlocks();
        }

    }

    @Override
    public boolean isOnRails() {
        return this.onRails;
    }

    public void setOnRails(boolean onRail) {
        this.onRails = onRail;
    }

    public boolean isFlipped() {
        return this.flipped;
    }

    public void setFlipped(boolean yawFlipped) {
        this.flipped = yawFlipped;
    }

    public Vec3 getRedstoneDirection(BlockPos railPos) {
        BlockState iblockdata = this.level().getBlockState(railPos);

        if (iblockdata.is(Blocks.POWERED_RAIL) && (Boolean) iblockdata.getValue(PoweredRailBlock.POWERED)) {
            RailShape blockpropertytrackposition = (RailShape) iblockdata.getValue(((BaseRailBlock) iblockdata.getBlock()).getShapeProperty());

            if (blockpropertytrackposition == RailShape.EAST_WEST) {
                if (this.isRedstoneConductor(railPos.west())) {
                    return new Vec3(1.0D, 0.0D, 0.0D);
                }

                if (this.isRedstoneConductor(railPos.east())) {
                    return new Vec3(-1.0D, 0.0D, 0.0D);
                }
            } else if (blockpropertytrackposition == RailShape.NORTH_SOUTH) {
                if (this.isRedstoneConductor(railPos.north())) {
                    return new Vec3(0.0D, 0.0D, 1.0D);
                }

                if (this.isRedstoneConductor(railPos.south())) {
                    return new Vec3(0.0D, 0.0D, -1.0D);
                }
            }

            return Vec3.ZERO;
        } else {
            return Vec3.ZERO;
        }
    }

    public boolean isRedstoneConductor(BlockPos pos) {
        return this.level().getBlockState(pos).isRedstoneConductor(this.level(), pos);
    }

    protected Vec3 applyNaturalSlowdown(Vec3 velocity) {
        double d0 = this.behavior.getSlowdownFactor();
        Vec3 vec3d1 = velocity.multiply(d0, 0.0D, d0);

        if (this.isInWater()) {
            vec3d1 = vec3d1.scale(0.949999988079071D);
        }

        return vec3d1;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        if (nbt.getBoolean("CustomDisplayTile")) {
            this.setDisplayBlockState(NbtUtils.readBlockState(this.level().holderLookup(Registries.BLOCK), nbt.getCompound("DisplayState")));
            this.setDisplayOffset(nbt.getInt("DisplayOffset"));
        }

        this.flipped = nbt.getBoolean("FlippedRotation");
        this.firstTick = nbt.getBoolean("HasTicked");
        // Paper start - Friction API
        if (nbt.contains("Paper.FrictionState")) {
            String fs = nbt.getString("Paper.FrictionState");
            try {
                frictionState = net.kyori.adventure.util.TriState.valueOf(fs);
            } catch (Exception ignored) {
                com.mojang.logging.LogUtils.getLogger().error("Unknown friction state " + fs + " for " + this);
            }
        }
        // Paper end - Friction API
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        if (this.hasCustomDisplay()) {
            nbt.putBoolean("CustomDisplayTile", true);
            nbt.put("DisplayState", NbtUtils.writeBlockState(this.getDisplayBlockState()));
            nbt.putInt("DisplayOffset", this.getDisplayOffset());
        }

        nbt.putBoolean("FlippedRotation", this.flipped);
        nbt.putBoolean("HasTicked", this.firstTick);

        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            nbt.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
    }

    @Override
    public void push(Entity entity) {
        if (!this.level().isClientSide) {
            if (!entity.noPhysics && !this.noPhysics) {
                if (!this.level().paperConfig().collisions.allowVehicleCollisions && this.level().paperConfig().collisions.onlyPlayersCollide && !(entity instanceof Player)) return; // Paper - Collision option for requiring a player participant
                if (!this.hasPassenger(entity)) {
                    // CraftBukkit start
                    VehicleEntityCollisionEvent collisionEvent = new VehicleEntityCollisionEvent((Vehicle) this.getBukkitEntity(), entity.getBukkitEntity());
                    this.level().getCraftServer().getPluginManager().callEvent(collisionEvent);

                    if (collisionEvent.isCancelled()) {
                        return;
                    }
                    // CraftBukkit end
                    double d0 = entity.getX() - this.getX();
                    double d1 = entity.getZ() - this.getZ();
                    double d2 = d0 * d0 + d1 * d1;

                    if (d2 >= 9.999999747378752E-5D) {
                        d2 = Math.sqrt(d2);
                        d0 /= d2;
                        d1 /= d2;
                        double d3 = 1.0D / d2;

                        if (d3 > 1.0D) {
                            d3 = 1.0D;
                        }

                        d0 *= d3;
                        d1 *= d3;
                        d0 *= 0.10000000149011612D;
                        d1 *= 0.10000000149011612D;
                        d0 *= 0.5D;
                        d1 *= 0.5D;
                        if (entity instanceof AbstractMinecart) {
                            AbstractMinecart entityminecartabstract = (AbstractMinecart) entity;

                            this.pushOtherMinecart(entityminecartabstract, d0, d1);
                        } else {
                            this.push(-d0, 0.0D, -d1);
                            entity.push(d0 / 4.0D, 0.0D, d1 / 4.0D);
                        }
                    }

                }
            }
        }
    }

    private void pushOtherMinecart(AbstractMinecart entity, double xDiff, double zDiff) {
        double d2;
        double d3;

        if (AbstractMinecart.useExperimentalMovement(this.level())) {
            d2 = this.getDeltaMovement().x;
            d3 = this.getDeltaMovement().z;
        } else {
            d2 = entity.getX() - this.getX();
            d3 = entity.getZ() - this.getZ();
        }

        Vec3 vec3d = (new Vec3(d2, 0.0D, d3)).normalize();
        Vec3 vec3d1 = (new Vec3((double) Mth.cos(this.getYRot() * 0.017453292F), 0.0D, (double) Mth.sin(this.getYRot() * 0.017453292F))).normalize();
        double d4 = Math.abs(vec3d.dot(vec3d1));

        if (d4 >= 0.800000011920929D || AbstractMinecart.useExperimentalMovement(this.level())) {
            Vec3 vec3d2 = this.getDeltaMovement();
            Vec3 vec3d3 = entity.getDeltaMovement();

            if (entity.isFurnace() && !this.isFurnace()) {
                this.setDeltaMovement(vec3d2.multiply(0.2D, 1.0D, 0.2D));
                this.push(vec3d3.x - xDiff, 0.0D, vec3d3.z - zDiff);
                entity.setDeltaMovement(vec3d3.multiply(0.95D, 1.0D, 0.95D));
            } else if (!entity.isFurnace() && this.isFurnace()) {
                entity.setDeltaMovement(vec3d3.multiply(0.2D, 1.0D, 0.2D));
                entity.push(vec3d2.x + xDiff, 0.0D, vec3d2.z + zDiff);
                this.setDeltaMovement(vec3d2.multiply(0.95D, 1.0D, 0.95D));
            } else {
                double d5 = (vec3d3.x + vec3d2.x) / 2.0D;
                double d6 = (vec3d3.z + vec3d2.z) / 2.0D;

                this.setDeltaMovement(vec3d2.multiply(0.2D, 1.0D, 0.2D));
                this.push(d5 - xDiff, 0.0D, d6 - zDiff);
                entity.setDeltaMovement(vec3d3.multiply(0.2D, 1.0D, 0.2D));
                entity.push(d5 + xDiff, 0.0D, d6 + zDiff);
            }

        }
    }

    public BlockState getDisplayBlockState() {
        return !this.hasCustomDisplay() ? this.getDefaultDisplayBlockState() : Block.stateById((Integer) this.getEntityData().get(AbstractMinecart.DATA_ID_DISPLAY_BLOCK));
    }

    public BlockState getDefaultDisplayBlockState() {
        return Blocks.AIR.defaultBlockState();
    }

    public int getDisplayOffset() {
        return !this.hasCustomDisplay() ? this.getDefaultDisplayOffset() : (Integer) this.getEntityData().get(AbstractMinecart.DATA_ID_DISPLAY_OFFSET);
    }

    public int getDefaultDisplayOffset() {
        return 6;
    }

    public void setDisplayBlockState(BlockState state) {
        this.getEntityData().set(AbstractMinecart.DATA_ID_DISPLAY_BLOCK, Block.getId(state));
        this.setCustomDisplay(true);
    }

    public void setDisplayOffset(int offset) {
        this.getEntityData().set(AbstractMinecart.DATA_ID_DISPLAY_OFFSET, offset);
        this.setCustomDisplay(true);
    }

    public boolean hasCustomDisplay() {
        return (Boolean) this.getEntityData().get(AbstractMinecart.DATA_ID_CUSTOM_DISPLAY);
    }

    public void setCustomDisplay(boolean present) {
        this.getEntityData().set(AbstractMinecart.DATA_ID_CUSTOM_DISPLAY, present);
    }

    public static boolean useExperimentalMovement(Level world) {
        return world.enabledFeatures().contains(FeatureFlags.MINECART_IMPROVEMENTS);
    }

    @Override
    public abstract ItemStack getPickResult();

    public boolean isRideable() {
        return false;
    }

    public boolean isFurnace() {
        return false;
    }

    // CraftBukkit start - Methods for getting and setting flying and derailed velocity modifiers
    public Vector getFlyingVelocityMod() {
        return new Vector(this.flyingX, this.flyingY, this.flyingZ);
    }

    public void setFlyingVelocityMod(Vector flying) {
        this.flyingX = flying.getX();
        this.flyingY = flying.getY();
        this.flyingZ = flying.getZ();
    }

    public Vector getDerailedVelocityMod() {
        return new Vector(this.derailedX, this.derailedY, this.derailedZ);
    }

    public void setDerailedVelocityMod(Vector derailed) {
        this.derailedX = derailed.getX();
        this.derailedY = derailed.getY();
        this.derailedZ = derailed.getZ();
    }
    // CraftBukkit end
    public net.minecraft.world.item.Item publicGetDropItem() { return getDropItem(); } // Paper - api to get boat and minecart material - expose public drop item
}
