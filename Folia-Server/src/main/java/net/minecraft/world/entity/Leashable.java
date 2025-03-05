package net.minecraft.world.entity;

import com.mojang.datafixers.util.Either;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.EntityUnleashEvent.UnleashReason;
// CraftBukkit end

public interface Leashable {

    String LEASH_TAG = "leash";
    double LEASH_TOO_FAR_DIST = 10.0D;
    double LEASH_ELASTIC_DIST = 6.0D;

    @Nullable
    Leashable.LeashData getLeashData();

    void setLeashData(@Nullable Leashable.LeashData leashData);

    default boolean isLeashed() {
        return this.getLeashData() != null && this.getLeashData().leashHolder != null;
    }

    default boolean mayBeLeashed() {
        return this.getLeashData() != null;
    }

    default boolean canHaveALeashAttachedToIt() {
        return this.canBeLeashed() && !this.isLeashed();
    }

    default boolean canBeLeashed() {
        return true;
    }

    default void setDelayedLeashHolderId(int unresolvedLeashHolderId) {
        this.setLeashData(new Leashable.LeashData(unresolvedLeashHolderId));
        Leashable.dropLeash((Entity & Leashable) this, false, false); // CraftBukkit - decompile error
    }

    default void readLeashData(CompoundTag nbt) {
        Leashable.LeashData leashable_a = Leashable.readLeashDataInternal(nbt);

        if (this.getLeashData() != null && leashable_a == null) {
            this.removeLeash();
        }

        this.setLeashData(leashable_a);
    }

    @Nullable
    private static Leashable.LeashData readLeashDataInternal(CompoundTag nbt) {
        if (nbt.contains("leash", 10)) {
            // Paper start
            final CompoundTag leashTag = nbt.getCompound("leash");
            if (!leashTag.hasUUID("UUID")) {
                return null;
            }
            return new Leashable.LeashData(Either.left(leashTag.getUUID("UUID")));
            // Paper end
        } else {
            if (nbt.contains("leash", 11)) {
                Either<UUID, BlockPos> either = (Either) NbtUtils.readBlockPos(nbt, "leash").map(Either::right).orElse(null); // CraftBukkit - decompile error

                if (either != null) {
                    return new Leashable.LeashData(either);
                }
            }

            return null;
        }
    }

    default void writeLeashData(CompoundTag nbt, @Nullable Leashable.LeashData leashData) {
        if (leashData != null) {
            Either<UUID, BlockPos> either = leashData.delayedLeashInfo;
            Entity entity = leashData.leashHolder;
            // CraftBukkit start - SPIGOT-7487: Don't save (and possible drop) leash, when the holder was removed by a plugin
            if (entity != null && entity.pluginRemoved) {
                return;
            }
            // CraftBukkit end

            if (entity instanceof LeashFenceKnotEntity) {
                LeashFenceKnotEntity entityleash = (LeashFenceKnotEntity) entity;

                either = Either.right(entityleash.getPos());
            } else if (leashData.leashHolder != null) {
                either = Either.left(leashData.leashHolder.getUUID());
            }

            if (either != null) {
                nbt.put("leash", (Tag) either.map((uuid) -> {
                    CompoundTag nbttagcompound1 = new CompoundTag();

                    nbttagcompound1.putUUID("UUID", uuid);
                    return nbttagcompound1;
                }, NbtUtils::writeBlockPos));
            }
        }
    }

