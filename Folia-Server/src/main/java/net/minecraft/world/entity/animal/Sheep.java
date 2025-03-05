package net.minecraft.world.entity.animal;

import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.BreedGoal;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowParentGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.item.DyeColor;
// CraftBukkit start
import net.minecraft.world.item.Item;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.SheepRegrowWoolEvent;
// CraftBukkit end

public class Sheep extends Animal implements Shearable {

    private static final int EAT_ANIMATION_TICKS = 40;
    private static final EntityDataAccessor<Byte> DATA_WOOL_ID = SynchedEntityData.defineId(Sheep.class, EntityDataSerializers.BYTE);
    private static final Map<DyeColor, Integer> COLOR_BY_DYE = Maps.newEnumMap((Map) Arrays.stream(DyeColor.values()).collect(Collectors.toMap((enumcolor) -> {
        return enumcolor;
    }, Sheep::createSheepColor)));
    private int eatAnimationTick;
    private EatBlockGoal eatBlockGoal;

    private static int createSheepColor(DyeColor color) {
        if (color == DyeColor.WHITE) {
            return -1644826;
        } else {
            int i = color.getTextureDiffuseColor();
            float f = 0.75F;

            return ARGB.color(255, Mth.floor((float) ARGB.red(i) * 0.75F), Mth.floor((float) ARGB.green(i) * 0.75F), Mth.floor((float) ARGB.blue(i) * 0.75F));
        }
    }

    public static int getColor(DyeColor dyeColor) {
        return (Integer) Sheep.COLOR_BY_DYE.get(dyeColor);
    }

    public Sheep(EntityType<? extends Sheep> type, Level world) {
        super(type, world);
    }

    @Override
    protected void registerGoals() {
        this.eatBlockGoal = new EatBlockGoal(this);
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new PanicGoal(this, 1.25D));
        this.goalSelector.addGoal(2, new BreedGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new TemptGoal(this, 1.1D, (itemstack) -> {
            return itemstack.is(ItemTags.SHEEP_FOOD);
        }, false));
        this.goalSelector.addGoal(4, new FollowParentGoal(this, 1.1D));
        this.goalSelector.addGoal(5, this.eatBlockGoal);
        this.goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 6.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean isFood(ItemStack stack) {
        return stack.is(ItemTags.SHEEP_FOOD);
    }

    @Override
    protected void customServerAiStep(ServerLevel world) {
        this.eatAnimationTick = this.eatBlockGoal.getEatAnimationTick();
        super.customServerAiStep(world);
    }

    @Override
    public void aiStep() {
        if (this.level().isClientSide) {
            this.eatAnimationTick = Math.max(0, this.eatAnimationTick - 1);
        }

        super.aiStep();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Animal.createAnimalAttributes().add(Attributes.MAX_HEALTH, 8.0D).add(Attributes.MOVEMENT_SPEED, 0.23000000417232513D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(Sheep.DATA_WOOL_ID, (byte) 0);
    }

    @Override
    public void handleEntityEvent(byte status) {
        if (status == 10) {
            this.eatAnimationTick = 40;
        } else {
            super.handleEntityEvent(status);
        }

    }

    public float getHeadEatPositionScale(float delta) {
        return this.eatAnimationTick <= 0 ? 0.0F : (this.eatAnimationTick >= 4 && this.eatAnimationTick <= 36 ? 1.0F : (this.eatAnimationTick < 4 ? ((float) this.eatAnimationTick - delta) / 4.0F : -((float) (this.eatAnimationTick - 40) - delta) / 4.0F));
    }

    public float getHeadEatAngleScale(float delta) {
        if (this.eatAnimationTick > 4 && this.eatAnimationTick <= 36) {
            float f1 = ((float) (this.eatAnimationTick - 4) - delta) / 32.0F;

            return 0.62831855F + 0.21991149F * Mth.sin(f1 * 28.7F);
        } else {
            return this.eatAnimationTick > 0 ? 0.62831855F : this.getXRot() * 0.017453292F;
        }
    }

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        ItemStack itemstack = player.getItemInHand(hand);

        if (itemstack.is(Items.SHEARS)) {
            Level world = this.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                if (this.readyForShearing()) {
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
                    return InteractionResult.SUCCESS_SERVER;
                }
            }

            return InteractionResult.CONSUME;
        } else {
            return super.mobInteract(player, hand);
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
        this.dropFromShearingLootTable(serverLevel, BuiltInLootTables.SHEAR_SHEEP, shears, (ignored, stack) -> {
            for (int i = 0; i < stack.getCount(); ++i) drops.add(stack.copyWithCount(1));
        });
        return drops;
    }

