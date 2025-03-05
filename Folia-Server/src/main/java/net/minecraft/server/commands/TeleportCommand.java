package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.RotationArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
// CraftBukkit end

public class TeleportCommand {

    private static final SimpleCommandExceptionType INVALID_POSITION = new SimpleCommandExceptionType(Component.translatable("commands.teleport.invalidPosition"));

    public TeleportCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralCommandNode<CommandSourceStack> literalcommandnode = dispatcher.register((LiteralArgumentBuilder) ((LiteralArgumentBuilder) ((LiteralArgumentBuilder) ((LiteralArgumentBuilder) net.minecraft.commands.Commands.literal("teleport").requires((commandlistenerwrapper) -> {
            return commandlistenerwrapper.hasPermission(2);
        })).then(net.minecraft.commands.Commands.argument("location", Vec3Argument.vec3()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), Collections.singleton(((CommandSourceStack) commandcontext.getSource()).getEntityOrException()), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), (Coordinates) null, (LookAt) null);
        }))).then(net.minecraft.commands.Commands.argument("destination", EntityArgument.entity()).executes((commandcontext) -> {
            return TeleportCommand.teleportToEntity((CommandSourceStack) commandcontext.getSource(), Collections.singleton(((CommandSourceStack) commandcontext.getSource()).getEntityOrException()), EntityArgument.getEntity(commandcontext, "destination"));
        }))).then(((RequiredArgumentBuilder) net.minecraft.commands.Commands.argument("targets", EntityArgument.entities()).then(((RequiredArgumentBuilder) ((RequiredArgumentBuilder) net.minecraft.commands.Commands.argument("location", Vec3Argument.vec3()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), (Coordinates) null, (LookAt) null);
        })).then(net.minecraft.commands.Commands.argument("rotation", RotationArgument.rotation()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), RotationArgument.getRotation(commandcontext, "rotation"), (LookAt) null);
        }))).then(((LiteralArgumentBuilder) net.minecraft.commands.Commands.literal("facing").then(net.minecraft.commands.Commands.literal("entity").then(((RequiredArgumentBuilder) net.minecraft.commands.Commands.argument("facingEntity", EntityArgument.entity()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), (Coordinates) null, new LookAt.LookAtEntity(EntityArgument.getEntity(commandcontext, "facingEntity"), EntityAnchorArgument.Anchor.FEET));
        })).then(net.minecraft.commands.Commands.argument("facingAnchor", EntityAnchorArgument.anchor()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), (Coordinates) null, new LookAt.LookAtEntity(EntityArgument.getEntity(commandcontext, "facingEntity"), EntityAnchorArgument.getAnchor(commandcontext, "facingAnchor")));
        }))))).then(net.minecraft.commands.Commands.argument("facingLocation", Vec3Argument.vec3()).executes((commandcontext) -> {
            return TeleportCommand.teleportToPos((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), ((CommandSourceStack) commandcontext.getSource()).getLevel(), Vec3Argument.getCoordinates(commandcontext, "location"), (Coordinates) null, new LookAt.LookAtPosition(Vec3Argument.getVec3(commandcontext, "facingLocation")));
        }))))).then(net.minecraft.commands.Commands.argument("destination", EntityArgument.entity()).executes((commandcontext) -> {
            return TeleportCommand.teleportToEntity((CommandSourceStack) commandcontext.getSource(), EntityArgument.getEntities(commandcontext, "targets"), EntityArgument.getEntity(commandcontext, "destination"));
        }))));

        dispatcher.register((LiteralArgumentBuilder) ((LiteralArgumentBuilder) net.minecraft.commands.Commands.literal("tp").requires((commandlistenerwrapper) -> {
            return commandlistenerwrapper.hasPermission(2);
        })).redirect(literalcommandnode));
    }

    private static int teleportToEntity(CommandSourceStack source, Collection<? extends Entity> targets, Entity destination) throws CommandSyntaxException {
        Iterator iterator = targets.iterator();

        while (iterator.hasNext()) {
            Entity entity1 = (Entity) iterator.next();

            io.papermc.paper.threadedregions.TeleportUtils.teleport(entity1, false, destination, Float.valueOf(destination.getYRot()), Float.valueOf(destination.getXRot()), Entity.TELEPORT_FLAG_LOAD_CHUNK, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND, null); // Folia - region threading
        }

        if (targets.size() == 1) {
            source.sendSuccess(() -> {
                return Component.translatable("commands.teleport.success.entity.single", ((Entity) targets.iterator().next()).getDisplayName(), destination.getDisplayName());
            }, true);
        } else {
            source.sendSuccess(() -> {
                return Component.translatable("commands.teleport.success.entity.multiple", targets.size(), destination.getDisplayName());
            }, true);
        }

        return targets.size();
    }

    private static int teleportToPos(CommandSourceStack source, Collection<? extends Entity> targets, ServerLevel world, Coordinates location, @Nullable Coordinates rotation, @Nullable LookAt facingLocation) throws CommandSyntaxException {
        Vec3 vec3d = location.getPosition(source);
        Vec2 vec2f = rotation == null ? null : rotation.getRotation(source);
        Iterator iterator = targets.iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();
            Set<Relative> set = TeleportCommand.getRelatives(location, rotation, entity.level().dimension() == world.dimension());

            if (vec2f == null) {
                TeleportCommand.performTeleport(source, entity, world, vec3d.x, vec3d.y, vec3d.z, set, entity.getYRot(), entity.getXRot(), facingLocation);
            } else {
                TeleportCommand.performTeleport(source, entity, world, vec3d.x, vec3d.y, vec3d.z, set, vec2f.y, vec2f.x, facingLocation);
            }
        }

        if (targets.size() == 1) {
            source.sendSuccess(() -> {
                return Component.translatable("commands.teleport.success.location.single", ((Entity) targets.iterator().next()).getDisplayName(), TeleportCommand.formatDouble(vec3d.x), TeleportCommand.formatDouble(vec3d.y), TeleportCommand.formatDouble(vec3d.z));
            }, true);
        } else {
            source.sendSuccess(() -> {
                return Component.translatable("commands.teleport.success.location.multiple", targets.size(), TeleportCommand.formatDouble(vec3d.x), TeleportCommand.formatDouble(vec3d.y), TeleportCommand.formatDouble(vec3d.z));
            }, true);
        }

        return targets.size();
    }

    private static Set<Relative> getRelatives(Coordinates pos, @Nullable Coordinates rotation, boolean sameDimension) {
        Set<Relative> set = EnumSet.noneOf(Relative.class);

        if (pos.isXRelative()) {
            set.add(Relative.DELTA_X);
            if (sameDimension) {
                set.add(Relative.X);
            }
        }

        if (pos.isYRelative()) {
            set.add(Relative.DELTA_Y);
            if (sameDimension) {
                set.add(Relative.Y);
            }
        }

        if (pos.isZRelative()) {
            set.add(Relative.DELTA_Z);
            if (sameDimension) {
                set.add(Relative.Z);
            }
        }

        if (rotation == null || rotation.isXRelative()) {
            set.add(Relative.X_ROT);
        }

        if (rotation == null || rotation.isYRelative()) {
            set.add(Relative.Y_ROT);
        }

        return set;
    }

    private static String formatDouble(double d) {
        return String.format(Locale.ROOT, "%f", d);
    }

    private static void performTeleport(CommandSourceStack source, Entity target, ServerLevel world, double x, double y, double z, Set<Relative> movementFlags, float yaw, float pitch, @Nullable LookAt facingLocation) throws CommandSyntaxException {
        BlockPos blockposition = BlockPos.containing(x, y, z);

        if (!Level.isInSpawnableBounds(blockposition)) {
            throw TeleportCommand.INVALID_POSITION.create();
        } else {
            double d3 = movementFlags.contains(Relative.X) ? x - target.getX() : x;
            double d4 = movementFlags.contains(Relative.Y) ? y - target.getY() : y;
            double d5 = movementFlags.contains(Relative.Z) ? z - target.getZ() : z;
            float f2 = movementFlags.contains(Relative.Y_ROT) ? yaw - target.getYRot() : yaw;
            float f3 = movementFlags.contains(Relative.X_ROT) ? pitch - target.getXRot() : pitch;
            float f4 = Mth.wrapDegrees(f2);
            float f5 = Mth.wrapDegrees(f3);

            // Folia start - region threading
            if (true) {
                ServerLevel worldFinal = world;
                Vec3 posFinal = new Vec3(x, y, z);
                Float yawFinal = Float.valueOf(f2);
                Float pitchFinal = Float.valueOf(f3);
                target.getBukkitEntity().taskScheduler.schedule((nmsEntity) -> {
                    nmsEntity.unRide();
                    nmsEntity.teleportAsync(
                        worldFinal, posFinal, yawFinal, pitchFinal, Vec3.ZERO,
                        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND,
                        Entity.TELEPORT_FLAG_LOAD_CHUNK,
                        null
                    );
                }, null, 1L);
                return;
            }
            // Folia end - region threading
            // CraftBukkit start - Teleport event
            boolean result;
            if (target instanceof ServerPlayer player) {
                result = player.teleportTo(world, d3, d4, d5, movementFlags, f4, f5, true, PlayerTeleportEvent.TeleportCause.COMMAND);
            } else {
                Location to = new Location(world.getWorld(), d3, d4, d5, f4, f5);
                EntityTeleportEvent event = new EntityTeleportEvent(target.getBukkitEntity(), target.getBukkitEntity().getLocation(), to);
                world.getCraftServer().getPluginManager().callEvent(event);
                if (event.isCancelled() || event.getTo() == null) { // Paper
                    return;
                }
                to = event.getTo(); // Paper - actually track new location

                d3 = to.getX();
                d4 = to.getY();
                d5 = to.getZ();
                f4 = to.getYaw();
                f5 = to.getPitch();
                world = ((CraftWorld) to.getWorld()).getHandle();

                result = target.teleportTo(world, d3, d4, d5, movementFlags, f4, f5, true);
            }

            if (result) {
                // CraftBukkit end
                if (facingLocation != null) {
                    facingLocation.perform(source, target);
                }

                label46:
                {
                    if (target instanceof LivingEntity) {
                        LivingEntity entityliving = (LivingEntity) target;

                        if (entityliving.isFallFlying()) {
                            break label46;
                        }
                    }

                    target.setDeltaMovement(target.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
                    target.setOnGround(true);
                }

                if (target instanceof PathfinderMob) {
                    PathfinderMob entitycreature = (PathfinderMob) target;

                    entitycreature.getNavigation().stop();
                }

            }
        }
    }
}
