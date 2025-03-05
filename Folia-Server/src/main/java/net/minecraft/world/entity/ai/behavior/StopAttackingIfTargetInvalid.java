package net.minecraft.world.entity.ai.behavior;

import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

// CraftBukkit start
import org.bukkit.craftbukkit.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityTargetEvent;
// CraftBukkit end

public class StopAttackingIfTargetInvalid {

    private static final int TIMEOUT_TO_GET_WITHIN_ATTACK_RANGE = 200;

    public StopAttackingIfTargetInvalid() {}

    public static <E extends Mob> BehaviorControl<E> create(StopAttackingIfTargetInvalid.TargetErasedCallback<E> callback) {
        return StopAttackingIfTargetInvalid.create((worldserver, entityliving) -> {
            return false;
        }, callback, true);
    }

    public static <E extends Mob> BehaviorControl<E> create(StopAttackingIfTargetInvalid.StopAttackCondition condition) {
        return StopAttackingIfTargetInvalid.create(condition, (worldserver, entityinsentient, entityliving) -> {
        }, true);
    }

    public static <E extends Mob> BehaviorControl<E> create() {
        return StopAttackingIfTargetInvalid.create((worldserver, entityliving) -> {
            return false;
        }, (worldserver, entityinsentient, entityliving) -> {
        }, true);
    }

    public static <E extends Mob> BehaviorControl<E> create(StopAttackingIfTargetInvalid.StopAttackCondition condition, StopAttackingIfTargetInvalid.TargetErasedCallback<E> callback, boolean shouldForgetIfTargetUnreachable) {
        return BehaviorBuilder.create((behaviorbuilder_b) -> {
            return behaviorbuilder_b.group(behaviorbuilder_b.present(MemoryModuleType.ATTACK_TARGET), behaviorbuilder_b.registered(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)).apply(behaviorbuilder_b, (memoryaccessor, memoryaccessor1) -> {
                return (worldserver, entityinsentient, i) -> {
                    LivingEntity entityliving = (LivingEntity) behaviorbuilder_b.get(memoryaccessor);

                    if (entityinsentient.canAttack(entityliving) && (!shouldForgetIfTargetUnreachable || !StopAttackingIfTargetInvalid.isTiredOfTryingToReachTarget(entityinsentient, behaviorbuilder_b.tryGet(memoryaccessor1))) && entityliving.isAlive() && entityliving.level() == entityinsentient.level() && !condition.test(worldserver, entityliving)) {
                        return true;
                    } else {
                        // Paper start - better track target change reason
                        final EntityTargetEvent.TargetReason reason;
                        if (!entityinsentient.canAttack(entityliving)) {
                            reason = EntityTargetEvent.TargetReason.TARGET_INVALID;
                        } else if (shouldForgetIfTargetUnreachable && StopAttackingIfTargetInvalid.isTiredOfTryingToReachTarget(entityinsentient, behaviorbuilder_b.tryGet(memoryaccessor1))) {
                            reason = EntityTargetEvent.TargetReason.FORGOT_TARGET;
                        } else if (!entityliving.isAlive()) {
                            reason = EntityTargetEvent.TargetReason.TARGET_DIED;
                        } else if (entityliving.level() != entityinsentient.level()) {
                            reason = EntityTargetEvent.TargetReason.TARGET_OTHER_LEVEL;
                        } else {
                            reason = EntityTargetEvent.TargetReason.TARGET_INVALID;
                        }
                        // Paper end
                        // CraftBukkit start
                        EntityTargetEvent event = CraftEventFactory.callEntityTargetLivingEvent(entityinsentient, null, reason); // Paper
                        if (event.isCancelled()) {
                            return false;
                        }
                        if (event.getTarget() != null) {
                            entityinsentient.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, ((CraftLivingEntity) event.getTarget()).getHandle());
                            return true;
                        }
                        // CraftBukkit end
                        callback.accept(worldserver, entityinsentient, entityliving);
                        memoryaccessor.erase();
                        return true;
                    }
                };
            });
        });
    }

    private static boolean isTiredOfTryingToReachTarget(LivingEntity target, Optional<Long> lastReachTime) {
        return lastReachTime.isPresent() && target.level().getGameTime() - (Long) lastReachTime.get() > 200L;
    }

    @FunctionalInterface
    public interface StopAttackCondition {

        boolean test(ServerLevel world, LivingEntity target);
    }

    @FunctionalInterface
    public interface TargetErasedCallback<E> {

        void accept(ServerLevel world, E entity, LivingEntity target);
    }
}
