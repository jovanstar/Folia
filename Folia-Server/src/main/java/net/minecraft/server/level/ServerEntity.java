package net.minecraft.server.level;

import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.MinecartBehavior;
import net.minecraft.world.entity.vehicle.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

// CraftBukkit start
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.Mth;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerVelocityEvent;
// CraftBukkit end

public class ServerEntity {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int TOLERANCE_LEVEL_ROTATION = 1;
    private static final double TOLERANCE_LEVEL_POSITION = 7.62939453125E-6D;
    public static final int FORCED_POS_UPDATE_PERIOD = 60;
    private static final int FORCED_TELEPORT_PERIOD = 400;
    private final ServerLevel level;
    private final Entity entity;
    private final int updateInterval;
    private final boolean trackDelta;
    private final Consumer<Packet<?>> broadcast;
    private final VecDeltaCodec positionCodec = new VecDeltaCodec();
    private byte lastSentYRot;
    private byte lastSentXRot;
    private byte lastSentYHeadRot;
    private Vec3 lastSentMovement;
    private int tickCount;
    private int teleportDelay;
    private List<Entity> lastPassengers = com.google.common.collect.ImmutableList.of(); // Paper - optimize passenger checks
    private boolean wasRiding;
    private boolean wasOnGround;
    @Nullable
    private List<SynchedEntityData.DataValue<?>> trackedDataValues;
    // CraftBukkit start
    private final Set<ServerPlayerConnection> trackedPlayers;

    public ServerEntity(ServerLevel worldserver, Entity entity, int i, boolean flag, Consumer<Packet<?>> consumer, Set<ServerPlayerConnection> trackedPlayers) {
        this.trackedPlayers = trackedPlayers;
        // CraftBukkit end
        this.level = worldserver;
        this.broadcast = consumer;
        this.entity = entity;
        this.updateInterval = i;
        this.trackDelta = flag;
        this.positionCodec.setBase(entity.trackingPosition());
        this.lastSentMovement = entity.getDeltaMovement();
        this.lastSentYRot = Mth.packDegrees(entity.getYRot());
        this.lastSentXRot = Mth.packDegrees(entity.getXRot());
        this.lastSentYHeadRot = Mth.packDegrees(entity.getYHeadRot());
        this.wasOnGround = entity.onGround();
        this.trackedDataValues = entity.getEntityData().getNonDefaultValues();
    }

    // Paper start - fix desync when a player is added to the tracker
    private boolean forceStateResync;
    public void onPlayerAdd() {
        this.forceStateResync = true;
    }
    // Paper end - fix desync when a player is added to the tracker

