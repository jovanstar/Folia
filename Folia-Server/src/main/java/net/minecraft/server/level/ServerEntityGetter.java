package net.minecraft.server.level;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.EntityGetter;
import net.minecraft.world.phys.AABB;

public interface ServerEntityGetter extends EntityGetter {
    ServerLevel getLevel();

    @Nullable
    default Player getNearestPlayer(TargetingConditions targetPredicate, LivingEntity entity) {
        return this.getNearestEntity(this.getLocalPlayers(), targetPredicate, entity, entity.getX(), entity.getY(), entity.getZ()); // Folia - region threading
    }

    @Nullable
    default Player getNearestPlayer(TargetingConditions targetPredicate, LivingEntity entity, double x, double y, double z) {
        return this.getNearestEntity(this.getLocalPlayers(), targetPredicate, entity, x, y, z); // Folia - region threading
    }

    @Nullable
    default Player getNearestPlayer(TargetingConditions targetPredicate, double x, double y, double z) {
        return this.getNearestEntity(this.getLocalPlayers(), targetPredicate, null, x, y, z); // Folia - region threading
    }

    @Nullable
    default <T extends LivingEntity> T getNearestEntity(
        Class<? extends T> clazz, TargetingConditions targetPredicate, @Nullable LivingEntity entity, double x, double y, double z, AABB box
    ) {
        return this.getNearestEntity(this.getEntitiesOfClass(clazz, box, potentialEntity -> true), targetPredicate, entity, x, y, z);
    }

    @Nullable
    default <T extends LivingEntity> T getNearestEntity(
        List<? extends T> entities, TargetingConditions targetPredicate, @Nullable LivingEntity entity, double x, double y, double z
    ) {
        double d = -1.0;
        T livingEntity = null;

        for (T livingEntity2 : entities) {
            if (targetPredicate.test(this.getLevel(), entity, livingEntity2)) {
                double e = livingEntity2.distanceToSqr(x, y, z);
                if (d == -1.0 || e < d) {
                    d = e;
                    livingEntity = livingEntity2;
                }
            }
        }

        return livingEntity;
    }

    default List<Player> getNearbyPlayers(TargetingConditions targetPredicate, LivingEntity entity, AABB box) {
        List<Player> list = new ArrayList<>();

        for (Player player : this.getLocalPlayers()) { // Folia - region threading
            if (box.contains(player.getX(), player.getY(), player.getZ()) && targetPredicate.test(this.getLevel(), entity, player)) {
                list.add(player);
            }
        }

        return list;
    }

    default <T extends LivingEntity> List<T> getNearbyEntities(Class<T> clazz, TargetingConditions targetPredicate, LivingEntity entity, AABB box) {
        List<T> list = this.getEntitiesOfClass(clazz, box, entityx -> true);
        List<T> list2 = new ArrayList<>();

        for (T livingEntity : list) {
            if (targetPredicate.test(this.getLevel(), entity, livingEntity)) {
                list2.add(livingEntity);
            }
        }

        return list2;
    }
}
