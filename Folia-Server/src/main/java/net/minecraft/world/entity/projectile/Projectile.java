package net.minecraft.world.entity.projectile;

import com.google.common.base.MoreObjects;
import it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.projectiles.ProjectileSource;
// CraftBukkit end

public abstract class Projectile extends Entity implements TraceableEntity {

    @Nullable
    public UUID ownerUUID;
    @Nullable
    public org.bukkit.craftbukkit.entity.CraftEntity cachedOwner; // Folia - region threading - replace with CraftEntity
    public boolean leftOwner;
    public boolean hasBeenShot;
    @Nullable
    private Entity lastDeflectedBy;

    // CraftBukkit start
    protected boolean hitCancelled = false;
    // CraftBukkit end

    Projectile(EntityType<? extends Projectile> type, Level world) {
        super(type, world);
    }

    public void setOwner(@Nullable Entity entity) {
        if (entity != null) {
            this.ownerUUID = entity.getUUID();
            this.cachedOwner = entity.getBukkitEntity(); // Folia - region threading
        }
        // Paper start - Refresh ProjectileSource for projectiles
        else {
            this.ownerUUID = null;
            this.cachedOwner = null;
            this.projectileSource = null;
        }
        // Paper end - Refresh ProjectileSource for projectiles
        this.refreshProjectileSource(false); // Paper
    }
    // Paper start - Refresh ProjectileSource for projectiles
    public void refreshProjectileSource(boolean fillCache) {
        if (fillCache) {
            this.getOwner();
        }
        if (this.cachedOwner != null && !this.cachedOwner.getHandleRaw().isRemoved() && this.projectileSource == null && this.cachedOwner instanceof ProjectileSource projSource) { // Folia - region threading
            this.projectileSource = projSource;
        }
    }
    // Paper end - Refresh ProjectileSource for projectiles

    // Folia start - region threading
    // In general, this is an entire mess. At the time of writing, there are fifty usages of getOwner.
    // Usage of this function is to avoid concurrency issues, even if it sacrifices behavior.
    @Nullable
    @Override
    public Entity getOwner() {
        Entity ret = this.getOwnerRaw();
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(ret) && (ret == null || !ret.isRemoved()) ? ret : null;
    }
    // Folia end - region threading

    @Nullable
    public Entity getOwnerRaw() { // Folia - region threading
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot update owner state asynchronously"); // Folia - region threading
        if (this.cachedOwner != null && !this.cachedOwner.isPurged()) { // Folia - region threading
            this.refreshProjectileSource(false); // Paper - Refresh ProjectileSource for projectiles
            return this.cachedOwner.getHandleRaw(); // Folia - region threading
        } else if (this.ownerUUID != null) {
            // Folia start - region threading
            Entity ret = this.findOwner(this.ownerUUID);
            if (ret != null) {
                this.cachedOwner = ret.getBukkitEntity();
            }
            // Folia end - region threading
            this.refreshProjectileSource(false); // Paper - Refresh ProjectileSource for projectiles
            return ret; // Folia - region threading
        } else {
            return null;
        }
    }