    private static <E extends Entity & Leashable> void restoreLeashFromSave(E entity, Leashable.LeashData leashData) {
        if (leashData.delayedLeashInfo != null) {
            Level world = entity.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;
                Optional<UUID> optional = leashData.delayedLeashInfo.left();
                Optional<BlockPos> optional1 = leashData.delayedLeashInfo.right();

                if (optional.isPresent()) {
                    Entity entity1 = worldserver.getEntity((UUID) optional.get());

                    if (entity1 != null) {
                        Leashable.setLeashedTo(entity, entity1, true);
                        return;
                    }
                } else if (optional1.isPresent()) {
                    Leashable.setLeashedTo(entity, LeashFenceKnotEntity.getOrCreateKnot(worldserver, (BlockPos) optional1.get()), true);
                    return;
                }

                if (entity.tickCount > 100) {
                    entity.forceDrops = true; // CraftBukkit
                    entity.spawnAtLocation(worldserver, (ItemLike) Items.LEAD);
                    entity.forceDrops = false; // CraftBukkit
                    ((Leashable) entity).setLeashData((Leashable.LeashData) null);
                }
            }
        }

    }

    default void dropLeash() {
        Leashable.dropLeash((Entity & Leashable) this, true, true); // CraftBukkit - decompile error
    }

    default void removeLeash() {
        Leashable.dropLeash((Entity & Leashable) this, true, false); // CraftBukkit - decompile error
    }

    default void onLeashRemoved() {}

    private static <E extends Entity & Leashable> void dropLeash(E entity, boolean sendPacket, boolean dropItem) {
        Leashable.LeashData leashable_a = ((Leashable) entity).getLeashData();

        if (leashable_a != null && leashable_a.leashHolder != null) {
            ((Leashable) entity).setLeashData((Leashable.LeashData) null);
            ((Leashable) entity).onLeashRemoved();
            Level world = entity.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                if (dropItem) {
                    entity.forceDrops = true; // CraftBukkit
                    entity.spawnAtLocation(worldserver, (ItemLike) Items.LEAD);
                    entity.forceDrops = false; // CraftBukkit
                }

                if (sendPacket) {
                    worldserver.getChunkSource().broadcast(entity, new ClientboundSetEntityLinkPacket(entity, (Entity) null));
                }
            }
        }

    }

    static <E extends Entity & Leashable> void tickLeash(ServerLevel world, E entity) {
        Leashable.LeashData leashable_a = ((Leashable) entity).getLeashData();

        if (leashable_a != null && leashable_a.delayedLeashInfo != null) {
            Leashable.restoreLeashFromSave(entity, leashable_a);
        }

        if (leashable_a != null && leashable_a.leashHolder != null) {
            if (!entity.isAlive() || !leashable_a.leashHolder.isAlive()) {
                // Paper start - Expand EntityUnleashEvent
                final EntityUnleashEvent event = new EntityUnleashEvent(entity.getBukkitEntity(), (!entity.isAlive()) ? UnleashReason.PLAYER_UNLEASH : UnleashReason.HOLDER_GONE, world.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS) && !entity.pluginRemoved);
                event.callEvent();
                if (event.isDropLeash()) { // CraftBukkit - SPIGOT-7487: Don't drop leash, when the holder was removed by a plugin
                    // Paper end - Expand EntityUnleashEvent
                    ((Leashable) entity).dropLeash();
                } else {
                    ((Leashable) entity).removeLeash();
                }
            }

            Entity entity1 = ((Leashable) entity).getLeashHolder();

            if (entity1 != null && entity1.level() == entity.level()) {
                float f = entity.distanceTo(entity1);

                if (!((Leashable) entity).handleLeashAtDistance(entity1, f)) {
                    return;
                }

                if ((double) f > entity.level().paperConfig().misc.maxLeashDistance.or(LEASH_TOO_FAR_DIST)) { // Paper - Configurable max leash distance
                    ((Leashable) entity).leashTooFarBehaviour();
                } else if ((double) f > 6.0D) {
                    ((Leashable) entity).elasticRangeLeashBehaviour(entity1, f);
                    entity.checkSlowFallDistance();
                } else {
                    ((Leashable) entity).closeRangeLeashBehaviour(entity1);
                }
            }

        }
    }

    default boolean handleLeashAtDistance(Entity leashHolder, float distance) {
        return true;
    }

    default void leashTooFarBehaviour() {
        // CraftBukkit start
        boolean dropLeash = true; // Paper
        if (this instanceof Entity entity) {
            // Paper start - Expand EntityUnleashEvent
            final EntityUnleashEvent event = new EntityUnleashEvent(entity.getBukkitEntity(), EntityUnleashEvent.UnleashReason.DISTANCE, true);
            if (!event.callEvent()) return;
            dropLeash = event.isDropLeash();
        }
        // CraftBukkit end
        if (dropLeash) {
            this.dropLeash();
        } else {
            this.removeLeash();
        }
        // Paper end - Expand EntityUnleashEvent
    }

    default void closeRangeLeashBehaviour(Entity entity) {}

    default void elasticRangeLeashBehaviour(Entity leashHolder, float distance) {
        Leashable.legacyElasticRangeLeashBehaviour((Entity & Leashable) this, leashHolder, distance); // CraftBukkit - decompile error
    }

    private static <E extends Entity & Leashable> void legacyElasticRangeLeashBehaviour(E entity, Entity leashHolder, float distance) {
        double d0 = (leashHolder.getX() - entity.getX()) / (double) distance;
        double d1 = (leashHolder.getY() - entity.getY()) / (double) distance;
        double d2 = (leashHolder.getZ() - entity.getZ()) / (double) distance;

        entity.setDeltaMovement(entity.getDeltaMovement().add(Math.copySign(d0 * d0 * 0.4D, d0), Math.copySign(d1 * d1 * 0.4D, d1), Math.copySign(d2 * d2 * 0.4D, d2)));
    }

    default void setLeashedTo(Entity leashHolder, boolean sendPacket) {
        Leashable.setLeashedTo((Entity & Leashable) this, leashHolder, sendPacket); // CraftBukkit - decompile error
    }

    private static <E extends Entity & Leashable> void setLeashedTo(E entity, Entity leashHolder, boolean sendPacket) {
        Leashable.LeashData leashable_a = ((Leashable) entity).getLeashData();

        if (leashable_a == null) {
            leashable_a = new Leashable.LeashData(leashHolder);
            ((Leashable) entity).setLeashData(leashable_a);
        } else {
            leashable_a.setLeashHolder(leashHolder);
        }

        if (sendPacket) {
            Level world = entity.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                worldserver.getChunkSource().broadcast(entity, new ClientboundSetEntityLinkPacket(entity, leashHolder));
            }
        }

        if (entity.isPassenger()) {
            entity.stopRiding();
        }

    }

    @Nullable
    default Entity getLeashHolder() {
        return Leashable.getLeashHolder((Entity & Leashable) this); // CraftBukkit - decompile error
    }

    @Nullable
    private static <E extends Entity & Leashable> Entity getLeashHolder(E entity) {
        Leashable.LeashData leashable_a = ((Leashable) entity).getLeashData();

        if (leashable_a == null) {
            return null;
        } else {
            if (leashable_a.delayedLeashHolderId != 0 && entity.level().isClientSide) {
                Entity entity1 = entity.level().getEntity(leashable_a.delayedLeashHolderId);

                if (entity1 instanceof Entity) {
                    leashable_a.setLeashHolder(entity1);
                }
            }

            return leashable_a.leashHolder;
        }
    }

    public static final class LeashData {

        int delayedLeashHolderId;
        @Nullable
        public Entity leashHolder;
        @Nullable
        public Either<UUID, BlockPos> delayedLeashInfo;

        LeashData(Either<UUID, BlockPos> unresolvedLeashData) {
            this.delayedLeashInfo = unresolvedLeashData;
        }

        LeashData(Entity leashHolder) {
            this.leashHolder = leashHolder;
        }

        LeashData(int unresolvedLeashHolderId) {
            this.delayedLeashHolderId = unresolvedLeashHolderId;
        }

        public void setLeashHolder(Entity leashHolder) {
            this.leashHolder = leashHolder;
            this.delayedLeashInfo = null;
            this.delayedLeashHolderId = 0;
        }
    }
}
