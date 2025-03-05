package net.minecraft.world.item;

import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class EnderEyeItem extends Item {

    public EnderEyeItem(Item.Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level world = context.getLevel();
        BlockPos blockposition = context.getClickedPos();
        BlockState iblockdata = world.getBlockState(blockposition);

        if (iblockdata.is(Blocks.END_PORTAL_FRAME) && !(Boolean) iblockdata.getValue(EndPortalFrameBlock.HAS_EYE)) {
            if (world.isClientSide) {
                return InteractionResult.SUCCESS;
            } else {
                BlockState iblockdata1 = (BlockState) iblockdata.setValue(EndPortalFrameBlock.HAS_EYE, true);
                // Paper start
                if (!org.bukkit.craftbukkit.event.CraftEventFactory.callEntityChangeBlockEvent(context.getPlayer(), blockposition, iblockdata1)) {
                    return InteractionResult.PASS;
                }
                // Paper end

                Block.pushEntitiesUp(iblockdata, iblockdata1, world, blockposition);
                world.setBlock(blockposition, iblockdata1, 2);
                world.updateNeighbourForOutputSignal(blockposition, Blocks.END_PORTAL_FRAME);
                context.getItemInHand().shrink(1);
                world.levelEvent(1503, blockposition, 0);
                BlockPattern.BlockPatternMatch shapedetector_shapedetectorcollection = EndPortalFrameBlock.getOrCreatePortalShape().find(world, blockposition);

                if (shapedetector_shapedetectorcollection != null) {
                    BlockPos blockposition1 = shapedetector_shapedetectorcollection.getFrontTopLeft().offset(-3, 0, -3);

                    for (int i = 0; i < 3; ++i) {
                        for (int j = 0; j < 3; ++j) {
                            world.setBlock(blockposition1.offset(i, 0, j), Blocks.END_PORTAL.defaultBlockState(), 2);
                        }
                    }

                    // CraftBukkit start - Use relative location for far away sounds
                    // world.globalLevelEvent(1038, blockposition1.offset(1, 0, 1), 0);
                    int viewDistance = world.getCraftServer().getViewDistance() * 16;
                    BlockPos soundPos = blockposition1.offset(1, 0, 1);
                    final net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) world; // Paper - respect global sound events gamerule - ensured by isClientSide check above
                    for (ServerPlayer player : serverLevel.getPlayersForGlobalSoundGamerule()) { // Paper - respect global sound events gamerule
                        double deltaX = soundPos.getX() - player.getX();
                        double deltaZ = soundPos.getZ() - player.getZ();
                        double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;
                        final double soundRadiusSquared = serverLevel.getGlobalSoundRangeSquared(config -> config.endPortalSoundRadius); // Paper - respect global sound events gamerule
                        if (!serverLevel.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_GLOBAL_SOUND_EVENTS) && distanceSquared > soundRadiusSquared) continue; // Spigot // Paper - respect global sound events gamerule
                        if (distanceSquared > viewDistance * viewDistance) {
                            double deltaLength = Math.sqrt(distanceSquared);
                            double relativeX = player.getX() + (deltaX / deltaLength) * viewDistance;
                            double relativeZ = player.getZ() + (deltaZ / deltaLength) * viewDistance;
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(1038, new BlockPos((int) relativeX, (int) soundPos.getY(), (int) relativeZ), 0, true));
                        } else {
                            player.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelEventPacket(1038, soundPos, 0, true));
                        }
                    }
                    // CraftBukkit end
                }

                return InteractionResult.SUCCESS;
            }
        } else {
            return InteractionResult.PASS;
        }
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return 0;
    }

    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemstack = user.getItemInHand(hand);
        BlockHitResult movingobjectpositionblock = getPlayerPOVHitResult(world, user, ClipContext.Fluid.NONE);

        if (movingobjectpositionblock.getType() == HitResult.Type.BLOCK && world.getBlockState(movingobjectpositionblock.getBlockPos()).is(Blocks.END_PORTAL_FRAME)) {
            return InteractionResult.PASS;
        } else {
            user.startUsingItem(hand);
            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;
                BlockPos blockposition = worldserver.findNearestMapStructure(StructureTags.EYE_OF_ENDER_LOCATED, user.blockPosition(), 100, false);

                if (blockposition == null) {
                    return InteractionResult.CONSUME;
                }

                EyeOfEnder entityendersignal = new EyeOfEnder(world, user.getX(), user.getY(0.5D), user.getZ());

                entityendersignal.setItem(itemstack);
                entityendersignal.signalTo(blockposition);
                world.gameEvent((Holder) GameEvent.PROJECTILE_SHOOT, entityendersignal.position(), GameEvent.Context.of((Entity) user));
                // CraftBukkit start
                if (!world.addFreshEntity(entityendersignal)) {
                    return InteractionResult.FAIL;
                }
                // CraftBukkit end
                if (user instanceof ServerPlayer) {
                    ServerPlayer entityplayer = (ServerPlayer) user;

                    CriteriaTriggers.USED_ENDER_EYE.trigger(entityplayer, blockposition);
                }

                float f = Mth.lerp(world.random.nextFloat(), 0.33F, 0.5F);

                world.playSound((Player) null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 1.0F, f);
                itemstack.consume(1, user);
                user.awardStat(Stats.ITEM_USED.get(this));
            }

            return InteractionResult.SUCCESS_SERVER;
        }
    }
}
