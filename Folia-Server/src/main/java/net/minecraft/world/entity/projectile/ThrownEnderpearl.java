package net.minecraft.world.entity.projectile;

import java.util.Iterator;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Endermite;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
// CraftBukkit end

public class ThrownEnderpearl extends ThrowableItemProjectile {

    private long ticketTimer = 0L;

    public ThrownEnderpearl(EntityType<? extends ThrownEnderpearl> type, Level world) {
        super(type, world);
    }

    public ThrownEnderpearl(Level world, LivingEntity owner, ItemStack stack) {
        super(EntityType.ENDER_PEARL, owner, world, stack);
    }

    @Override
    protected Item getDefaultItem() {
        return Items.ENDER_PEARL;
    }

    @Override
    protected void setOwnerThroughUUID(UUID uuid) {
        this.deregisterFromCurrentOwner();
        super.setOwnerThroughUUID(uuid);
        this.registerToCurrentOwner();
    }

    @Override
    public void setOwner(@Nullable Entity entity) {
        this.deregisterFromCurrentOwner();
        super.setOwner(entity);
        this.registerToCurrentOwner();
    }

    private void deregisterFromCurrentOwner() {
        Entity entity = this.getOwner();

        if (entity instanceof ServerPlayer entityplayer) {
            entityplayer.deregisterEnderPearl(this);
        }

    }

    private void registerToCurrentOwner() {
        Entity entity = this.getOwner();

        if (entity instanceof ServerPlayer entityplayer) {
            entityplayer.registerEnderPearl(this);
        }

    }

