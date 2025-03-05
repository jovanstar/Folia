package net.minecraft.world.entity.vehicle;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;

// CraftBukkit start
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
// CraftBukkit end

public abstract class VehicleEntity extends Entity {

    protected static final EntityDataAccessor<Integer> DATA_ID_HURT = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    protected static final EntityDataAccessor<Integer> DATA_ID_HURTDIR = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.INT);
    protected static final EntityDataAccessor<Float> DATA_ID_DAMAGE = SynchedEntityData.defineId(VehicleEntity.class, EntityDataSerializers.FLOAT);

    public VehicleEntity(EntityType<?> type, Level world) {
        super(type, world);
    }

    @Override
    public boolean hurtClient(DamageSource source) {
        return true;
    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (this.isRemoved()) {
            return true;
        } else if (this.isInvulnerableToBase(source)) {
            return false;
        } else {
            boolean flag;
            // CraftBukkit start
            Vehicle vehicle = (Vehicle) this.getBukkitEntity();
            org.bukkit.entity.Entity attacker = (source.getEntity() == null) ? null : source.getEntity().getBukkitEntity();

            VehicleDamageEvent event = new VehicleDamageEvent(vehicle, attacker, (double) amount);
            this.level().getCraftServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return false;
            }
            amount = (float) event.getDamage();
            // CraftBukkit end
            label32:
            {
                this.setHurtDir(-this.getHurtDir());
                this.setHurtTime(10);
                this.markHurt();
                this.setDamage(this.getDamage() + amount * 10.0F);
                this.gameEvent(GameEvent.ENTITY_DAMAGE, source.getEntity());
                Entity entity = source.getEntity();

                if (entity instanceof Player) {
                    Player entityhuman = (Player) entity;

                    if (entityhuman.getAbilities().instabuild) {
                        flag = true;
                        break label32;
                    }
                }

                flag = false;
            }

            boolean flag1 = flag;

            if ((flag1 || this.getDamage() <= 40.0F) && !this.shouldSourceDestroy(source)) {
                if (flag1) {
                    // CraftBukkit start
                    VehicleDestroyEvent destroyEvent = new VehicleDestroyEvent(vehicle, attacker);
                    this.level().getCraftServer().getPluginManager().callEvent(destroyEvent);

                    if (destroyEvent.isCancelled()) {
                        this.setDamage(40.0F); // Maximize damage so this doesn't get triggered again right away
                        return true;
                    }
                    // CraftBukkit end
                    this.discard(EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause
                }
            } else {
                // CraftBukkit start
                VehicleDestroyEvent destroyEvent = new VehicleDestroyEvent(vehicle, attacker);
                this.level().getCraftServer().getPluginManager().callEvent(destroyEvent);

                if (destroyEvent.isCancelled()) {
                    this.setDamage(40.0F); // Maximize damage so this doesn't get triggered again right away
                    return true;
                }
                // CraftBukkit end
                this.destroy(world, source);
            }

            return true;
        }
    }

    boolean shouldSourceDestroy(DamageSource source) {
        return false;
    }

    @Override
    public boolean ignoreExplosion(Explosion explosion) {
        return explosion.getIndirectSourceEntity() instanceof Mob && !explosion.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
    }

    public void destroy(ServerLevel world, Item item) {
        this.kill(world);
        if (world.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            ItemStack itemstack = new ItemStack(item);

            itemstack.set(DataComponents.CUSTOM_NAME, this.getCustomName());
            this.spawnAtLocation(world, itemstack);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(VehicleEntity.DATA_ID_HURT, 0);
        builder.define(VehicleEntity.DATA_ID_HURTDIR, 1);
        builder.define(VehicleEntity.DATA_ID_DAMAGE, 0.0F);
    }

    public void setHurtTime(int damageWobbleTicks) {
        this.entityData.set(VehicleEntity.DATA_ID_HURT, damageWobbleTicks);
    }

    public void setHurtDir(int damageWobbleSide) {
        this.entityData.set(VehicleEntity.DATA_ID_HURTDIR, damageWobbleSide);
    }

    public void setDamage(float damageWobbleStrength) {
        this.entityData.set(VehicleEntity.DATA_ID_DAMAGE, damageWobbleStrength);
    }

    public float getDamage() {
        return (Float) this.entityData.get(VehicleEntity.DATA_ID_DAMAGE);
    }

    public int getHurtTime() {
        return (Integer) this.entityData.get(VehicleEntity.DATA_ID_HURT);
    }

    public int getHurtDir() {
        return (Integer) this.entityData.get(VehicleEntity.DATA_ID_HURTDIR);
    }

    protected void destroy(ServerLevel world, DamageSource damageSource) {
        this.destroy(world, this.getDropItem());
    }

    @Override
    public int getDimensionChangingDelay() {
        return 10;
    }

    protected abstract Item getDropItem();
}
