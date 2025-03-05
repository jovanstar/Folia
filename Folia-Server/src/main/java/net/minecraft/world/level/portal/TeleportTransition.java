package net.minecraft.world.level.portal;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.event.player.PlayerTeleportEvent;

public record TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot, boolean missingRespawnBlock, boolean asPassenger, Set<Relative> relatives, TeleportTransition.PostTeleportTransition postTeleportTransition, PlayerTeleportEvent.TeleportCause cause) {

    public TeleportTransition(ServerLevel newLevel, Vec3 position, Vec3 deltaMovement, float yRot, float xRot, boolean missingRespawnBlock, boolean asPassenger, Set<Relative> relatives, TeleportTransition.PostTeleportTransition postTeleportTransition) {
        this(newLevel, position, deltaMovement, yRot, xRot, missingRespawnBlock, asPassenger, relatives, postTeleportTransition, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public TeleportTransition(PlayerTeleportEvent.TeleportCause cause) {
        this(null, Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F, false, false, Set.of(), DO_NOTHING, cause);
    }
    // CraftBukkit end

    public static final TeleportTransition.PostTeleportTransition DO_NOTHING = (entity) -> {
    };
    public static final TeleportTransition.PostTeleportTransition PLAY_PORTAL_SOUND = TeleportTransition::playPortalSound;
    public static final TeleportTransition.PostTeleportTransition PLACE_PORTAL_TICKET = TeleportTransition::placePortalTicket;

    public TeleportTransition(ServerLevel world, Vec3 pos, Vec3 velocity, float yaw, float pitch, TeleportTransition.PostTeleportTransition postDimensionTransition) {
        // CraftBukkit start
        this(world, pos, velocity, yaw, pitch, postDimensionTransition, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public TeleportTransition(ServerLevel worldserver, Vec3 vec3d, Vec3 vec3d1, float f, float f1, TeleportTransition.PostTeleportTransition teleporttransition_a, PlayerTeleportEvent.TeleportCause cause) {
        this(worldserver, vec3d, vec3d1, f, f1, Set.of(), teleporttransition_a, cause);
        // CraftBukkit end
    }

    public TeleportTransition(ServerLevel world, Vec3 pos, Vec3 velocity, float yaw, float pitch, Set<Relative> flags, TeleportTransition.PostTeleportTransition postDimensionTransition) {
        // CraftBukkit start
        this(world, pos, velocity, yaw, pitch, flags, postDimensionTransition, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public TeleportTransition(ServerLevel worldserver, Vec3 vec3d, Vec3 vec3d1, float f, float f1, Set<Relative> set, TeleportTransition.PostTeleportTransition teleporttransition_a, PlayerTeleportEvent.TeleportCause cause) {
        this(worldserver, vec3d, vec3d1, f, f1, false, false, set, teleporttransition_a, cause);
        // CraftBukkit end
    }

    public TeleportTransition(ServerLevel world, Entity entity, TeleportTransition.PostTeleportTransition postDimensionTransition) {
        // CraftBukkit start
        this(world, entity, postDimensionTransition, PlayerTeleportEvent.TeleportCause.UNKNOWN);
    }

    public TeleportTransition(ServerLevel worldserver, Entity entity, TeleportTransition.PostTeleportTransition teleporttransition_a, PlayerTeleportEvent.TeleportCause cause) {
        this(worldserver, findAdjustedSharedSpawnPos(worldserver, entity), Vec3.ZERO, worldserver.getSharedSpawnAngle(), 0.0F, false, false, Set.of(), teleporttransition_a, cause); // Paper - MC-200092 - fix first spawn pos yaw being ignored
        // CraftBukkit end
    }

    private static void playPortalSound(Entity entity) {
        if (entity instanceof ServerPlayer entityplayer) {
            entityplayer.connection.send(new ClientboundLevelEventPacket(1032, BlockPos.ZERO, 0, false));
        }

    }

    private static void placePortalTicket(Entity entity) {
        entity.placePortalTicket(BlockPos.containing(entity.position()));
    }

    public static TeleportTransition missingRespawnBlock(ServerLevel world, Entity entity, TeleportTransition.PostTeleportTransition postDimensionTransition) {
        return new TeleportTransition(world, findAdjustedSharedSpawnPos(world, entity), Vec3.ZERO, world.getSharedSpawnAngle(), 0.0F, true, false, Set.of(), postDimensionTransition); // Paper - MC-200092 - fix spawn pos yaw being ignored
    }

    private static Vec3 findAdjustedSharedSpawnPos(ServerLevel world, Entity entity) {
        return entity.adjustSpawnLocation(world, world.getSharedSpawnPos()).getBottomCenter();
    }

    public TeleportTransition withRotation(float yaw, float pitch) {
        return new TeleportTransition(this.newLevel(), this.position(), this.deltaMovement(), yaw, pitch, this.missingRespawnBlock(), this.asPassenger(), this.relatives(), this.postTeleportTransition());
    }

    public TeleportTransition withPosition(Vec3 position) {
        return new TeleportTransition(this.newLevel(), position, this.deltaMovement(), this.yRot(), this.xRot(), this.missingRespawnBlock(), this.asPassenger(), this.relatives(), this.postTeleportTransition());
    }

    public TeleportTransition transitionAsPassenger() {
        return new TeleportTransition(this.newLevel(), this.position(), this.deltaMovement(), this.yRot(), this.xRot(), this.missingRespawnBlock(), true, this.relatives(), this.postTeleportTransition());
    }

    @FunctionalInterface
    public interface PostTeleportTransition {

        void onTransition(Entity entity);

        default TeleportTransition.PostTeleportTransition then(TeleportTransition.PostTeleportTransition next) {
            return (entity) -> {
                this.onTransition(entity);
                next.onTransition(entity);
            };
        }
    }
}