    @Nullable
    protected Entity findOwner(UUID uuid) {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            return worldserver.getEntity(uuid);
        } else {
            return null;
        }
    }

    public Entity getEffectSource() {
        return (Entity) MoreObjects.firstNonNull(this.getOwner(), this);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        if (this.ownerUUID != null) {
            nbt.putUUID("Owner", this.ownerUUID);
        }

        if (this.leftOwner) {
            nbt.putBoolean("LeftOwner", true);
        }

        nbt.putBoolean("HasBeenShot", this.hasBeenShot);
    }

    protected boolean ownedBy(Entity entity) {
        return entity.getUUID().equals(this.ownerUUID);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        if (nbt.hasUUID("Owner")) {
            this.setOwnerThroughUUID(nbt.getUUID("Owner"));
            if (this instanceof ThrownEnderpearl && this.level() != null && this.level().paperConfig().fixes.disableUnloadedChunkEnderpearlExploit && this.level().paperConfig().misc.legacyEnderPearlBehavior) { this.ownerUUID = null; } // Paper - Reset pearls when they stop being ticked; Don't store shooter name for pearls to block enderpearl travel exploit
        }

        this.leftOwner = nbt.getBoolean("LeftOwner");
        this.hasBeenShot = nbt.getBoolean("HasBeenShot");
    }

    protected void setOwnerThroughUUID(UUID uuid) {
        if (this.ownerUUID != uuid) {
            this.ownerUUID = uuid;
            // Folia start - region threading
            Entity cachedOwner = this.findOwner(this.ownerUUID);
            if (cachedOwner != null) {
                this.cachedOwner = cachedOwner.getBukkitEntity();
            }
            // Folia end - region threading
        }

    }

    @Override
    public void restoreFrom(Entity original) {
        super.restoreFrom(original);
        if (original instanceof Projectile iprojectile) {
            this.ownerUUID = iprojectile.ownerUUID;
            this.cachedOwner = iprojectile.cachedOwner;
        }

    }

    @Override
    public void tick() {
        if (!this.hasBeenShot) {
            this.gameEvent(GameEvent.PROJECTILE_SHOOT, this.getOwner());
            this.hasBeenShot = true;
        }

        if (!this.leftOwner) {
            this.leftOwner = this.checkLeftOwner();
        }

        super.tick();
    }

    private boolean checkLeftOwner() {
        Entity entity = this.getOwner();

        if (entity != null) {
            AABB axisalignedbb = this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0D);

            return entity.getRootVehicle().getSelfAndPassengers().filter(EntitySelector.CAN_BE_PICKED).noneMatch((entity1) -> {
                return axisalignedbb.intersects(entity1.getBoundingBox());
            });
        } else {
            return true;
        }
    }

    public Vec3 getMovementToShoot(double x, double y, double z, float power, float uncertainty) {
        return (new Vec3(x, y, z)).normalize().add(this.random.triangle(0.0D, 0.0172275D * (double) uncertainty), this.random.triangle(0.0D, 0.0172275D * (double) uncertainty), this.random.triangle(0.0D, 0.0172275D * (double) uncertainty)).scale((double) power);
    }

    public void shoot(double x, double y, double z, float power, float uncertainty) {
        Vec3 vec3d = this.getMovementToShoot(x, y, z, power, uncertainty);

        this.setDeltaMovement(vec3d);
        this.hasImpulse = true;
        double d3 = vec3d.horizontalDistance();

        this.setYRot((float) (Mth.atan2(vec3d.x, vec3d.z) * 57.2957763671875D));
        this.setXRot((float) (Mth.atan2(vec3d.y, d3) * 57.2957763671875D));
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
    }

    public void shootFromRotation(Entity shooter, float pitch, float yaw, float roll, float speed, float divergence) {
        float f5 = -Mth.sin(yaw * 0.017453292F) * Mth.cos(pitch * 0.017453292F);
        float f6 = -Mth.sin((pitch + roll) * 0.017453292F);
        float f7 = Mth.cos(yaw * 0.017453292F) * Mth.cos(pitch * 0.017453292F);

        this.shoot((double) f5, (double) f6, (double) f7, speed, divergence);
        Vec3 vec3d = shooter.getKnownMovement();
        // Paper start - allow disabling relative velocity
        if (!shooter.level().paperConfig().misc.disableRelativeProjectileVelocity) {
        this.setDeltaMovement(this.getDeltaMovement().add(vec3d.x, shooter.onGround() ? 0.0D : vec3d.y, vec3d.z));
        }
        // Paper end - allow disabling relative velocity
    }

    public static <T extends Projectile> T spawnProjectileFromRotation(Projectile.ProjectileFactory<T> creator, ServerLevel world, ItemStack projectileStack, LivingEntity shooter, float roll, float power, float divergence) {
    // Paper start - PlayerLaunchProjectileEvent
        return spawnProjectileFromRotationDelayed(creator, world, projectileStack, shooter, roll, power, divergence).spawn();
    }
    public static <T extends Projectile> Delayed<T> spawnProjectileFromRotationDelayed(Projectile.ProjectileFactory<T> creator, ServerLevel world, ItemStack projectileStack, LivingEntity shooter, float roll, float power, float divergence) {
        return Projectile.spawnProjectileDelayed(creator.create(world, shooter, projectileStack), world, projectileStack, (iprojectile) -> {
    // Paper end - PlayerLaunchProjectileEvent
            iprojectile.shootFromRotation(shooter, shooter.getXRot(), shooter.getYRot(), roll, power, divergence);
        });
    }

    public static <T extends Projectile> T spawnProjectileUsingShoot(Projectile.ProjectileFactory<T> creator, ServerLevel world, ItemStack projectileStack, LivingEntity shooter, double velocityX, double velocityY, double velocityZ, float power, float divergence) {
        return Projectile.spawnProjectile(creator.create(world, shooter, projectileStack), world, projectileStack, (iprojectile) -> {
            iprojectile.shoot(velocityX, velocityY, velocityZ, power, divergence);
        });
    }

    public static <T extends Projectile> T spawnProjectileUsingShoot(T projectile, ServerLevel world, ItemStack projectileStack, double velocityX, double velocityY, double velocityZ, float power, float divergence) {
    // Paper start - fixes and addition to spawn reason API
        return spawnProjectileUsingShootDelayed(projectile, world, projectileStack, velocityX, velocityY, velocityZ, power, divergence).spawn();
    }
    public static <T extends Projectile> Delayed<T> spawnProjectileUsingShootDelayed(T projectile, ServerLevel world, ItemStack projectileStack, double velocityX, double velocityY, double velocityZ, float power, float divergence) {
        return Projectile.spawnProjectileDelayed(projectile, world, projectileStack, (iprojectile) -> {
    // Paper end - fixes and addition to spawn reason API
            projectile.shoot(velocityX, velocityY, velocityZ, power, divergence);
        });
    }

    public static <T extends Projectile> T spawnProjectile(T projectile, ServerLevel world, ItemStack projectileStack) {
        return Projectile.spawnProjectile(projectile, world, projectileStack, (iprojectile) -> {
        });
    }

    // Paper start - delayed projectile spawning
    public record Delayed<T extends Projectile>(
        T projectile,
        ServerLevel world,
        ItemStack projectileStack
    ) {
        // Taken from net.minecraft.world.entity.projectile.Projectile.spawnProjectile(T, net.minecraft.server.level.ServerLevel, net.minecraft.world.item.ItemStack, java.util.function.Consumer<T>)
        public boolean attemptSpawn() {
            if (!world.addFreshEntity(projectile)) return false;
            projectile.applyOnProjectileSpawned(this.world, this.projectileStack);
            return true;
        }

        public T spawn() {
            this.attemptSpawn();
            return projectile();
        }

        public boolean attemptSpawn(final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
            if (!world.addFreshEntity(projectile, reason)) return false;
            projectile.applyOnProjectileSpawned(this.world, this.projectileStack);
            return true;
        }

        public T spawn(final org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason reason) {
            this.attemptSpawn(reason);
            return projectile();
        }
    }
    // Paper end - delayed projectile spawning

    public static <T extends Projectile> T spawnProjectile(T projectile, ServerLevel world, ItemStack projectileStack, Consumer<T> beforeSpawn) {
    // Paper start - delayed projectile spawning
        return spawnProjectileDelayed(projectile, world, projectileStack, beforeSpawn).spawn();
    }
    public static <T extends Projectile> Delayed<T> spawnProjectileDelayed(T projectile, ServerLevel world, ItemStack projectileStack, Consumer<T> beforeSpawn) {
    // Paper end - delayed projectile spawning
        beforeSpawn.accept(projectile);
        return new Delayed<>(projectile, world, projectileStack); // Paper - delayed projectile spawning
    }

    public void applyOnProjectileSpawned(ServerLevel world, ItemStack projectileStack) {
        EnchantmentHelper.onProjectileSpawned(world, projectileStack, this, (item) -> {
        });
        if (this instanceof AbstractArrow entityarrow) {
            ItemStack itemstack1 = entityarrow.getWeaponItem();

            if (itemstack1 != null && !itemstack1.isEmpty() && !projectileStack.getItem().equals(itemstack1.getItem())) {
                Objects.requireNonNull(entityarrow);
                EnchantmentHelper.onProjectileSpawned(world, itemstack1, this, entityarrow::onItemBreak);
            }
        }

    }

    // CraftBukkit start - call projectile hit event
    public ProjectileDeflection preHitTargetOrDeflectSelf(HitResult movingobjectposition) { // Paper - protected -> public
        org.bukkit.event.entity.ProjectileHitEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callProjectileHitEvent(this, movingobjectposition);
        this.hitCancelled = event != null && event.isCancelled();
        if (movingobjectposition.getType() == HitResult.Type.BLOCK || !this.hitCancelled) {
            return this.hitTargetOrDeflectSelf(movingobjectposition);
        }
        return ProjectileDeflection.NONE;
    }
    // CraftBukkit end

    protected ProjectileDeflection hitTargetOrDeflectSelf(HitResult hitResult) {
        if (hitResult.getType() == HitResult.Type.ENTITY) {
            EntityHitResult movingobjectpositionentity = (EntityHitResult) hitResult;
            Entity entity = movingobjectpositionentity.getEntity();
            ProjectileDeflection projectiledeflection = entity.deflection(this);

            if (projectiledeflection != ProjectileDeflection.NONE) {
                if (entity != this.lastDeflectedBy && this.deflect(projectiledeflection, entity, this.getOwner(), false)) {
                    this.lastDeflectedBy = entity;
                }

                return projectiledeflection;
            }
        } else if (this.shouldBounceOnWorldBorder() && hitResult instanceof BlockHitResult) {
            BlockHitResult movingobjectpositionblock = (BlockHitResult) hitResult;

            if (movingobjectpositionblock.isWorldBorderHit()) {
                ProjectileDeflection projectiledeflection1 = ProjectileDeflection.REVERSE;

                if (this.deflect(projectiledeflection1, (Entity) null, this.getOwner(), false)) {
                    this.setDeltaMovement(this.getDeltaMovement().scale(0.2D));
                    return projectiledeflection1;
                }
            }
        }

        this.onHit(hitResult);
        return ProjectileDeflection.NONE;
    }

    protected boolean shouldBounceOnWorldBorder() {
        return false;
    }

    public boolean deflect(ProjectileDeflection deflection, @Nullable Entity deflector, @Nullable Entity owner, boolean fromAttack) {
        deflection.deflect(this, deflector, this.random);
        if (!this.level().isClientSide) {
            // Paper start - Fix PickupStatus getting reset
            if (this instanceof AbstractArrow arrow) {
                arrow.setOwner(owner, false);
            } else {
                this.setOwner(owner);
            }
            // Paper end - Fix PickupStatus getting reset
            this.onDeflection(deflector, fromAttack);
        }

        return true;
    }

    protected void onDeflection(@Nullable Entity deflector, boolean fromAttack) {}

    protected void onItemBreak(Item item) {}

    protected void onHit(HitResult hitResult) {
        HitResult.Type movingobjectposition_enummovingobjecttype = hitResult.getType();

        if (movingobjectposition_enummovingobjecttype == HitResult.Type.ENTITY) {
            EntityHitResult movingobjectpositionentity = (EntityHitResult) hitResult;
            Entity entity = movingobjectpositionentity.getEntity();

            if (entity.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE) && entity instanceof Projectile) {
                Projectile iprojectile = (Projectile) entity;

                iprojectile.deflect(ProjectileDeflection.AIM_DEFLECT, this.getOwner(), this.getOwner(), true);
            }

            this.onHitEntity(movingobjectpositionentity);
            this.level().gameEvent((Holder) GameEvent.PROJECTILE_LAND, hitResult.getLocation(), GameEvent.Context.of(this, (BlockState) null));
        } else if (movingobjectposition_enummovingobjecttype == HitResult.Type.BLOCK) {
            BlockHitResult movingobjectpositionblock = (BlockHitResult) hitResult;

            this.onHitBlock(movingobjectpositionblock);
            BlockPos blockposition = movingobjectpositionblock.getBlockPos();

            this.level().gameEvent((Holder) GameEvent.PROJECTILE_LAND, blockposition, GameEvent.Context.of(this, this.level().getBlockState(blockposition)));
        }

    }

    protected void onHitEntity(EntityHitResult entityHitResult) {}

    protected void onHitBlock(BlockHitResult blockHitResult) {
        // CraftBukkit start - cancellable hit event
        if (this.hitCancelled) {
            return;
        }
        // CraftBukkit end
        BlockState iblockdata = this.level().getBlockState(blockHitResult.getBlockPos());

        iblockdata.onProjectileHit(this.level(), iblockdata, blockHitResult, this);
    }

    public boolean canHitEntity(Entity entity) {
        if (!entity.canBeHitByProjectile()) {
            return false;
        } else {
            Entity entity1 = this.getOwner();

            // Paper start - Cancel hit for vanished players
            if (entity1 instanceof net.minecraft.server.level.ServerPlayer && entity instanceof net.minecraft.server.level.ServerPlayer) {
                org.bukkit.entity.Player collided = (org.bukkit.entity.Player) entity.getBukkitEntity();
                org.bukkit.entity.Player shooter = (org.bukkit.entity.Player) entity1.getBukkitEntity();
                if (!shooter.canSee(collided)) {
                    return false;
                }
            }
            // Paper end - Cancel hit for vanished players
            return entity1 == null || this.leftOwner || !entity1.isPassengerOfSameVehicle(entity);
        }
    }

    protected void updateRotation() {
        Vec3 vec3d = this.getDeltaMovement();
        double d0 = vec3d.horizontalDistance();

        this.setXRot(Projectile.lerpRotation(this.xRotO, (float) (Mth.atan2(vec3d.y, d0) * 57.2957763671875D)));
        this.setYRot(Projectile.lerpRotation(this.yRotO, (float) (Mth.atan2(vec3d.x, vec3d.z) * 57.2957763671875D)));
    }

    protected static float lerpRotation(float prevRot, float newRot) {
        prevRot += Math.round((newRot - prevRot) / 360.0F) * 360.0F; // Paper - stop large look changes from crashing the server

        return Mth.lerp(0.2F, prevRot, newRot);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entityTrackerEntry) {
        Entity entity = this.getOwner();

        return new ClientboundAddEntityPacket(this, entityTrackerEntry, entity == null ? 0 : entity.getId());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        Vec3 vec3d = new Vec3(packet.getXa(), packet.getYa(), packet.getZa());

        this.setDeltaMovement(vec3d);
        Entity entity = this.level().getEntity(packet.getData());

        if (entity != null) {
            this.setOwner(entity);
        }

    }

    @Override
    public boolean mayInteract(ServerLevel world, BlockPos pos) {
        Entity entity = this.getOwner();

        return entity instanceof Player && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity) ? entity.mayInteract(world, pos) : entity == null || world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING); // Folia - region threading
    }

    public boolean mayBreak(ServerLevel world) {
        return this.getType().is(EntityTypeTags.IMPACT_PROJECTILES) && world.getGameRules().getBoolean(GameRules.RULE_PROJECTILESCANBREAKBLOCKS);
    }

    @Override
    public boolean isPickable() {
        return this.getType().is(EntityTypeTags.REDIRECTABLE_PROJECTILE);
    }

    @Override
    public float getPickRadius() {
        return this.isPickable() ? 1.0F : 0.0F;
    }

    public DoubleDoubleImmutablePair calculateHorizontalHurtKnockbackDirection(LivingEntity target, DamageSource source) {
        double d0 = this.getDeltaMovement().x;
        double d1 = this.getDeltaMovement().z;

        return DoubleDoubleImmutablePair.of(d0, d1);
    }

    @Override
    public int getDimensionChangingDelay() {
        return 2;
    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (!this.isInvulnerableToBase(source)) {
            this.markHurt();
        }

        return false;
    }

    @FunctionalInterface
    public interface ProjectileFactory<T extends Projectile> {

        T create(ServerLevel world, LivingEntity shooter, ItemStack stack);
    }
}
