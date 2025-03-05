package net.minecraft.world.entity.item;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
// CraftBukkit start
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
// CraftBukkit end
import org.bukkit.event.player.PlayerAttemptPickupItemEvent; // Paper

public class ItemEntity extends Entity implements TraceableEntity {

    private static final EntityDataAccessor<ItemStack> DATA_ITEM = SynchedEntityData.defineId(ItemEntity.class, EntityDataSerializers.ITEM_STACK);
    private static final float FLOAT_HEIGHT = 0.1F;
    public static final float EYE_HEIGHT = 0.2125F;
    private static final int LIFETIME = 6000;
    private static final int INFINITE_PICKUP_DELAY = 32767;
    private static final int INFINITE_LIFETIME = -32768;
    public int age;
    public int pickupDelay;
    public int health;
    @Nullable
    public UUID thrower;
    @Nullable
    private Entity cachedThrower;
    @Nullable
    public UUID target;
    public final float bobOffs;
    // private int lastTick = MinecraftServer.currentTick - 1; // CraftBukkit // Paper - remove anti tick skipping measures / wall time
    public boolean canMobPickup = true; // Paper - Item#canEntityPickup
    private int despawnRate = -1; // Paper - Alternative item-despawn-rate
    public net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API

    public ItemEntity(EntityType<? extends ItemEntity> type, Level world) {
        super(type, world);
        this.health = 5;
        this.bobOffs = this.random.nextFloat() * 3.1415927F * 2.0F;
        this.setYRot(this.random.nextFloat() * 360.0F);
    }

    public ItemEntity(Level world, double x, double y, double z, ItemStack stack) {
        // Paper start - Don't use level random in entity constructors (to make them thread-safe)
        this(EntityType.ITEM, world);
        this.setPos(x, y, z);
        this.setDeltaMovement(this.random.nextDouble() * 0.2D - 0.1D, 0.2D, this.random.nextDouble() * 0.2D - 0.1D);
        this.setItem(stack);
        // Paper end - Don't use level random in entity constructors
    }

    public ItemEntity(Level world, double x, double y, double z, ItemStack stack, double velocityX, double velocityY, double velocityZ) {
        this(EntityType.ITEM, world);
        this.setPos(x, y, z);
        this.setDeltaMovement(velocityX, velocityY, velocityZ);
        this.setItem(stack);
    }

    private ItemEntity(ItemEntity entity) {
        super(entity.getType(), entity.level());
        this.health = 5;
        this.setItem(entity.getItem().copy());
        this.copyPosition(entity);
        this.age = entity.age;
        this.bobOffs = entity.bobOffs;
    }

    @Override
    public boolean dampensVibrations() {
        return this.getItem().is(ItemTags.DAMPENS_VIBRATIONS);
    }

    @Nullable
    @Override
    public Entity getOwner() {
        if (this.cachedThrower != null && !this.cachedThrower.isRemoved()) {
            return this.cachedThrower;
        } else {
            if (this.thrower != null) {
                Level world = this.level();

                if (world instanceof ServerLevel) {
                    ServerLevel worldserver = (ServerLevel) world;

                    this.cachedThrower = worldserver.getEntity(this.thrower);
                    return this.cachedThrower;
                }
            }

            return null;
        }
    }