    @Override
    public void shear(ServerLevel world, SoundSource shearedSoundCategory, ItemStack shears, java.util.List<ItemStack> drops) {
        final ServerLevel worldserver1 = world; // Named for lambda consumption
        // Paper end - custom shear drops
        world.playSound((Player) null, (Entity) this, SoundEvents.SHEEP_SHEAR, shearedSoundCategory, 1.0F, 1.0F);
        drops.forEach(itemstack1 -> { // Paper - custom drops - loop in generated default drops
            if (true) { // Paper - custom drops - loop in generated default drops
                this.forceDrops = true; // CraftBukkit
                ItemEntity entityitem = this.spawnAtLocation(worldserver1, itemstack1, 1.0F); // Paper - custom drops - copy already done above
                this.forceDrops = false; // CraftBukkit

                if (entityitem != null) {
                    entityitem.setDeltaMovement(entityitem.getDeltaMovement().add((double) ((this.random.nextFloat() - this.random.nextFloat()) * 0.1F), (double) (this.random.nextFloat() * 0.05F), (double) ((this.random.nextFloat() - this.random.nextFloat()) * 0.1F)));
                }
            }

        });
        this.setSheared(true);
    }

    @Override
    public boolean readyForShearing() {
        return this.isAlive() && !this.isSheared() && !this.isBaby();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putBoolean("Sheared", this.isSheared());
        nbt.putByte("Color", (byte) this.getColor().getId());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        this.setSheared(nbt.getBoolean("Sheared"));
        this.setColor(DyeColor.byId(nbt.getByte("Color")));
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SHEEP_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SHEEP_HURT;
    }

    @Override
    public SoundEvent getDeathSound() {
        return SoundEvents.SHEEP_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SHEEP_STEP, 0.15F, 1.0F);
    }

    public DyeColor getColor() {
        return DyeColor.byId((Byte) this.entityData.get(Sheep.DATA_WOOL_ID) & 15);
    }

    public void setColor(DyeColor color) {
        byte b0 = (Byte) this.entityData.get(Sheep.DATA_WOOL_ID);

        this.entityData.set(Sheep.DATA_WOOL_ID, (byte) (b0 & 240 | color.getId() & 15));
    }

    public boolean isSheared() {
        return ((Byte) this.entityData.get(Sheep.DATA_WOOL_ID) & 16) != 0;
    }

    public void setSheared(boolean sheared) {
        byte b0 = (Byte) this.entityData.get(Sheep.DATA_WOOL_ID);

        if (sheared) {
            this.entityData.set(Sheep.DATA_WOOL_ID, (byte) (b0 | 16));
        } else {
            this.entityData.set(Sheep.DATA_WOOL_ID, (byte) (b0 & -17));
        }

    }

    public static DyeColor getRandomSheepColor(RandomSource random) {
        int i = random.nextInt(100);

        return i < 5 ? DyeColor.BLACK : (i < 10 ? DyeColor.GRAY : (i < 15 ? DyeColor.LIGHT_GRAY : (i < 18 ? DyeColor.BROWN : (random.nextInt(500) == 0 ? DyeColor.PINK : DyeColor.WHITE))));
    }

    @Nullable
    @Override
    public Sheep getBreedOffspring(ServerLevel world, AgeableMob entity) {
        Sheep entitysheep = (Sheep) EntityType.SHEEP.create(world, EntitySpawnReason.BREEDING);

        if (entitysheep != null) {
            DyeColor enumcolor = this.getColor();
            DyeColor enumcolor1 = ((Sheep) entity).getColor();

            entitysheep.setColor(DyeColor.getMixedColor(world, enumcolor, enumcolor1));
        }

        return entitysheep;
    }

    @Override
    public void ate() {
        // CraftBukkit start
        SheepRegrowWoolEvent event = new SheepRegrowWoolEvent((org.bukkit.entity.Sheep) this.getBukkitEntity());
        this.level().getCraftServer().getPluginManager().callEvent(event);

        if (event.isCancelled()) return;
        // CraftBukkit end
        super.ate();
        this.setSheared(false);
        if (this.isBaby()) {
            this.ageUp(60);
        }

    }

    @Nullable
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor world, DifficultyInstance difficulty, EntitySpawnReason spawnReason, @Nullable SpawnGroupData entityData) {
        this.setColor(Sheep.getRandomSheepColor(world.getRandom()));
        return super.finalizeSpawn(world, difficulty, spawnReason, entityData);
    }
}