    public void sendChanges() {
        // Paper start - optimise collisions
        if (((ca.spottedleaf.moonrise.patches.chunk_system.entity.ChunkSystemEntity)this.entity).moonrise$isHardColliding()) {
            this.teleportDelay = 9999;
        }
        // Paper end - optimise collisions
        List<Entity> list = this.entity.getPassengers();

        if (!list.equals(this.lastPassengers)) {
            this.broadcastAndSend(new ClientboundSetPassengersPacket(this.entity)); // CraftBukkit
            ServerEntity.removedPassengers(list, this.lastPassengers).forEach((entity) -> {
                if (entity instanceof ServerPlayer entityplayer) {
                    entityplayer.connection.teleport(entityplayer.getX(), entityplayer.getY(), entityplayer.getZ(), entityplayer.getYRot(), entityplayer.getXRot());
                }

            });
            this.lastPassengers = list;
        }

        Entity entity = this.entity;

        if (!this.trackedPlayers.isEmpty() && entity instanceof ItemFrame entityitemframe) { // Paper - Perf: Only tick item frames if players can see it
            if (true || this.tickCount % 10 == 0) { // CraftBukkit - Moved below, should always enter this block
                ItemStack itemstack = entityitemframe.getItem();

                if (this.level.paperConfig().maps.itemFrameCursorUpdateInterval > 0 && this.tickCount % this.level.paperConfig().maps.itemFrameCursorUpdateInterval == 0 && itemstack.getItem() instanceof MapItem) { // CraftBukkit - Moved this.tickCounter % 10 logic here so item frames do not enter the other blocks // Paper - Make item frame map cursor update interval configurable
                    MapId mapid = entityitemframe.cachedMapId; // Paper - Perf: Cache map ids on item frames
                    MapItemSavedData worldmap = MapItem.getSavedData(mapid, this.level);

                    if (worldmap != null) {
                        Iterator<ServerPlayerConnection> iterator = this.trackedPlayers.iterator(); // CraftBukkit

                        while (iterator.hasNext()) {
                            ServerPlayer entityplayer = iterator.next().getPlayer(); // CraftBukkit

                            worldmap.tickCarriedBy(entityplayer, itemstack);
                            Packet<?> packet = worldmap.getUpdatePacket(mapid, entityplayer);

                            if (packet != null) {
                                entityplayer.connection.send(packet);
                            }
                        }
                    }
                }

                this.sendDirtyEntityData();
            }
        }

        if (this.forceStateResync || this.tickCount % this.updateInterval == 0 || this.entity.hasImpulse || this.entity.getEntityData().isDirty()) { // Paper - fix desync when a player is added to the tracker
            byte b0 = Mth.packDegrees(this.entity.getYRot());
            byte b1 = Mth.packDegrees(this.entity.getXRot());
            boolean flag = Math.abs(b0 - this.lastSentYRot) >= 1 || Math.abs(b1 - this.lastSentXRot) >= 1;

            if (this.entity.isPassenger()) {
                if (flag) {
                    this.broadcast.accept(new ClientboundMoveEntityPacket.Rot(this.entity.getId(), b0, b1, this.entity.onGround()));
                    this.lastSentYRot = b0;
                    this.lastSentXRot = b1;
                }

                this.positionCodec.setBase(this.entity.trackingPosition());
                this.sendDirtyEntityData();
                this.wasRiding = true;
            } else {
                label186:
                {
                    Entity entity1 = this.entity;

                    if (entity1 instanceof AbstractMinecart) {
                        AbstractMinecart entityminecartabstract = (AbstractMinecart) entity1;
                        MinecartBehavior minecartbehavior = entityminecartabstract.getBehavior();

                        if (minecartbehavior instanceof NewMinecartBehavior) {
                            NewMinecartBehavior newminecartbehavior = (NewMinecartBehavior) minecartbehavior;

                            this.handleMinecartPosRot(newminecartbehavior, b0, b1, flag);
                            break label186;
                        }
                    }

                    ++this.teleportDelay;
                    Vec3 vec3d = this.entity.trackingPosition();
                    // Paper start - reduce allocation of Vec3D here
                    Vec3 base = this.positionCodec.base;
                    double vec3d_dx = vec3d.x - base.x;
                    double vec3d_dy = vec3d.y - base.y;
                    double vec3d_dz = vec3d.z - base.z;
                    boolean flag1 = (vec3d_dx * vec3d_dx + vec3d_dy * vec3d_dy + vec3d_dz * vec3d_dz) >= 7.62939453125E-6D;
                    // Paper end - reduce allocation of Vec3D here
                    Packet<?> packet1 = null;
                    boolean flag2 = flag1 || this.tickCount % 60 == 0;
                    boolean flag3 = false;
                    boolean flag4 = false;
                    long i = this.positionCodec.encodeX(vec3d);
                    long j = this.positionCodec.encodeY(vec3d);
                    long k = this.positionCodec.encodeZ(vec3d);
                    boolean flag5 = i < -32768L || i > 32767L || j < -32768L || j > 32767L || k < -32768L || k > 32767L;

                    if (!this.forceStateResync && !flag5 && this.teleportDelay <= 400 && !this.wasRiding && this.wasOnGround == this.entity.onGround()) { // Paper - fix desync when a player is added to the tracker
                        if ((!flag2 || !flag) && !(this.entity instanceof AbstractArrow)) {
                            if (flag2) {
                                packet1 = new ClientboundMoveEntityPacket.Pos(this.entity.getId(), (short) ((int) i), (short) ((int) j), (short) ((int) k), this.entity.onGround());
                                flag3 = true;
                            } else if (flag) {
                                packet1 = new ClientboundMoveEntityPacket.Rot(this.entity.getId(), b0, b1, this.entity.onGround());
                                flag4 = true;
                            }
                        } else {
                            packet1 = new ClientboundMoveEntityPacket.PosRot(this.entity.getId(), (short) ((int) i), (short) ((int) j), (short) ((int) k), b0, b1, this.entity.onGround());
                            flag3 = true;
                            flag4 = true;
                        }
                    } else {
                        this.wasOnGround = this.entity.onGround();
                        this.teleportDelay = 0;
                        packet1 = ClientboundEntityPositionSyncPacket.of(this.entity);
                        flag3 = true;
                        flag4 = true;
                    }

                    if (this.entity.hasImpulse || this.trackDelta || this.entity instanceof LivingEntity && ((LivingEntity) this.entity).isFallFlying()) {
                        Vec3 vec3d1 = this.entity.getDeltaMovement();
                        double d0 = vec3d1.distanceToSqr(this.lastSentMovement);

                        if (d0 > 1.0E-7D || d0 > 0.0D && vec3d1.lengthSqr() == 0.0D) {
                            this.lastSentMovement = vec3d1;
                            Entity entity2 = this.entity;

                            if (entity2 instanceof AbstractHurtingProjectile) {
                                AbstractHurtingProjectile entityfireball = (AbstractHurtingProjectile) entity2;

                                this.broadcast.accept(new ClientboundBundlePacket(List.of(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement), new ClientboundProjectilePowerPacket(entityfireball.getId(), entityfireball.accelerationPower))));
                            } else {
                                this.broadcast.accept(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement));
                            }
                        }
                    }

                    if (packet1 != null) {
                        this.broadcast.accept(packet1);
                    }

                    this.sendDirtyEntityData();
                    if (flag3) {
                        this.positionCodec.setBase(vec3d);
                    }

                    if (flag4) {
                        this.lastSentYRot = b0;
                        this.lastSentXRot = b1;
                    }

                    this.wasRiding = false;
                }
            }

