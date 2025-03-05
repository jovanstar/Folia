package net.minecraft.world.level.block;

import com.mojang.serialization.MapCodec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
// CraftBukkit start
import java.util.List;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.event.CraftPortalEvent;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
// CraftBukkit end

public class EndPortalBlock extends BaseEntityBlock implements Portal {

    public static final MapCodec<EndPortalBlock> CODEC = simpleCodec(EndPortalBlock::new);
    protected static final VoxelShape SHAPE = Block.box(0.0D, 6.0D, 0.0D, 16.0D, 12.0D, 16.0D);

    @Override
    public MapCodec<EndPortalBlock> codec() {
        return EndPortalBlock.CODEC;
    }

    protected EndPortalBlock(BlockBehaviour.Properties settings) {
        super(settings);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TheEndPortalBlockEntity(pos, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return EndPortalBlock.SHAPE;
    }

    @Override
    protected VoxelShape getEntityInsideCollisionShape(BlockState state, Level world, BlockPos pos) {
        return state.getShape(world, pos);
    }

    @Override
    protected void entityInside(BlockState state, Level world, BlockPos pos, Entity entity) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(world, pos)).callEvent()) { return; } // Paper - Add EntityInsideBlockEvent
        if (entity.canUsePortal(false)) {
            // CraftBukkit start - Entity in portal
            EntityPortalEnterEvent event = new EntityPortalEnterEvent(entity.getBukkitEntity(), new org.bukkit.Location(world.getWorld(), pos.getX(), pos.getY(), pos.getZ()), org.bukkit.PortalType.ENDER); // Paper - add portal type
            world.getCraftServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) return; // Paper - make cancellable
            // CraftBukkit end
            // Folia - region threading - do not show credits

            entity.setAsInsidePortal(this, pos);
        }

    }

    @Override
    public TeleportTransition getPortalDestination(ServerLevel world, Entity entity, BlockPos pos) {
        ResourceKey<Level> resourcekey = world.getTypeKey() == LevelStem.END ? Level.OVERWORLD : Level.END; // CraftBukkit - SPIGOT-6152: send back to main overworld in custom ends
        ServerLevel worldserver1 = world.getServer().getLevel(resourcekey);

        if (worldserver1 == null) {
            return null; // Paper - keep previous behavior of not firing PlayerTeleportEvent if the target world doesn't exist
        } else {
            boolean flag = resourcekey == Level.END;
            BlockPos blockposition1 = flag ? ServerLevel.END_SPAWN_POINT : worldserver1.getSharedSpawnPos();
            Vec3 vec3d = blockposition1.getBottomCenter();
            float f;
            Set set;

            if (flag) {
                EndPlatformFeature.createEndPlatform(worldserver1, BlockPos.containing(vec3d).below(), true, entity); // CraftBukkit
                f = Direction.WEST.toYRot();
                set = Relative.union(Relative.DELTA, Set.of(Relative.X_ROT));
                if (entity instanceof ServerPlayer) {
                    vec3d = vec3d.subtract(0.0D, 1.0D, 0.0D);
                }
            } else {
                f = 0.0F;
                set = Relative.union(Relative.DELTA, Relative.ROTATION);
                if (entity instanceof ServerPlayer) {
                    ServerPlayer entityplayer = (ServerPlayer) entity;

                    return entityplayer.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING, PlayerRespawnEvent.RespawnReason.END_PORTAL); // CraftBukkit
                }

                vec3d = entity.adjustSpawnLocation(worldserver1, blockposition1).getBottomCenter();
            }

            // CraftBukkit start
            CraftPortalEvent event = entity.callPortalEvent(entity, CraftLocation.toBukkit(vec3d, worldserver1.getWorld(), f, entity.getXRot()), PlayerTeleportEvent.TeleportCause.END_PORTAL, 0, 0);
            if (event == null) {
                return null;
            }
            Location to = event.getTo();

            return new TeleportTransition(((CraftWorld) to.getWorld()).getHandle(), CraftLocation.toVec3D(to), entity.getDeltaMovement(), to.getYaw(), to.getPitch(), set, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET), PlayerTeleportEvent.TeleportCause.END_PORTAL);
            // CraftBukkit end
        }
    }

    // Folia start - region threading
    @Override
    public boolean portalAsync(ServerLevel sourceWorld, Entity portalTarget, BlockPos portalPos) {
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(portalTarget)) {
            return false;
        }
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(sourceWorld, portalPos)) {
            return false;
        }

        return portalTarget.endPortalLogicAsync(portalPos);
    }
    // Folia end - region threading

    @Override
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        double d0 = (double) pos.getX() + random.nextDouble();
        double d1 = (double) pos.getY() + 0.8D;
        double d2 = (double) pos.getZ() + random.nextDouble();

        world.addParticle(ParticleTypes.SMOKE, d0, d1, d2, 0.0D, 0.0D, 0.0D);
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return ItemStack.EMPTY;
    }

    @Override
    protected boolean canBeReplaced(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }
}
