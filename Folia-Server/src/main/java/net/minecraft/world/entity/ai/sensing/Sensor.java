package net.minecraft.world.entity.ai.sensing;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;

public abstract class Sensor<E extends LivingEntity> {
    private static final RandomSource RANDOM = RandomSource.createThreadSafe();
    private static final int DEFAULT_SCAN_RATE = 20;
    private static final int DEFAULT_TARGETING_RANGE = 16;
    private static final TargetingConditions TARGET_CONDITIONS = TargetingConditions.forNonCombat().range(16.0);
    private static final TargetingConditions TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING = TargetingConditions.forNonCombat()
        .range(16.0)
        .ignoreInvisibilityTesting();
    private static final TargetingConditions ATTACK_TARGET_CONDITIONS = TargetingConditions.forCombat().range(16.0);
    private static final TargetingConditions ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING = TargetingConditions.forCombat()
        .range(16.0)
        .ignoreInvisibilityTesting();
    private static final TargetingConditions ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT = TargetingConditions.forCombat().range(16.0).ignoreLineOfSight();
    private static final TargetingConditions ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT = TargetingConditions.forCombat()
        .range(16.0)
        .ignoreLineOfSight()
        .ignoreInvisibilityTesting();
    private final int scanRate;
    private long timeToTick;
    // Paper start - configurable sensor tick rate and timings
    private final String configKey;
    // Paper end

    public Sensor(int senseInterval) {
        // Paper start - configurable sensor tick rate and timings
        String key = io.papermc.paper.util.MappingEnvironment.reobf() ? io.papermc.paper.util.ObfHelper.INSTANCE.deobfClassName(this.getClass().getName()) : this.getClass().getName();
        int lastSeparator = key.lastIndexOf('.');
        if (lastSeparator != -1) {
            key = key.substring(lastSeparator + 1);
        }
        this.configKey = key.toLowerCase(java.util.Locale.ROOT);
        // Paper end
        this.scanRate = senseInterval;
        this.timeToTick = (long)RANDOM.nextInt(senseInterval);
    }

    public Sensor() {
        this(20);
    }

    public final void tick(ServerLevel world, E entity) {
        if (--this.timeToTick <= 0L) {
            // Paper start - configurable sensor tick rate and timings
            this.timeToTick = java.util.Objects.requireNonNullElse(world.paperConfig().tickRates.sensor.get(entity.getType(), this.configKey), this.scanRate);
            this.updateTargetingConditionRanges(entity);
            // Paper end
            this.doTick(world, entity);
        }
    }

    private void updateTargetingConditionRanges(E entity) {
        double d = entity.getAttributeValue(Attributes.FOLLOW_RANGE);
        TARGET_CONDITIONS.range(d);
        TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING.range(d);
        ATTACK_TARGET_CONDITIONS.range(d);
        ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING.range(d);
        ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT.range(d);
        ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT.range(d);
    }

    protected abstract void doTick(ServerLevel world, E entity);

    public abstract Set<MemoryModuleType<?>> requires();

    public static boolean isEntityTargetable(ServerLevel world, LivingEntity entity, LivingEntity target) {
        return entity.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, target)
            ? TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING.test(world, entity, target)
            : TARGET_CONDITIONS.test(world, entity, target);
    }

    public static boolean isEntityAttackable(ServerLevel world, LivingEntity entity, LivingEntity target) {
        return entity.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, target)
            ? ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_TESTING.test(world, entity, target)
            : ATTACK_TARGET_CONDITIONS.test(world, entity, target);
    }

    public static BiPredicate<ServerLevel, LivingEntity> wasEntityAttackableLastNTicks(LivingEntity entity, int ticks) {
        return rememberPositives(ticks, (world, target) -> isEntityAttackable(world, entity, target));
    }

    public static boolean isEntityAttackableIgnoringLineOfSight(ServerLevel world, LivingEntity entity, LivingEntity target) {
        return entity.getBrain().isMemoryValue(MemoryModuleType.ATTACK_TARGET, target)
            ? ATTACK_TARGET_CONDITIONS_IGNORE_INVISIBILITY_AND_LINE_OF_SIGHT.test(world, entity, target)
            : ATTACK_TARGET_CONDITIONS_IGNORE_LINE_OF_SIGHT.test(world, entity, target);
    }

    static <T, U> BiPredicate<T, U> rememberPositives(int times, BiPredicate<T, U> predicate) {
        AtomicInteger atomicInteger = new AtomicInteger(0);
        return (world, target) -> {
            if (predicate.test(world, target)) {
                atomicInteger.set(times);
                return true;
            } else {
                return atomicInteger.decrementAndGet() >= 0;
            }
        };
    }
}