            byte b2 = Mth.packDegrees(this.entity.getYHeadRot());

            if (Math.abs(b2 - this.lastSentYHeadRot) >= 1) {
                this.broadcast.accept(new ClientboundRotateHeadPacket(this.entity, b2));
                this.lastSentYHeadRot = b2;
            }

            this.entity.hasImpulse = false;
            this.forceStateResync = false; // Paper - fix desync when a player is added to the tracker
        }

        ++this.tickCount;
        if (this.entity.hurtMarked) {
            // CraftBukkit start - Create PlayerVelocity event
            boolean cancelled = false;

            if (this.entity instanceof ServerPlayer) {
                Player player = (Player) this.entity.getBukkitEntity();
                org.bukkit.util.Vector velocity = player.getVelocity();

                PlayerVelocityEvent event = new PlayerVelocityEvent(player, velocity.clone());
                this.entity.level().getCraftServer().getPluginManager().callEvent(event);

                if (event.isCancelled()) {
                    cancelled = true;
                } else if (!velocity.equals(event.getVelocity())) {
                    player.setVelocity(event.getVelocity());
                }
            }

            if (cancelled) {
                return;
            }
            // CraftBukkit end
            this.entity.hurtMarked = false;
            this.broadcastAndSend(new ClientboundSetEntityMotionPacket(this.entity));
        }

    }

    private void handleMinecartPosRot(NewMinecartBehavior controller, byte yaw, byte pitch, boolean changedAngles) {
        this.sendDirtyEntityData();
        if (controller.lerpSteps.isEmpty()) {
            Vec3 vec3d = this.entity.getDeltaMovement();
            double d0 = vec3d.distanceToSqr(this.lastSentMovement);
            Vec3 vec3d1 = this.entity.trackingPosition();
            boolean flag1 = this.positionCodec.delta(vec3d1).lengthSqr() >= 7.62939453125E-6D;
            boolean flag2 = flag1 || this.tickCount % 60 == 0;

            if (flag2 || changedAngles || d0 > 1.0E-7D) {
                this.broadcast.accept(new ClientboundMoveMinecartPacket(this.entity.getId(), List.of(new NewMinecartBehavior.MinecartStep(this.entity.position(), this.entity.getDeltaMovement(), this.entity.getYRot(), this.entity.getXRot(), 1.0F))));
            }
        } else {
            this.broadcast.accept(new ClientboundMoveMinecartPacket(this.entity.getId(), List.copyOf(controller.lerpSteps)));
            controller.lerpSteps.clear();
        }

        this.lastSentYRot = yaw;
        this.lastSentXRot = pitch;
        this.positionCodec.setBase(this.entity.position());
    }

    private static Stream<Entity> removedPassengers(List<Entity> passengers, List<Entity> lastPassengers) {
        return lastPassengers.stream().filter((entity) -> {
            return !passengers.contains(entity);
        });
    }

    public void removePairing(ServerPlayer player) {
        this.entity.stopSeenByPlayer(player);
        player.connection.send(new ClientboundRemoveEntitiesPacket(new int[]{this.entity.getId()}));
    }

    public void addPairing(ServerPlayer player) {
        List<Packet<? super ClientGamePacketListener>> list = new ArrayList();

        Objects.requireNonNull(list);
        this.sendPairingData(player, list::add);
        player.connection.send(new ClientboundBundlePacket(list));
        this.entity.startSeenByPlayer(player);
    }

    public void sendPairingData(ServerPlayer player, Consumer<Packet<ClientGamePacketListener>> sender) {
        if (this.entity.isRemoved()) {
            // CraftBukkit start - Remove useless error spam, just return
            // EntityTrackerEntry.LOGGER.warn("Fetching packet for removed entity {}", this.entity);
            return;
            // CraftBukkit end
        }

        Packet<ClientGamePacketListener> packet = this.entity.getAddEntityPacket(this);

        sender.accept(packet);
        if (this.trackedDataValues != null) {
            sender.accept(new ClientboundSetEntityDataPacket(this.entity.getId(), this.trackedDataValues));
        }

        boolean flag = this.trackDelta;

        if (this.entity instanceof LivingEntity) {
            Collection<AttributeInstance> collection = ((LivingEntity) this.entity).getAttributes().getSyncableAttributes();

            // CraftBukkit start - If sending own attributes send scaled health instead of current maximum health
            if (this.entity.getId() == player.getId()) {
                ((ServerPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(collection, false);
            }
            // CraftBukkit end

            if (!collection.isEmpty()) {
                sender.accept(new ClientboundUpdateAttributesPacket(this.entity.getId(), collection));
            }

            if (((LivingEntity) this.entity).isFallFlying()) {
                flag = true;
            }
        }

        if (flag && !(this.entity instanceof LivingEntity)) {
            sender.accept(new ClientboundSetEntityMotionPacket(this.entity.getId(), this.lastSentMovement));
        }

        Entity entity = this.entity;

        if (entity instanceof LivingEntity entityliving) {
            List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayList();
            Iterator iterator = EquipmentSlot.VALUES.iterator();

            while (iterator.hasNext()) {
                EquipmentSlot enumitemslot = (EquipmentSlot) iterator.next();
                ItemStack itemstack = entityliving.getItemBySlot(enumitemslot);

                if (!itemstack.isEmpty()) {
                    list.add(Pair.of(enumitemslot, itemstack.copy()));
                }
            }

            if (!list.isEmpty()) {
                sender.accept(new ClientboundSetEquipmentPacket(this.entity.getId(), list, true)); // Paper - data sanitization
            }
            ((LivingEntity) this.entity).detectEquipmentUpdatesPublic(); // CraftBukkit - SPIGOT-3789: sync again immediately after sending
        }

        if (!this.entity.getPassengers().isEmpty()) {
            sender.accept(new ClientboundSetPassengersPacket(this.entity));
        }

        if (this.entity.isPassenger()) {
            sender.accept(new ClientboundSetPassengersPacket(this.entity.getVehicle()));
        }

        entity = this.entity;
        if (entity instanceof Leashable leashable) {
            if (leashable.isLeashed()) {
                sender.accept(new ClientboundSetEntityLinkPacket(this.entity, leashable.getLeashHolder()));
            }
        }

    }

    public Vec3 getPositionBase() {
        return this.positionCodec.getBase();
    }

    public Vec3 getLastSentMovement() {
        return this.lastSentMovement;
    }

    public float getLastSentXRot() {
        return Mth.unpackDegrees(this.lastSentXRot);
    }

    public float getLastSentYRot() {
        return Mth.unpackDegrees(this.lastSentYRot);
    }

    public float getLastSentYHeadRot() {
        return Mth.unpackDegrees(this.lastSentYHeadRot);
    }

    private void sendDirtyEntityData() {
        SynchedEntityData datawatcher = this.entity.getEntityData();
        List<SynchedEntityData.DataValue<?>> list = datawatcher.packDirty();

        if (list != null) {
            this.trackedDataValues = datawatcher.getNonDefaultValues();
            this.broadcastAndSend(new ClientboundSetEntityDataPacket(this.entity.getId(), list));
        }

        if (this.entity instanceof LivingEntity) {
            Set<AttributeInstance> set = ((LivingEntity) this.entity).getAttributes().getAttributesToSync();

            if (!set.isEmpty()) {
                // CraftBukkit start - Send scaled max health
                if (this.entity instanceof ServerPlayer) {
                    ((ServerPlayer) this.entity).getBukkitEntity().injectScaledMaxHealth(set, false);
                }
                // CraftBukkit end
                this.broadcastAndSend(new ClientboundUpdateAttributesPacket(this.entity.getId(), set));
            }

            set.clear();
        }

    }

    private void broadcastAndSend(Packet<?> packet) {
        this.broadcast.accept(packet);
        if (this.entity instanceof ServerPlayer) {
            ((ServerPlayer) this.entity).connection.send(packet);
        }

    }
}
