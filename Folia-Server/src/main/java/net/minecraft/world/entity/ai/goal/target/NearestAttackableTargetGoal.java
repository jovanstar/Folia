package net.minecraft.world.entity.ai.goal.target;

import java.util.EnumSet;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

public class NearestAttackableTargetGoal<T extends LivingEntity> extends TargetGoal {

    private static final int DEFAULT_RANDOM_INTERVAL = 10;
    protected final Class<T> targetType;
    protected final int randomInterval;
    @Nullable
    protected LivingEntity target;
    protected TargetingConditions targetConditions;

    public NearestAttackableTargetGoal(Mob mob, Class<T> targetClass, boolean checkVisibility) {
        this(mob, targetClass, 10, checkVisibility, false, (TargetingConditions.Selector) null);
    }

    public NearestAttackableTargetGoal(Mob mob, Class<T> targetClass, boolean checkVisibility, TargetingConditions.Selector predicate) {
        this(mob, targetClass, 10, checkVisibility, false, predicate);
    }

    public NearestAttackableTargetGoal(Mob mob, Class<T> targetClass, boolean checkVisibility, boolean checkCanNavigate) {
        this(mob, targetClass, 10, checkVisibility, checkCanNavigate, (TargetingConditions.Selector) null);
    }

    public NearestAttackableTargetGoal(Mob mob, Class<T> targetClass, int reciprocalChance, boolean checkVisibility, boolean checkCanNavigate, @Nullable TargetingConditions.Selector targetPredicate) {
        super(mob, checkVisibility, checkCanNavigate);
        this.targetType = targetClass;
        this.randomInterval = reducedTickDelay(reciprocalChance);
        this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        this.targetConditions = TargetingConditions.forCombat().range(this.getFollowDistance()).selector(targetPredicate);
    }

    @Override
    public boolean canUse() {
        if (this.randomInterval > 0 && this.mob.getRandom().nextInt(this.randomInterval) != 0) {
            return false;
        } else {
            this.findTarget();
            return this.target != null;
        }
    }

    protected AABB getTargetSearchArea(double distance) {
        return this.mob.getBoundingBox().inflate(distance, distance, distance);
    }

    protected void findTarget() {
        ServerLevel worldserver = getServerLevel((Entity) this.mob);

        if (this.targetType != Player.class && this.targetType != ServerPlayer.class) {
            this.target = worldserver.getNearestEntity(this.mob.level().getEntitiesOfClass(this.targetType, this.getTargetSearchArea(this.getFollowDistance()), (entityliving) -> {
                return true;
            }), this.getTargetConditions(), this.mob, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
        } else {
            this.target = worldserver.getNearestPlayer(this.getTargetConditions(), this.mob, this.mob.getX(), this.mob.getEyeY(), this.mob.getZ());
        }

    }

    @Override
    public void start() {
        this.mob.setTarget(this.target, this.target instanceof ServerPlayer ? org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_PLAYER : org.bukkit.event.entity.EntityTargetEvent.TargetReason.CLOSEST_ENTITY, true); // CraftBukkit - reason
        super.start();
    }

    public void setTarget(@Nullable LivingEntity targetEntity) {
        this.target = targetEntity;
    }

    private TargetingConditions getTargetConditions() {
        return this.targetConditions.range(this.getFollowDistance());
    }
}
