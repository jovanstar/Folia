package net.minecraft.world.entity.animal;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.craftbukkit.event.CraftEventFactory;
// CraftBukkit end

public class SnowGolem extends AbstractGolem implements Shearable, RangedAttackMob {

    private static final EntityDataAccessor<Byte> DATA_PUMPKIN_ID = SynchedEntityData.defineId(SnowGolem.class, EntityDataSerializers.BYTE);
    private static final byte PUMPKIN_FLAG = 16;

    public SnowGolem(EntityType<? extends SnowGolem> type, Level world) {
        super(type, world);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new RangedAttackGoal(this, 1.25D, 20, 10.0F));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, 1.0D, 1.0000001E-5F));
        this.goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(4, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false, (entityliving, worldserver) -> {
            return entityliving instanceof Enemy;
        }));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 4.0D).add(Attributes.MOVEMENT_SPEED, 0.20000000298023224D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SnowGolem.DATA_PUMPKIN_ID, (byte) 16);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("Pumpkin", this.hasPumpkin());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        if (nbt.contains("Pumpkin")) {
            this.setPumpkin(nbt.getBoolean("Pumpkin"));
        }

    }

    @Override
    public boolean isSensitiveToWater() {
        return true;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            if (this.level().getBiome(this.blockPosition()).is(BiomeTags.SNOW_GOLEM_MELTS)) {
                this.hurtServer(worldserver, this.damageSources().melting(), 1.0F); // CraftBukkit - DamageSources.ON_FIRE -> CraftEventFactory.MELTING
            }

            if (!worldserver.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
                return;
            }

            BlockState iblockdata = Blocks.SNOW.defaultBlockState();

            for (int i = 0; i < 4; ++i) {
                int j = Mth.floor(this.getX() + (double) ((float) (i % 2 * 2 - 1) * 0.25F));
                int k = Mth.floor(this.getY());
                int l = Mth.floor(this.getZ() + (double) ((float) (i / 2 % 2 * 2 - 1) * 0.25F));
                BlockPos blockposition = new BlockPos(j, k, l);

                if (this.level().getBlockState(blockposition).isAir() && iblockdata.canSurvive(this.level(), blockposition)) {
                    // CraftBukkit start
                    if (!CraftEventFactory.handleBlockFormEvent(this.level(), blockposition, iblockdata, this)) {
                        continue;
                    }
                    // CraftBukkit end
                    this.level().gameEvent((Holder) GameEvent.BLOCK_PLACE, blockposition, GameEvent.Context.of(this, iblockdata));
                }
            }
        }

    }

    @Override
    public void performRangedAttack(LivingEntity target, float pullProgress) {
        double d0 = target.getX() - this.getX();
        double d1 = target.getEyeY() - 1.100000023841858D;
        double d2 = target.getZ() - this.getZ();
        double d3 = Math.sqrt(d0 * d0 + d2 * d2) * 0.20000000298023224D;
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            ItemStack itemstack = new ItemStack(Items.SNOWBALL);

            Projectile.spawnProjectile(new Snowball(worldserver, this, itemstack), worldserver, itemstack, (entitysnowball) -> {
                entitysnowball.shoot(d0, d1 + d3 - entitysnowball.getY(), d2, 1.6F, 12.0F);
            });
        }

        this.playSound(SoundEvents.SNOW_GOLEM_SHOOT, 1.0F, 0.4F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);

        if (itemstack.is(Items.SHEARS) && this.readyForShearing()) {
            Level world = this.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                // CraftBukkit start
                // Paper start - custom shear drops
                java.util.List<ItemStack> drops = this.generateDefaultDrops(worldserver, itemstack);
                org.bukkit.event.player.PlayerShearEntityEvent event = CraftEventFactory.handlePlayerShearEntityEvent(player, this, itemstack, hand, drops);
                if (event != null) {
                    if (event.isCancelled()) {
                        return InteractionResult.PASS;
                    }
                    drops = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getDrops());
                // Paper end - custom shear drops
                }
                // CraftBukkit end
                this.shear(worldserver, SoundSource.PLAYERS, itemstack, drops); // Paper - custom shear drops
                this.gameEvent(GameEvent.SHEAR, player);
                itemstack.hurtAndBreak(1, player, getSlotForHand(hand));
            }

            return InteractionResult.SUCCESS;
        } else {
            return InteractionResult.PASS;
        }
    }

    @Override
    public void shear(ServerLevel world, SoundSource shearedSoundCategory, ItemStack shears) {
    // Paper start - custom shear drops
        this.shear(world, shearedSoundCategory, shears, this.generateDefaultDrops(world, shears));
    }

    @Override
    public java.util.List<ItemStack> generateDefaultDrops(final ServerLevel serverLevel, final ItemStack shears) {
        final java.util.List<ItemStack> drops = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        this.dropFromShearingLootTable(serverLevel, BuiltInLootTables.SHEAR_SNOW_GOLEM, shears, (ignored, stack) -> {
            drops.add(stack);
        });
        return drops;
    }

    @Override
    public void shear(ServerLevel world, SoundSource shearedSoundCategory, ItemStack shears, java.util.List<ItemStack> drops) {
        final ServerLevel worldserver1 = world; // Named for lambda consumption
    // Paper end - custom shear drops
        world.playSound((Player) null, (Entity) this, SoundEvents.SNOW_GOLEM_SHEAR, shearedSoundCategory, 1.0F, 1.0F);
        this.setPumpkin(false);
        drops.forEach(itemstack1 -> { // Paper - custom shear drops
            this.forceDrops = true; // CraftBukkit
            this.spawnAtLocation(worldserver1, itemstack1, this.getEyeHeight());
            this.forceDrops = false; // CraftBukkit
        });
    }

    @Override
    public boolean readyForShearing() {
        return this.isAlive() && this.hasPumpkin();
    }

    public boolean hasPumpkin() {
        return ((Byte) this.entityData.get(SnowGolem.DATA_PUMPKIN_ID) & 16) != 0;
    }

    public void setPumpkin(boolean hasPumpkin) {
        byte b0 = (Byte) this.entityData.get(SnowGolem.DATA_PUMPKIN_ID);

        if (hasPumpkin) {
            this.entityData.set(SnowGolem.DATA_PUMPKIN_ID, (byte) (b0 | 16));
        } else {
            this.entityData.set(SnowGolem.DATA_PUMPKIN_ID, (byte) (b0 & -17));
        }

    }

    @Nullable
    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SNOW_GOLEM_AMBIENT;
    }

    @Nullable
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SNOW_GOLEM_HURT;
    }

    @Nullable
    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.SNOW_GOLEM_DEATH;
    }

    @Override
    public Vec3 getLeashOffset() {
        return new Vec3(0.0D, (double) (0.75F * this.getEyeHeight()), (double) (this.getBbWidth() * 0.4F));
    }
}
