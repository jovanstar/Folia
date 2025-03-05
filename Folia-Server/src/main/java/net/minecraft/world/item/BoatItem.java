package net.minecraft.world.item;

import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractBoat;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class BoatItem extends Item {

    private final EntityType<? extends AbstractBoat> entityType;

    public BoatItem(EntityType<? extends AbstractBoat> boatEntityType, Item.Properties settings) {
        super(settings);
        this.entityType = boatEntityType;
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);
        BlockHitResult movingobjectpositionblock = getPlayerPOVHitResult(world, user, ClipContext.Fluid.ANY);

        if (movingobjectpositionblock.getType() == HitResult.Type.MISS) {
            return InteractionResult.PASS;
        } else {
            Vec3 vec3d = user.getViewVector(1.0F);
            double d0 = 5.0D;
            List<Entity> list = world.getEntities((Entity) user, user.getBoundingBox().expandTowards(vec3d.scale(5.0D)).inflate(1.0D), EntitySelector.CAN_BE_PICKED);

            if (!list.isEmpty()) {
                Vec3 vec3d1 = user.getEyePosition();
                Iterator iterator = list.iterator();

                while (iterator.hasNext()) {
                    Entity entity = (Entity) iterator.next();
                    AABB axisalignedbb = entity.getBoundingBox().inflate((double) entity.getPickRadius());

                    if (axisalignedbb.contains(vec3d1)) {
                        return InteractionResult.PASS;
                    }
                }
            }

            if (movingobjectpositionblock.getType() == HitResult.Type.BLOCK) {
                // CraftBukkit start - Boat placement
                org.bukkit.event.player.PlayerInteractEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerInteractEvent(user, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, movingobjectpositionblock.getBlockPos(), movingobjectpositionblock.getDirection(), itemstack, false, hand, movingobjectpositionblock.getLocation());

                if (event.isCancelled()) {
                    return InteractionResult.PASS;
                }
                // CraftBukkit end
                AbstractBoat abstractboat = this.getBoat(world, movingobjectpositionblock, itemstack, user);

                if (abstractboat == null) {
                    return InteractionResult.FAIL;
                } else {
                    abstractboat.setYRot(user.getYRot());
                    if (!world.noCollision(abstractboat, abstractboat.getBoundingBox())) {
                        return InteractionResult.FAIL;
                    } else {
                        if (!world.isClientSide) {
                            // CraftBukkit start
                            if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(world, movingobjectpositionblock.getBlockPos(), movingobjectpositionblock.getDirection(), user, abstractboat, hand).isCancelled()) {
                                return InteractionResult.FAIL;
                            }

                            if (!world.addFreshEntity(abstractboat)) {
                                return InteractionResult.PASS;
                            }
                            // CraftBukkit end
                            world.gameEvent((Entity) user, (Holder) GameEvent.ENTITY_PLACE, movingobjectpositionblock.getLocation());
                            itemstack.consume(1, user);
                        }

                        user.awardStat(Stats.ITEM_USED.get(this));
                        return InteractionResult.SUCCESS;
                    }
                }
            } else {
                return InteractionResult.PASS;
            }
        }
    }

    @Nullable
    private AbstractBoat getBoat(Level world, HitResult hitResult, ItemStack stack, Player player) {
        AbstractBoat abstractboat = (AbstractBoat) this.entityType.create(world, EntitySpawnReason.SPAWN_ITEM_USE);

        if (abstractboat != null) {
            Vec3 vec3d = hitResult.getLocation();

            abstractboat.setInitialPos(vec3d.x, vec3d.y, vec3d.z);
            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                EntityType.createDefaultStackConfig(worldserver, stack, player).accept(abstractboat);
            }
        }

        return abstractboat;
    }
}