    @Nullable
    @Override
    protected Entity findOwner(UUID uuid) {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            Entity entity = super.findOwner(uuid);

            if (entity != null) {
                return entity;
            } else {
                Iterator iterator = worldserver.getServer().getAllLevels().iterator();

                while (iterator.hasNext()) {
                    ServerLevel worldserver1 = (ServerLevel) iterator.next();

                    if (worldserver1 != worldserver) {
                        entity = worldserver1.getEntity(uuid);
                        if (entity != null) {
                            return entity;
                        }
                    }
                }

                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult entityHitResult) {
        super.onHitEntity(entityHitResult);
        entityHitResult.getEntity().hurt(this.damageSources().thrown(this, this.getOwner()), 0.0F);
    }

    // Folia start - region threading
    private static void attemptTeleport(Entity source, ServerLevel checkWorld, net.minecraft.world.phys.Vec3 to) {
        final boolean onPortalCooldown = source.isOnPortalCooldown();
        // ignore retired callback, in those cases we do not want to teleport
        source.getBukkitEntity().taskScheduler.schedule(
            (Entity entity) -> {
                if (!isAllowedToTeleportOwner(entity, checkWorld)) {
                    return;
                }
                // source is now an invalid reference, do not use it, use the entity parameter
                net.minecraft.world.phys.Vec3 endermitePos = entity.position();

                // dismount from any vehicles, so we can teleport and to prevent desync
                if (entity.isPassenger()) {
                    entity.unRide();
                }

                if (onPortalCooldown) {
                    entity.setPortalCooldown();
                }

                entity.teleportAsync(
                    checkWorld, to, null, null, null,
                    PlayerTeleportEvent.TeleportCause.ENDER_PEARL,
                    // chunk could have been unloaded
                    Entity.TELEPORT_FLAG_TELEPORT_PASSENGERS | Entity.TELEPORT_FLAG_LOAD_CHUNK,
                    (Entity teleported) -> {
                        // entity is now an invalid reference, do not use it, instead use teleported
                        if (teleported instanceof ServerPlayer player) {
                            // connection teleport is already done
                            ServerLevel world = player.serverLevel();

                            // endermite spawn chance
                            if (world.random.nextFloat() < 0.05F && world.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                                Endermite entityendermite = (Endermite) EntityType.ENDERMITE.create(world, EntitySpawnReason.TRIGGERED);

                                if (entityendermite != null) {
                                    float yRot = teleported.getYRot();
                                    float xRot = teleported.getXRot();
                                    Runnable spawn = () -> {
                                        entityendermite.moveTo(endermitePos.x, endermitePos.y, endermitePos.z, yRot, xRot);
                                        world.addFreshEntity(entityendermite, CreatureSpawnEvent.SpawnReason.ENDER_PEARL);
                                    };

                                    if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(world, endermitePos, net.minecraft.world.phys.Vec3.ZERO, 1)) {
                                        spawn.run();
                                    } else {
                                        io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                                            world,
                                            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(endermitePos.x),
                                            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkCoordinate(endermitePos.z),
                                            spawn
                                        );
                                    }
                                }
                            }

                            // damage player
                            teleported.resetFallDistance();
                            player.resetCurrentImpulseContext();
                            player.hurtServer(player.serverLevel(), player.damageSources().enderPearl().customEventDamager(player), 5.0F); // CraftBukkit // Paper - fix DamageSource API
                            playSound(teleported.level(), to);
                        } else {
                            // reset fall damage so that if the entity was falling they do not instantly die
                            teleported.resetFallDistance();
                            playSound(teleported.level(), to);
                        }
                    }
                );
            },
            null, 1L
        );
    }
    // Folia end - region threading

    @Override
    protected void onHit(HitResult hitResult) {
        super.onHit(hitResult);

        for (int i = 0; i < 32; ++i) {
            this.level().addParticle(ParticleTypes.PORTAL, this.getX(), this.getY() + this.random.nextDouble() * 2.0D, this.getZ(), this.random.nextGaussian(), 0.0D, this.random.nextGaussian());
        }

        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            if (!this.isRemoved()) {
                // Folia start - region threading
                if (true) {
                    // we can't fire events, because we do not actually know where the other entity is located
                    if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this)) {
                        throw new IllegalStateException("Must be on tick thread for ticking entity: " + this);
                    }
                    Entity entity = this.getOwnerRaw();
                    if (entity != null) {
                        attemptTeleport(entity, (ServerLevel)this.level(), this.position());
                    }
                    this.discard(EntityRemoveEvent.Cause.HIT);
                    return;
                }
                // Folia end - region threading
                Entity entity = this.getOwner();

                if (entity != null && ThrownEnderpearl.isAllowedToTeleportOwner(entity, worldserver)) {
                    if (entity.isPassenger()) {
                        entity.unRide();
                    }

                    Vec3 vec3d = this.oldPosition();

                    if (entity instanceof ServerPlayer) {
                        ServerPlayer entityplayer = (ServerPlayer) entity;

                        if (entityplayer.connection.isAcceptingMessages()) {
                            // CraftBukkit start
                            ServerPlayer entityplayer1 = entityplayer.teleport(new TeleportTransition(worldserver, vec3d, Vec3.ZERO, 0.0F, 0.0F, Relative.union(Relative.ROTATION, Relative.DELTA), TeleportTransition.DO_NOTHING, PlayerTeleportEvent.TeleportCause.ENDER_PEARL));
                            if (entityplayer1 == null) {
                                this.discard(EntityRemoveEvent.Cause.HIT);
                                return;
                            }
                            // CraftBukkit end
                            if (this.random.nextFloat() < 0.05F && worldserver.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                                Endermite entityendermite = (Endermite) EntityType.ENDERMITE.create(worldserver, EntitySpawnReason.TRIGGERED);

                                if (entityendermite != null) {
                                    entityendermite.moveTo(entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
                                    worldserver.addFreshEntity(entityendermite, CreatureSpawnEvent.SpawnReason.ENDER_PEARL);
                                }
                            }

                            if (this.isOnPortalCooldown()) {
                                entity.setPortalCooldown();
                            }

                            // EntityPlayer entityplayer1 = entityplayer.teleport(new TeleportTransition(worldserver, vec3d, Vec3D.ZERO, 0.0F, 0.0F, Relative.union(Relative.ROTATION, Relative.DELTA), TeleportTransition.DO_NOTHING)); // CraftBukkit - moved up

                            if (entityplayer1 != null) {
                                entityplayer1.resetFallDistance();
                                entityplayer1.resetCurrentImpulseContext();
                                entityplayer1.hurtServer(entityplayer.serverLevel(), this.damageSources().enderPearl().customEventDamager(this), 5.0F); // CraftBukkit // Paper - fix DamageSource API
                            }

                            this.playSound(worldserver, vec3d);
                        }
                    } else {
                        Entity entity1 = entity.teleport(new TeleportTransition(worldserver, vec3d, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));

                        if (entity1 != null) {
                            entity1.resetFallDistance();
                        }

                        this.playSound(worldserver, vec3d);
                    }

                    this.discard(EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
                    return;
                }

                this.discard(EntityRemoveEvent.Cause.HIT); // CraftBukkit - add Bukkit remove cause
                return;
            }
        }

    }

    private static boolean isAllowedToTeleportOwner(Entity entity, Level world) {
        if (entity.level().dimension() == world.dimension()) {
            if (!(entity instanceof LivingEntity)) {
                return entity.isAlive();
            } else {
                LivingEntity entityliving = (LivingEntity) entity;

                return entityliving.isAlive() && !entityliving.isSleeping();
            }
        } else {
            return entity.canUsePortal(true);
        }
    }

    @Override
    public void tick() {
        int i;
        int j;
        Entity entity;
        label30:
        {
            i = SectionPos.blockToSectionCoord(this.position().x());
            j = SectionPos.blockToSectionCoord(this.position().z());
            entity = this.getOwner();
            if (entity instanceof ServerPlayer entityplayer) {
                if (!entity.isAlive() && entityplayer.serverLevel().getGameRules().getBoolean(GameRules.RULE_ENDER_PEARLS_VANISH_ON_DEATH)) {
                    this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit - add Bukkit remove cause
                    break label30;
                }
            }

            super.tick();
        }

        if (this.isAlive()) {
            BlockPos blockposition = BlockPos.containing(this.position());

            if ((--this.ticketTimer <= 0L || i != SectionPos.blockToSectionCoord(blockposition.getX()) || j != SectionPos.blockToSectionCoord(blockposition.getZ())) && entity instanceof ServerPlayer) {
                ServerPlayer entityplayer1 = (ServerPlayer) entity;

                this.ticketTimer = entityplayer1.registerAndUpdateEnderPearlTicket(this);
            }

        }
    }

    // Folia start - region threading
    @Override
    public void preChangeDimension() {
        super.preChangeDimension();
        // Don't change the owner here, since the tick logic will consider it anyways.
    }
    // Folia end - region threading

    private static void playSound(Level world, Vec3 pos) { // Folia - region threading - static
        world.playSound((Player) null, pos.x, pos.y, pos.z, SoundEvents.PLAYER_TELEPORT, SoundSource.PLAYERS);
    }

    @Nullable
    @Override
    public Entity teleport(TeleportTransition teleportTarget) {
        Entity entity = super.teleport(teleportTarget);

        if (entity != null) {
            if (!this.level().paperConfig().misc.legacyEnderPearlBehavior) entity.placePortalTicket(BlockPos.containing(entity.position())); // Paper - Allow using old ender pearl behavior
        }

        return entity;
    }

    @Override
    public boolean canTeleport(Level from, Level to) {
        if (from.getTypeKey() == LevelStem.END && to.getTypeKey() == LevelStem.OVERWORLD) { // CraftBukkit
            Entity entity = this.getOwner();

            if (entity instanceof ServerPlayer) {
                ServerPlayer entityplayer = (ServerPlayer) entity;

                return super.canTeleport(from, to) && entityplayer.seenCredits;
            }
        }

        return super.canTeleport(from, to);
    }

    @Override
    protected void onInsideBlock(BlockState state) {
        super.onInsideBlock(state);
        if (state.is(Blocks.END_GATEWAY)) {
            Entity entity = this.getOwner();

            if (entity instanceof ServerPlayer) {
                ServerPlayer entityplayer = (ServerPlayer) entity;

                entityplayer.onInsideBlock(state);
            }
        }

    }

    @Override
    public void onRemoval(Entity.RemovalReason reason) {
        if (reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            this.deregisterFromCurrentOwner();
        }

        super.onRemoval(reason);
    }
}
