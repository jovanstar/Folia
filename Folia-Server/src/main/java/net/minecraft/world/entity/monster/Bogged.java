package net.minecraft.world.entity.monster;

import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.bukkit.craftbukkit.event.CraftEventFactory;

public class Bogged extends AbstractSkeleton implements Shearable {

    private static final int HARD_ATTACK_INTERVAL = 50;
    private static final int NORMAL_ATTACK_INTERVAL = 70;
    private static final EntityDataAccessor<Boolean> DATA_SHEARED = SynchedEntityData.defineId(Bogged.class, EntityDataSerializers.BOOLEAN);
    public static final String SHEARED_TAG_NAME = "sheared";

    public static AttributeSupplier.Builder createAttributes() {
        return AbstractSkeleton.createAttributes().add(Attributes.MAX_HEALTH, 16.0D);
    }

    public Bogged(EntityType<? extends Bogged> type, Level world) {
        super(type, world);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(Bogged.DATA_SHEARED, false);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("sheared", this.isSheared());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        this.setSheared(nbt.getBoolean("sheared"));
    }

    public boolean isSheared() {
        return (Boolean) this.entityData.get(Bogged.DATA_SHEARED);
    }

    public void setSheared(boolean sheared) {
        this.entityData.set(Bogged.DATA_SHEARED, sheared);
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
                        // this.getEntityData().markDirty(Bogged.DATA_SHEARED); // CraftBukkit - mark dirty to restore sheared state to clients // Paper - no longer needed
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
            return super.mobInteract(player, hand);
        }
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.BOGGED_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.BOGGED_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.BOGGED_DEATH;
    }

    @Override
    protected SoundEvent getStepSound() {
        return SoundEvents.BOGGED_STEP;
    }

    @Override
    protected AbstractArrow getArrow(ItemStack arrow, float damageModifier, @Nullable ItemStack shotFrom) {
        AbstractArrow entityarrow = super.getArrow(arrow, damageModifier, shotFrom);

        if (entityarrow instanceof Arrow entitytippedarrow) {
            entitytippedarrow.addEffect(new MobEffectInstance(MobEffects.POISON, 100));
        }

        return entityarrow;
    }

    @Override
    protected int getHardAttackInterval() {
        return 50;
    }

    @Override
    protected int getAttackInterval() {
        return 70;
    }

    @Override
    public void shear(ServerLevel world, SoundSource shearedSoundCategory, ItemStack shears) {
    // Paper start - custom shear drops
        this.shear(world, shearedSoundCategory, shears, this.generateDefaultDrops(world, shears));
    }

    @Override
    public java.util.List<ItemStack> generateDefaultDrops(final ServerLevel serverLevel, final ItemStack shears) {
        final java.util.List<ItemStack> drops = new it.unimi.dsi.fastutil.objects.ObjectArrayList<>();
        this.dropFromShearingLootTable(serverLevel, BuiltInLootTables.BOGGED_SHEAR, shears, (ignored, stack) -> {
            drops.add(stack);
        });
        return drops;
    }

    @Override
    public void shear(ServerLevel world, SoundSource shearedSoundCategory, ItemStack shears, java.util.List<ItemStack> drops) {
    // Paper end - custom shear drops
        world.playSound((Player) null, (Entity) this, SoundEvents.BOGGED_SHEAR, shearedSoundCategory, 1.0F, 1.0F);
        this.spawnShearedMushrooms(world, shears, drops); // Paper - custom shear drops
        this.setSheared(true);
    }

    // Paper start - custom shear drops
    private void spawnShearedMushrooms(ServerLevel world, ItemStack shears, java.util.List<ItemStack> drops) {
        final ServerLevel worldserver1 = world; // Named for lambda consumption
        this.forceDrops = true; // Paper - Add missing forceDrop toggles
        drops.forEach(itemstack1 -> {
    // Paper end - custom shear drops
            this.spawnAtLocation(worldserver1, itemstack1, this.getBbHeight());
        });
        this.forceDrops = false; // Paper - Add missing forceDrop toggles
    }

    @Override
    public boolean readyForShearing() {
        return !this.isSheared() && this.isAlive();
    }
}
