package net.minecraft.world.entity;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Scoreboard;
import org.bukkit.event.entity.EntityRemoveEvent;
// CraftBukkit end

public enum ConversionType {

    SINGLE(true) {
        @Override
        void convert(Mob oldEntity, Mob newEntity, ConversionParams context) {
            Entity entity = oldEntity.getFirstPassenger();

            newEntity.copyPosition(oldEntity);
            newEntity.setDeltaMovement(oldEntity.getDeltaMovement());
            Entity entity1;

            if (entity != null) {
                entity.stopRiding();
                entity.boardingCooldown = 0;
                Iterator iterator = newEntity.getPassengers().iterator();

                while (iterator.hasNext()) {
                    entity1 = (Entity) iterator.next();
                    entity1.stopRiding();
                    entity1.remove(Entity.RemovalReason.DISCARDED, EntityRemoveEvent.Cause.TRANSFORMATION); // CraftBukkit - add Bukkit remove cause
                }

                entity.startRiding(newEntity);
            }

            Entity entity2 = oldEntity.getVehicle();

            if (entity2 != null) {
                oldEntity.stopRiding();
                newEntity.startRiding(entity2);
            }

            if (context.keepEquipment()) {
                Iterator iterator1 = EquipmentSlot.VALUES.iterator();

                while (iterator1.hasNext()) {
                    EquipmentSlot enumitemslot = (EquipmentSlot) iterator1.next();
                    ItemStack itemstack = oldEntity.getItemBySlot(enumitemslot);

                    if (!itemstack.isEmpty()) {
                        newEntity.setItemSlot(enumitemslot, itemstack.copyAndClear());
                        newEntity.setDropChance(enumitemslot, oldEntity.getEquipmentDropChance(enumitemslot));
                    }
                }
            }

            newEntity.fallDistance = oldEntity.fallDistance;
            newEntity.setSharedFlag(7, oldEntity.isFallFlying());
            newEntity.lastHurtByPlayerTime = oldEntity.lastHurtByPlayerTime;
            newEntity.hurtTime = oldEntity.hurtTime;
            newEntity.yBodyRot = oldEntity.yBodyRot;
            newEntity.setOnGround(oldEntity.onGround());
            Optional<BlockPos> optional = oldEntity.getSleepingPos(); // CraftBukkit - decompile error

            Objects.requireNonNull(newEntity);
            optional.ifPresent(newEntity::setSleepingPos);
            entity1 = oldEntity.getLeashHolder();
            if (entity1 != null) {
                newEntity.setLeashedTo(entity1, true);
            }

            this.convertCommon(oldEntity, newEntity, context);
        }
    },
    SPLIT_ON_DEATH(false) {
        @Override
        void convert(Mob oldEntity, Mob newEntity, ConversionParams context) {
            Entity entity = oldEntity.getFirstPassenger();

            if (entity != null) {
                entity.stopRiding();
            }

            Entity entity1 = oldEntity.getLeashHolder();

            if (entity1 != null) {
                oldEntity.dropLeash();
            }

            this.convertCommon(oldEntity, newEntity, context);
        }
    };

    private final boolean discardAfterConversion;

    ConversionType(final boolean flag) {
        this.discardAfterConversion = flag;
    }

    public boolean shouldDiscardAfterConversion() {
        return this.discardAfterConversion;
    }

    abstract void convert(Mob oldEntity, Mob newEntity, ConversionParams context);

    void convertCommon(Mob oldEntity, Mob newEntity, ConversionParams context) {
        newEntity.setAbsorptionAmount(oldEntity.getAbsorptionAmount());
        Iterator iterator = oldEntity.getActiveEffects().iterator();

        while (iterator.hasNext()) {
            MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

            newEntity.addEffect(new MobEffectInstance(mobeffect));
        }

        if (oldEntity.isBaby()) {
            newEntity.setBaby(true);
        }

        if (oldEntity instanceof AgeableMob entityageable) {
            if (newEntity instanceof AgeableMob entityageable1) {
                entityageable1.setAge(entityageable.getAge());
                entityageable1.forcedAge = entityageable.forcedAge;
                entityageable1.forcedAgeTimer = entityageable.forcedAgeTimer;
            }
        }

        Brain<?> behaviorcontroller = oldEntity.getBrain();
        Brain<?> behaviorcontroller1 = newEntity.getBrain();

        if (behaviorcontroller.checkMemory(MemoryModuleType.ANGRY_AT, MemoryStatus.REGISTERED) && behaviorcontroller.hasMemoryValue(MemoryModuleType.ANGRY_AT)) {
            behaviorcontroller1.setMemory(MemoryModuleType.ANGRY_AT, behaviorcontroller.getMemory(MemoryModuleType.ANGRY_AT));
        }

        if (context.preserveCanPickUpLoot()) {
            newEntity.setCanPickUpLoot(oldEntity.canPickUpLoot());
        }

        newEntity.setLeftHanded(oldEntity.isLeftHanded());
        newEntity.setNoAi(oldEntity.isNoAi());
        if (oldEntity.isPersistenceRequired()) {
            newEntity.setPersistenceRequired();
        }

        if (oldEntity.hasCustomName()) {
            newEntity.setCustomName(oldEntity.getCustomName());
            newEntity.setCustomNameVisible(oldEntity.isCustomNameVisible());
        }

        newEntity.setSharedFlagOnFire(oldEntity.isOnFire());
        newEntity.setInvulnerable(oldEntity.isInvulnerable());
        newEntity.setNoGravity(oldEntity.isNoGravity());
        newEntity.setPortalCooldown(oldEntity.getPortalCooldown());
        newEntity.setSilent(oldEntity.isSilent());
        Set<String> set = oldEntity.getTags(); // CraftBukkit - decompile error

        Objects.requireNonNull(newEntity);
        set.forEach(newEntity::addTag);
        if (context.team() != null) {
            Scoreboard scoreboard = newEntity.level().getScoreboard();

            scoreboard.addPlayerToTeam(newEntity.getStringUUID(), context.team());
            if (oldEntity.getTeam() != null && oldEntity.getTeam() == context.team()) {
                scoreboard.removePlayerFromTeam(oldEntity.getStringUUID(), oldEntity.getTeam());
            }
        }

        if (oldEntity instanceof Zombie entityzombie) {
            if (entityzombie.canBreakDoors() && newEntity instanceof Zombie entityzombie1) {
                entityzombie1.setCanBreakDoors(true);
            }
        }

    }
}
