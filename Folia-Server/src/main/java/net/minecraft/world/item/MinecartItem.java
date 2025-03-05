package net.minecraft.world.item;

import java.util.Iterator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

public class MinecartItem extends Item {

    private final EntityType<? extends AbstractMinecart> type;

    public MinecartItem(EntityType<? extends AbstractMinecart> type, Item.Properties settings) {
        super(settings);
        this.type = type;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level world = context.getLevel();
        BlockPos blockposition = context.getClickedPos();
        BlockState iblockdata = world.getBlockState(blockposition);

        if (!iblockdata.is(BlockTags.RAILS)) {
            return InteractionResult.FAIL;
        } else {
            ItemStack itemstack = context.getItemInHand();
            RailShape blockpropertytrackposition = iblockdata.getBlock() instanceof BaseRailBlock ? (RailShape) iblockdata.getValue(((BaseRailBlock) iblockdata.getBlock()).getShapeProperty()) : RailShape.NORTH_SOUTH;
            double d0 = 0.0D;

            if (blockpropertytrackposition.isSlope()) {
                d0 = 0.5D;
            }

            Vec3 vec3d = new Vec3((double) blockposition.getX() + 0.5D, (double) blockposition.getY() + 0.0625D + d0, (double) blockposition.getZ() + 0.5D);
            AbstractMinecart entityminecartabstract = AbstractMinecart.createMinecart(world, vec3d.x, vec3d.y, vec3d.z, this.type, EntitySpawnReason.DISPENSER, itemstack, context.getPlayer());

            if (entityminecartabstract == null) {
                return InteractionResult.FAIL;
            } else {
                if (AbstractMinecart.useExperimentalMovement(world)) {
                    List<Entity> list = world.getEntities((Entity) null, entityminecartabstract.getBoundingBox());
                    Iterator iterator = list.iterator();

                    while (iterator.hasNext()) {
                        Entity entity = (Entity) iterator.next();

                        if (entity instanceof AbstractMinecart) {
                            return InteractionResult.FAIL;
                        }
                    }
                }

                if (world instanceof ServerLevel) {
                    ServerLevel worldserver = (ServerLevel) world;

                    // CraftBukkit start
                    if (org.bukkit.craftbukkit.event.CraftEventFactory.callEntityPlaceEvent(context, entityminecartabstract).isCancelled()) {
                    if (context.getPlayer() != null) context.getPlayer().containerMenu.sendAllDataToRemote(); // Paper - Fix inventory desync
                        return InteractionResult.FAIL;
                    }
                    // CraftBukkit end
                    if (!worldserver.addFreshEntity(entityminecartabstract)) return InteractionResult.PASS; // CraftBukkit
                    worldserver.gameEvent((Holder) GameEvent.ENTITY_PLACE, blockposition, GameEvent.Context.of(context.getPlayer(), worldserver.getBlockState(blockposition.below())));
                }

                itemstack.shrink(1);
                return InteractionResult.SUCCESS;
            }
        }
    }
}