    @Override
    public void restoreFrom(Entity original) {
        super.restoreFrom(original);
        if (original instanceof ItemEntity entityitem) {
            this.cachedThrower = entityitem.cachedThrower;
        }

    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ItemEntity.DATA_ITEM, ItemStack.EMPTY);
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04D;
    }

    @Override
    public void tick() {
        if (this.getItem().isEmpty()) {
            this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        } else {
            super.tick();
            // Paper start - remove anti tick skipping measures / wall time - revert to vanilla
            if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
                --this.pickupDelay;
            }
            // Paper end - remove anti tick skipping measures / wall time - revert to vanilla

            this.xo = this.getX();
            this.yo = this.getY();
            this.zo = this.getZ();
            Vec3 vec3d = this.getDeltaMovement();

            if (this.isInWater() && this.getFluidHeight(FluidTags.WATER) > 0.10000000149011612D) {
                this.setUnderwaterMovement();
            } else if (this.isInLava() && this.getFluidHeight(FluidTags.LAVA) > 0.10000000149011612D) {
                this.setUnderLavaMovement();
            } else {
                this.applyGravity();
            }

            if (this.level().isClientSide) {
                this.noPhysics = false;
            } else {
                this.noPhysics = !this.level().noCollision(this, this.getBoundingBox().deflate(1.0E-7D));
                if (this.noPhysics) {
                    this.moveTowardsClosestSpace(this.getX(), (this.getBoundingBox().minY + this.getBoundingBox().maxY) / 2.0D, this.getZ());
                }
            }

            if (!this.onGround() || this.getDeltaMovement().horizontalDistanceSqr() > 9.999999747378752E-6D || (this.tickCount + this.getId()) % 4 == 0) { // Paper - Diff on change; ActivationRange immunity
                this.move(MoverType.SELF, this.getDeltaMovement());
                this.applyEffectsFromBlocks();
                float f = 0.98F;

                // Paper start - Friction API
                if (frictionState == net.kyori.adventure.util.TriState.FALSE) {
                    f = 1F;
                } else if (this.onGround()) {
                    // Paper end - Friction API
                    f = this.level().getBlockState(this.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction() * 0.98F;
                }

                this.setDeltaMovement(this.getDeltaMovement().multiply((double) f, 0.98D, (double) f));
                if (this.onGround()) {
                    Vec3 vec3d1 = this.getDeltaMovement();

                    if (vec3d1.y < 0.0D) {
                        this.setDeltaMovement(vec3d1.multiply(1.0D, -0.5D, 1.0D));
                    }
                }
            }

            boolean flag = Mth.floor(this.xo) != Mth.floor(this.getX()) || Mth.floor(this.yo) != Mth.floor(this.getY()) || Mth.floor(this.zo) != Mth.floor(this.getZ());
            int i = flag ? 2 : 40;

            if (this.tickCount % i == 0 && !this.level().isClientSide && this.isMergable()) {
                this.mergeWithNeighbours();
            }

            // Paper - remove anti tick skipping measures / wall time - revert to vanilla /* CraftBukkit start - moved up
            if (this.age != -32768) {
                ++this.age;
            }
            // CraftBukkit end */

            this.hasImpulse |= this.updateInWaterStateAndDoFluidPushing();
            if (!this.level().isClientSide) {
                double d0 = this.getDeltaMovement().subtract(vec3d).lengthSqr();

                if (d0 > 0.01D) {
                    this.hasImpulse = true;
                }
            }

            if (!this.level().isClientSide && this.age >= this.despawnRate) { // Spigot // Paper - Alternative item-despawn-rate
                // CraftBukkit start - fire ItemDespawnEvent
                if (CraftEventFactory.callItemDespawnEvent(this).isCancelled()) {
                    this.age = 0;
                    return;
                }
                // CraftBukkit end
                this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
            }

        }
    }

    // Spigot start - copied from above
    @Override
    public void inactiveTick() {
        // Paper start - remove anti tick skipping measures / wall time - copied from above
        if (this.pickupDelay > 0 && this.pickupDelay != 32767) {
            --this.pickupDelay;
        }
        if (this.age != -32768) {
            ++this.age;
        }
        // Paper end - remove anti tick skipping measures / wall time - copied from above

        if (!this.level().isClientSide && this.age >= this.despawnRate) { // Spigot // Paper - Alternative item-despawn-rate
            // CraftBukkit start - fire ItemDespawnEvent
            if (org.bukkit.craftbukkit.event.CraftEventFactory.callItemDespawnEvent(this).isCancelled()) {
                this.age = 0;
                return;
            }
            // CraftBukkit end
            this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
        }
    }
    // Spigot end

    @Override
    public BlockPos getBlockPosBelowThatAffectsMyMovement() {
        return this.getOnPos(0.999999F);
    }

    private void setUnderwaterMovement() {
        this.setFluidMovement(0.9900000095367432D);
    }

    private void setUnderLavaMovement() {
        this.setFluidMovement(0.949999988079071D);
    }

    private void setFluidMovement(double horizontalMultiplier) {
        Vec3 vec3d = this.getDeltaMovement();

        this.setDeltaMovement(vec3d.x * horizontalMultiplier, vec3d.y + (double) (vec3d.y < 0.05999999865889549D ? 5.0E-4F : 0.0F), vec3d.z * horizontalMultiplier);
    }

    private void mergeWithNeighbours() {
        if (this.isMergable()) {
            // Spigot start
            double radius = this.level().spigotConfig.itemMerge;
            List<ItemEntity> list = this.level().getEntitiesOfClass(ItemEntity.class, this.getBoundingBox().inflate(radius, this.level().paperConfig().entities.behavior.onlyMergeItemsHorizontally ? 0 : radius - 0.5D, radius), (entityitem) -> { // Paper - configuration to only merge items horizontally
                // Spigot end
                return entityitem != this && entityitem.isMergable();
            });
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                ItemEntity entityitem = (ItemEntity) iterator.next();

                if (entityitem.isMergable()) {
                    // Paper start - Fix items merging through walls
                    if (this.level().paperConfig().fixes.fixItemsMergingThroughWalls) {
                        if (this.level().clipDirect(this.position(), entityitem.position(),
                            net.minecraft.world.phys.shapes.CollisionContext.of(this)) == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            continue;
                        }
                    }
                    // Paper end - Fix items merging through walls
                    this.tryToMerge(entityitem);
                    if (this.isRemoved()) {
                        break;
                    }
                }
            }

        }
    }

    private boolean isMergable() {
        ItemStack itemstack = this.getItem();

        return this.isAlive() && this.pickupDelay != 32767 && this.age != -32768 && this.age < this.despawnRate && itemstack.getCount() < itemstack.getMaxStackSize(); // Paper - Alternative item-despawn-rate
    }

    private void tryToMerge(ItemEntity other) {
        ItemStack itemstack = this.getItem();
        ItemStack itemstack1 = other.getItem();

        if (Objects.equals(this.target, other.target) && ItemEntity.areMergable(itemstack, itemstack1)) {
            if (true || itemstack1.getCount() < itemstack.getCount()) { // Spigot
                ItemEntity.merge(this, itemstack, other, itemstack1);
            } else {
                ItemEntity.merge(other, itemstack1, this, itemstack);
            }

        }
    }

    public static boolean areMergable(ItemStack stack1, ItemStack stack2) {
        return stack2.getCount() + stack1.getCount() > stack2.getMaxStackSize() ? false : ItemStack.isSameItemSameComponents(stack1, stack2);
    }

    public static ItemStack merge(ItemStack stack1, ItemStack stack2, int maxCount) {
        int j = Math.min(Math.min(stack1.getMaxStackSize(), maxCount) - stack1.getCount(), stack2.getCount());
        ItemStack itemstack2 = stack1.copyWithCount(stack1.getCount() + j);

        stack2.shrink(j);
        return itemstack2;
    }

    private static void merge(ItemEntity targetEntity, ItemStack stack1, ItemStack stack2) {
        ItemStack itemstack2 = ItemEntity.merge(stack1, stack2, 64);

        targetEntity.setItem(itemstack2);
    }

    private static void merge(ItemEntity targetEntity, ItemStack targetStack, ItemEntity sourceEntity, ItemStack sourceStack) {
        // CraftBukkit start
        if (!CraftEventFactory.callItemMergeEvent(sourceEntity, targetEntity)) {
            return;
        }
        // CraftBukkit end
        ItemEntity.merge(targetEntity, targetStack, sourceStack);
        targetEntity.pickupDelay = Math.max(targetEntity.pickupDelay, sourceEntity.pickupDelay);
        targetEntity.age = Math.min(targetEntity.age, sourceEntity.age);
        if (sourceStack.isEmpty()) {
            sourceEntity.discard(EntityRemoveEvent.Cause.MERGE); // CraftBukkit - add Bukkit remove cause);
        }

    }

    @Override
    public boolean fireImmune() {
        return !this.getItem().canBeHurtBy(this.damageSources().inFire()) || super.fireImmune();
    }

    @Override
    protected boolean shouldPlayLavaHurtSound() {
        return this.health <= 0 ? true : this.tickCount % 10 == 0;
    }

    @Override
    public final boolean hurtClient(DamageSource source) {
        return this.isInvulnerableToBase(source) ? false : this.getItem().canBeHurtBy(source);
    }

    @Override
    public final boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (this.isInvulnerableToBase(source)) {
            return false;
        } else if (!world.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING) && source.getEntity() instanceof Mob) {
            return false;
        } else if (!this.getItem().canBeHurtBy(source)) {
            return false;
        } else {
            // CraftBukkit start
            if (CraftEventFactory.handleNonLivingEntityDamageEvent(this, source, amount)) {
                return false;
            }
            // CraftBukkit end
            this.markHurt();
            this.health = (int) ((float) this.health - amount);
            this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
            if (this.health <= 0) {
                this.getItem().onDestroyed(this);
                this.discard(EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
            }

            return true;
        }
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return explosion.shouldAffectBlocklikeEntities() ? super.ignoreExplosion(explosion) : true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            nbt.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
        nbt.putShort("Health", (short) this.health);
        nbt.putShort("Age", (short) this.age);
        nbt.putShort("PickupDelay", (short) this.pickupDelay);
        if (this.thrower != null) {
            nbt.putUUID("Thrower", this.thrower);
        }

        if (this.target != null) {
            nbt.putUUID("Owner", this.target);
        }

        if (!this.getItem().isEmpty()) {
            nbt.put("Item", this.getItem().save(this.registryAccess()));
        }

    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        this.health = nbt.getShort("Health");
        this.age = nbt.getShort("Age");
        if (nbt.contains("PickupDelay")) {
            this.pickupDelay = nbt.getShort("PickupDelay");
        }

        if (nbt.hasUUID("Owner")) {
            this.target = nbt.getUUID("Owner");
        }

        if (nbt.hasUUID("Thrower")) {
            this.thrower = nbt.getUUID("Thrower");
            this.cachedThrower = null;
        }

        if (nbt.contains("Item", 10)) {
            CompoundTag nbttagcompound1 = nbt.getCompound("Item");

            this.setItem((ItemStack) ItemStack.parse(this.registryAccess(), nbttagcompound1).orElse(ItemStack.EMPTY));
        } else {
            this.setItem(ItemStack.EMPTY);
        }

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

        if (this.getItem().isEmpty()) {
            this.discard(null); // CraftBukkit - add Bukkit remove cause
        }

    }

    @Override
    public void playerTouch(net.minecraft.world.entity.player.Player player) {
        if (!this.level().isClientSide) {
            ItemStack itemstack = this.getItem();
            Item item = itemstack.getItem();
            int i = itemstack.getCount();

            // CraftBukkit start - fire PlayerPickupItemEvent
            int canHold = player.getInventory().canHold(itemstack);
            int remaining = i - canHold;
            boolean flyAtPlayer = false; // Paper

            // Paper start - PlayerAttemptPickupItemEvent
            if (this.pickupDelay <= 0) {
                PlayerAttemptPickupItemEvent attemptEvent = new PlayerAttemptPickupItemEvent((org.bukkit.entity.Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                this.level().getCraftServer().getPluginManager().callEvent(attemptEvent);

                flyAtPlayer = attemptEvent.getFlyAtPlayer();
                if (attemptEvent.isCancelled()) {
                    if (flyAtPlayer) {
                        player.take(this, i);
                    }

                    return;
                }
            }
            // Paper end - PlayerAttemptPickupItemEvent

            if (this.pickupDelay <= 0 && canHold > 0) {
                itemstack.setCount(canHold);
                // Call legacy event
                PlayerPickupItemEvent playerEvent = new PlayerPickupItemEvent((Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                playerEvent.setCancelled(!playerEvent.getPlayer().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(playerEvent);
                flyAtPlayer = playerEvent.getFlyAtPlayer(); // Paper
                if (playerEvent.isCancelled()) {
                    itemstack.setCount(i); // SPIGOT-5294 - restore count
                    // Paper start
                    if (flyAtPlayer) {
                        player.take(this, i);
                    }
                    // Paper end
                    return;
                }

                // Call newer event afterwards
                EntityPickupItemEvent entityEvent = new EntityPickupItemEvent((Player) player.getBukkitEntity(), (org.bukkit.entity.Item) this.getBukkitEntity(), remaining);
                entityEvent.setCancelled(!entityEvent.getEntity().getCanPickupItems());
                this.level().getCraftServer().getPluginManager().callEvent(entityEvent);
                if (entityEvent.isCancelled()) {
                    itemstack.setCount(i); // SPIGOT-5294 - restore count
                    return;
                }

                // Update the ItemStack if it was changed in the event
                ItemStack current = this.getItem();
                if (!itemstack.equals(current)) {
                    itemstack = current;
                } else {
                    itemstack.setCount(canHold + remaining); // = i
                }

                // Possibly < 0; fix here so we do not have to modify code below
                this.pickupDelay = 0;
            } else if (this.pickupDelay == 0) {
                // ensure that the code below isn't triggered if canHold says we can't pick the items up
                this.pickupDelay = -1;
            }
            // CraftBukkit end

            if (this.pickupDelay == 0 && (this.target == null || this.target.equals(player.getUUID())) && player.getInventory().add(itemstack)) {
                if (flyAtPlayer) // Paper - PlayerPickupItemEvent
                player.take(this, i);
                if (itemstack.isEmpty()) {
                    this.discard(EntityRemoveEvent.Cause.PICKUP); // CraftBukkit - add Bukkit remove cause
                    itemstack.setCount(i);
                }

                player.awardStat(Stats.ITEM_PICKED_UP.get(item), i);
                player.onItemPickup(this);
            }

        }
    }

    @Override
    public Component getName() {
        Component ichatbasecomponent = this.getCustomName();

        return ichatbasecomponent != null ? ichatbasecomponent : this.getItem().getItemName();
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    // Folia start - region threading
    @Override
    public void postChangeDimension() {
        super.postChangeDimension();
        if (!this.level().isClientSide) {
            this.mergeWithNeighbours();
        }
    }
    // Folia end - region threading

    @Nullable
    @Override
    public Entity teleport(TeleportTransition teleportTarget) {
        Entity entity = super.teleport(teleportTarget);

        if (entity != null) entity.postChangeDimension(); // Folia - region threading - move to post change

        return entity;
    }

    public ItemStack getItem() {
        return (ItemStack) this.getEntityData().get(ItemEntity.DATA_ITEM);
    }

    public void setItem(ItemStack stack) {
        this.getEntityData().set(ItemEntity.DATA_ITEM, stack);
        this.despawnRate = this.level().paperConfig().entities.spawning.altItemDespawnRate.enabled ? this.level().paperConfig().entities.spawning.altItemDespawnRate.items.getOrDefault(stack.getItem(), this.level().spigotConfig.itemDespawnRate) : this.level().spigotConfig.itemDespawnRate; // Paper - Alternative item-despawn-rate
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (ItemEntity.DATA_ITEM.equals(data)) {
            this.getItem().setEntityRepresentation(this);
        }

    }

    public void setTarget(@Nullable UUID owner) {
        this.target = owner;
    }

    public void setThrower(Entity thrower) {
        this.thrower = thrower.getUUID();
        this.cachedThrower = thrower;
    }

    public int getAge() {
        return this.age;
    }

    public void setDefaultPickUpDelay() {
        this.pickupDelay = 10;
    }

    public void setNoPickUpDelay() {
        this.pickupDelay = 0;
    }

    public void setNeverPickUp() {
        this.pickupDelay = 32767;
    }

    public void setPickUpDelay(int pickupDelay) {
        this.pickupDelay = pickupDelay;
    }

    public boolean hasPickUpDelay() {
        return this.pickupDelay > 0;
    }

    public void setUnlimitedLifetime() {
        this.age = -32768;
    }

    public void setExtendedLifetime() {
        this.age = -6000;
    }

    public void makeFakeItem() {
        this.setNeverPickUp();
        this.age = this.despawnRate - 1; // Spigot // Paper - Alternative item-despawn-rate
    }

    public static float getSpin(float f, float f1) {
        return f / 20.0F + f1;
    }

    public ItemEntity copy() {
        return new ItemEntity(this);
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.AMBIENT;
    }

    @Override
    public float getVisualRotationYInDegrees() {
        return 180.0F - ItemEntity.getSpin((float) this.getAge() + 0.5F, this.bobOffs) / 6.2831855F * 360.0F;
    }

    @Override
    public SlotAccess getSlot(int mappedIndex) {
        return mappedIndex == 0 ? SlotAccess.of(this::getItem, this::setItem) : super.getSlot(mappedIndex);
    }
}
