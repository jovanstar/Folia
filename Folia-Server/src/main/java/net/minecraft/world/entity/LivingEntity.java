package net.minecraft.world.entity;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import net.minecraft.BlockUtil;
import net.minecraft.Util;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.CombatTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.component.DeathProtection;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.effects.EnchantmentLocationBasedEffect;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;

// CraftBukkit start
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedList;
import java.util.UUID;
import net.minecraft.world.item.component.Consumable;
import org.bukkit.Location;
import org.bukkit.craftbukkit.attribute.CraftAttributeMap;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ArrowBodyCountChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityKnockbackEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
// CraftBukkit end

public abstract class LivingEntity extends Entity implements Attackable {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG_ACTIVE_EFFECTS = "active_effects";
    private static final ResourceLocation SPEED_MODIFIER_POWDER_SNOW_ID = ResourceLocation.withDefaultNamespace("powder_snow");
    private static final ResourceLocation SPRINTING_MODIFIER_ID = ResourceLocation.withDefaultNamespace("sprinting");
    private static final AttributeModifier SPEED_MODIFIER_SPRINTING = new AttributeModifier(LivingEntity.SPRINTING_MODIFIER_ID, 0.30000001192092896D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
    public static final int HAND_SLOTS = 2;
    public static final int ARMOR_SLOTS = 4;
    public static final int EQUIPMENT_SLOT_OFFSET = 98;
    public static final int ARMOR_SLOT_OFFSET = 100;
    public static final int BODY_ARMOR_OFFSET = 105;
    public static final int SWING_DURATION = 6;
    public static final int PLAYER_HURT_EXPERIENCE_TIME = 100;
    private static final int DAMAGE_SOURCE_TIMEOUT = 40;
    public static final double MIN_MOVEMENT_DISTANCE = 0.003D;
    public static final double DEFAULT_BASE_GRAVITY = 0.08D;
    public static final int DEATH_DURATION = 20;
    private static final int TICKS_PER_ELYTRA_FREE_FALL_EVENT = 10;
    private static final int FREE_FALL_EVENTS_PER_ELYTRA_BREAK = 2;
    public static final float BASE_JUMP_POWER = 0.42F;
    private static final double MAX_LINE_OF_SIGHT_TEST_RANGE = 128.0D;
    protected static final int LIVING_ENTITY_FLAG_IS_USING = 1;
    protected static final int LIVING_ENTITY_FLAG_OFF_HAND = 2;
    public static final int LIVING_ENTITY_FLAG_SPIN_ATTACK = 4;
    protected static final EntityDataAccessor<Byte> DATA_LIVING_ENTITY_FLAGS = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BYTE);
    public static final EntityDataAccessor<Float> DATA_HEALTH_ID = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<List<ParticleOptions>> DATA_EFFECT_PARTICLES = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.PARTICLES);
    private static final EntityDataAccessor<Boolean> DATA_EFFECT_AMBIENCE_ID = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.BOOLEAN);
    public static final EntityDataAccessor<Integer> DATA_ARROW_COUNT_ID = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_STINGER_COUNT_ID = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Optional<BlockPos>> SLEEPING_POS_ID = SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.OPTIONAL_BLOCK_POS);
    private static final int PARTICLE_FREQUENCY_WHEN_INVISIBLE = 15;
    protected static final EntityDimensions SLEEPING_DIMENSIONS = EntityDimensions.fixed(0.2F, 0.2F).withEyeHeight(0.2F);
    public static final float EXTRA_RENDER_CULLING_SIZE_WITH_BIG_HAT = 0.5F;
    public static final float DEFAULT_BABY_SCALE = 0.5F;
    public static final String ATTRIBUTES_FIELD = "attributes";
    public static final Predicate<LivingEntity> PLAYER_NOT_WEARING_DISGUISE_ITEM = (entityliving) -> {
        if (entityliving instanceof net.minecraft.world.entity.player.Player entityhuman) {
            ItemStack itemstack = entityhuman.getItemBySlot(EquipmentSlot.HEAD);

            return !itemstack.is(ItemTags.GAZE_DISGUISE_EQUIPMENT);
        } else {
            return true;
        }
    };
    private final AttributeMap attributes;
    public CombatTracker combatTracker = new CombatTracker(this);
    public final Map<Holder<MobEffect>, MobEffectInstance> activeEffects = Maps.newHashMap();
    private final NonNullList<ItemStack> lastHandItemStacks;
    private final NonNullList<ItemStack> lastArmorItemStacks;
    private ItemStack lastBodyItemStack;
    public boolean swinging;
    private boolean discardFriction;
    public InteractionHand swingingArm;
    public int swingTime;
    public int removeArrowTime;
    public int removeStingerTime;
    public int hurtTime;
    public int hurtDuration;
    public int deathTime;
    public float oAttackAnim;
    public float attackAnim;
    protected int attackStrengthTicker;
    public final WalkAnimationState walkAnimation;
    public int invulnerableDuration;
    public final float timeOffs;
    public final float rotA;
    public float yBodyRot;
    public float yBodyRotO;
    public float yHeadRot;
    public float yHeadRotO;
    public final ElytraAnimationState elytraAnimationState;
    @Nullable
    public net.minecraft.world.entity.player.Player lastHurtByPlayer;
    public int lastHurtByPlayerTime;
    protected boolean dead;
    protected int noActionTime;
    protected float oRun;
    protected float run;
    protected float animStep;
    protected float animStepO;
    protected float rotOffs;
    public float lastHurt;
    public boolean jumping;
    public float xxa;
    public float yya;
    public float zza;
    protected int lerpSteps;
    protected double lerpX;
    protected double lerpY;
    protected double lerpZ;
    protected double lerpYRot;
    protected double lerpXRot;
    protected double lerpYHeadRot;
    protected int lerpHeadSteps;
    public boolean effectsDirty;
    @Nullable
    public LivingEntity lastHurtByMob;
    public int lastHurtByMobTimestamp;
    @Nullable
    private LivingEntity lastHurtMob;
    private int lastHurtMobTimestamp;
    private float speed;
    private int noJumpDelay;
    private float absorptionAmount;
    protected ItemStack useItem;
    public int useItemRemaining;
    protected int fallFlyTicks;
    private BlockPos lastPos;
    private Optional<BlockPos> lastClimbablePos;
    @Nullable
    private DamageSource lastDamageSource;
    private long lastDamageStamp = Long.MIN_VALUE; // Folia - region threading
    protected int autoSpinAttackTicks;
    protected float autoSpinAttackDmg;
    @Nullable
    protected ItemStack autoSpinAttackItemStack;
    private float swimAmount;
    private float swimAmountO;
    protected Brain<?> brain;
    protected boolean skipDropExperience;
    private final EnumMap<EquipmentSlot, Reference2ObjectMap<Enchantment, Set<EnchantmentLocationBasedEffect>>> activeLocationDependentEnchantments;
    protected float appliedScale;
    // CraftBukkit start
    public int expToDrop;
    public ArrayList<DefaultDrop> drops = new ArrayList<>(); // Paper - Restore vanilla drops behavior
    public final org.bukkit.craftbukkit.attribute.CraftAttributeMap craftAttributes;
    public boolean collides = true;
    public Set<UUID> collidableExemptions = new HashSet<>();
    public boolean bukkitPickUpLoot;
    public org.bukkit.craftbukkit.entity.CraftLivingEntity getBukkitLivingEntity() { return (org.bukkit.craftbukkit.entity.CraftLivingEntity) super.getBukkitEntity(); } // Paper
    public boolean silentDeath = false; // Paper - mark entity as dying silently for cancellable death event
    public net.kyori.adventure.util.TriState frictionState = net.kyori.adventure.util.TriState.NOT_SET; // Paper - Friction API

    @Override
    public float getBukkitYaw() {
        return this.getYHeadRot();
    }
    // CraftBukkit end
    // Spigot start
    public void inactiveTick()
    {
        super.inactiveTick();
        ++this.noActionTime; // Above all the floats
    }
    // Spigot end
    // Folia start - region threading
    @Override
    public void updateTicks(long fromTickOffset, long fromRedstoneTimeOffset) {
        super.updateTicks(fromTickOffset, fromRedstoneTimeOffset);
        if (this.lastDamageStamp != Long.MIN_VALUE) {
            this.lastDamageStamp += fromRedstoneTimeOffset;
        }
    }

    @Override
    protected void resetStoredPositions() {
        super.resetStoredPositions();
        this.lastClimbablePos = Optional.empty();
    }
    // Folia end - region threading

    protected LivingEntity(EntityType<? extends LivingEntity> type, Level world) {
        super(type, world);
        this.lastHandItemStacks = NonNullList.withSize(2, ItemStack.EMPTY);
        this.lastArmorItemStacks = NonNullList.withSize(4, ItemStack.EMPTY);
        this.lastBodyItemStack = ItemStack.EMPTY;
        this.discardFriction = false;
        this.walkAnimation = new WalkAnimationState();
        this.invulnerableDuration = 20;
        this.elytraAnimationState = new ElytraAnimationState(this);
        this.effectsDirty = true;
        this.useItem = ItemStack.EMPTY;
        this.lastClimbablePos = Optional.empty();
        this.activeLocationDependentEnchantments = new EnumMap(EquipmentSlot.class);
        this.appliedScale = 1.0F;
        this.attributes = new AttributeMap(DefaultAttributes.getSupplier(type));
        this.craftAttributes = new CraftAttributeMap(this.attributes); // CraftBukkit
        // CraftBukkit - setHealth(getMaxHealth()) inlined and simplified to skip the instanceof check for EntityPlayer, as getBukkitEntity() is not initialized in constructor
        this.entityData.set(LivingEntity.DATA_HEALTH_ID, (float) this.getAttribute(Attributes.MAX_HEALTH).getValue());
        this.blocksBuilding = true;
        this.rotA = (float) ((Math.random() + 1.0D) * 0.009999999776482582D);
        this.reapplyPosition();
        this.timeOffs = (float) Math.random() * 12398.0F;
        this.setYRot((float) (Math.random() * 6.2831854820251465D));
        this.yHeadRot = this.getYRot();
        NbtOps dynamicopsnbt = NbtOps.INSTANCE;

        this.brain = this.makeBrain(new Dynamic(dynamicopsnbt, (Tag) dynamicopsnbt.createMap((Map) ImmutableMap.of(dynamicopsnbt.createString("memories"), (Tag) dynamicopsnbt.emptyMap()))));
    }

    public Brain<?> getBrain() {
        return this.brain;
    }

    protected Brain.Provider<?> brainProvider() {
        return Brain.provider(ImmutableList.of(), ImmutableList.of());
    }

    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return this.brainProvider().makeBrain(dynamic);
    }

    @Override
    public void kill(ServerLevel world) {
        this.hurtServer(world, this.damageSources().genericKill(), Float.MAX_VALUE);
    }

    public boolean canAttackType(EntityType<?> type) {
        return true;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LivingEntity.DATA_LIVING_ENTITY_FLAGS, (byte) 0);
        builder.define(LivingEntity.DATA_EFFECT_PARTICLES, List.of());
        builder.define(LivingEntity.DATA_EFFECT_AMBIENCE_ID, false);
        builder.define(LivingEntity.DATA_ARROW_COUNT_ID, 0);
        builder.define(LivingEntity.DATA_STINGER_COUNT_ID, 0);
        builder.define(LivingEntity.DATA_HEALTH_ID, 1.0F);
        builder.define(LivingEntity.SLEEPING_POS_ID, Optional.empty());
    }

    public static AttributeSupplier.Builder createLivingAttributes() {
        return AttributeSupplier.builder().add(Attributes.MAX_HEALTH).add(Attributes.KNOCKBACK_RESISTANCE).add(Attributes.MOVEMENT_SPEED).add(Attributes.ARMOR).add(Attributes.ARMOR_TOUGHNESS).add(Attributes.MAX_ABSORPTION).add(Attributes.STEP_HEIGHT).add(Attributes.SCALE).add(Attributes.GRAVITY).add(Attributes.SAFE_FALL_DISTANCE).add(Attributes.FALL_DAMAGE_MULTIPLIER).add(Attributes.JUMP_STRENGTH).add(Attributes.OXYGEN_BONUS).add(Attributes.BURNING_TIME).add(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE).add(Attributes.WATER_MOVEMENT_EFFICIENCY).add(Attributes.MOVEMENT_EFFICIENCY).add(Attributes.ATTACK_KNOCKBACK);
    }

    @Override
    protected void checkFallDamage(double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition) {
        if (!this.isInWater()) {
            this.updateInWaterStateAndDoWaterCurrentPushing();
        }

        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            if (onGround && this.fallDistance > 0.0F) {
                this.onChangedBlock(worldserver, landedPosition);
                double d1 = this.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);

                if ((double) this.fallDistance > d1 && !state.isAir()) {
                    double d2 = this.getX();
                    double d3 = this.getY();
                    double d4 = this.getZ();
                    BlockPos blockposition1 = this.blockPosition();

                    if (landedPosition.getX() != blockposition1.getX() || landedPosition.getZ() != blockposition1.getZ()) {
                        double d5 = d2 - (double) landedPosition.getX() - 0.5D;
                        double d6 = d4 - (double) landedPosition.getZ() - 0.5D;
                        double d7 = Math.max(Math.abs(d5), Math.abs(d6));

                        d2 = (double) landedPosition.getX() + 0.5D + d5 / d7 * 0.5D;
                        d4 = (double) landedPosition.getZ() + 0.5D + d6 / d7 * 0.5D;
                    }

                    float f = (float) Mth.ceil((double) this.fallDistance - d1);
                    double d8 = Math.min((double) (0.2F + f / 15.0F), 2.5D);
                    int i = (int) (150.0D * d8);

                    // CraftBukkit start - visiblity api
                    if (this instanceof ServerPlayer) {
                        worldserver.sendParticlesSource((ServerPlayer) this, new BlockParticleOption(ParticleTypes.BLOCK, state), false, false, d2, d3, d4, i, 0.0D, 0.0D, 0.0D, 0.15000000596046448D);
                    } else {
                        worldserver.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), d2, d3, d4, i, 0.0D, 0.0D, 0.0D, 0.15000000596046448D);
                    }
                    // CraftBukkit end
                }
            }
        }

        super.checkFallDamage(heightDifference, onGround, state, landedPosition);
        if (onGround) {
            this.lastClimbablePos = Optional.empty();
        }

    }

    public final boolean canBreatheUnderwater() {
        return this.getType().is(EntityTypeTags.CAN_BREATHE_UNDER_WATER);
    }

    public float getSwimAmount(float tickDelta) {
        return Mth.lerp(tickDelta, this.swimAmountO, this.swimAmount);
    }

    public boolean hasLandedInLiquid() {
        return this.getDeltaMovement().y() < 9.999999747378752E-6D && this.isInLiquid();
    }

    @Override
    public void baseTick() {
        this.oAttackAnim = this.attackAnim;
        if (this.firstTick) {
            this.getSleepingPos().ifPresent(this::setPosToBed);
        }

        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            EnchantmentHelper.tickEffects(worldserver, this);
        }

        super.baseTick();
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("livingEntityBaseTick");
        if (this.fireImmune() || this.level().isClientSide) {
            this.clearFire();
        }

        if (this.isAlive()) {
            boolean flag = this instanceof net.minecraft.world.entity.player.Player;
            Level world1 = this.level();
            ServerLevel worldserver1;
            double d0;

            if (world1 instanceof ServerLevel) {
                worldserver1 = (ServerLevel) world1;
                if (this.isInWall()) {
                    this.hurtServer(worldserver1, this.damageSources().inWall(), 1.0F);
                } else if (flag && !this.level().getWorldBorder().isWithinBounds(this.getBoundingBox())) {
                    double d1 = this.level().getWorldBorder().getDistanceToBorder(this) + this.level().getWorldBorder().getDamageSafeZone();

                    if (d1 < 0.0D) {
                        d0 = this.level().getWorldBorder().getDamagePerBlock();
                        if (d0 > 0.0D) {
                            this.hurtServer(worldserver1, this.damageSources().outOfBorder(), (float) Math.max(1, Mth.floor(-d1 * d0)));
                        }
                    }
                }
            }

            if (this.isEyeInFluid(FluidTags.WATER) && !this.level().getBlockState(BlockPos.containing(this.getX(), this.getEyeY(), this.getZ())).is(Blocks.BUBBLE_COLUMN)) {
                boolean flag1 = !this.canBreatheUnderwater() && !MobEffectUtil.hasWaterBreathing(this) && (!flag || !((net.minecraft.world.entity.player.Player) this).getAbilities().invulnerable);

                if (flag1) {
                    this.setAirSupply(this.decreaseAirSupply(this.getAirSupply()));
                    if (this.getAirSupply() == -20) {
                        this.setAirSupply(0);
                        Vec3 vec3d = this.getDeltaMovement();

                        for (int i = 0; i < 8; ++i) {
                            d0 = this.random.nextDouble() - this.random.nextDouble();
                            double d2 = this.random.nextDouble() - this.random.nextDouble();
                            double d3 = this.random.nextDouble() - this.random.nextDouble();

                            this.level().addParticle(ParticleTypes.BUBBLE, this.getX() + d0, this.getY() + d2, this.getZ() + d3, vec3d.x, vec3d.y, vec3d.z);
                        }

                        this.hurt(this.damageSources().drown(), 2.0F);
                    }
                } else if (this.getAirSupply() < this.getMaxAirSupply()) {
                    this.setAirSupply(this.increaseAirSupply(this.getAirSupply()));
                }

                if (!this.level().isClientSide && this.isPassenger() && this.getVehicle() != null && this.getVehicle().dismountsUnderwater()) {
                    this.stopRiding();
                }
            } else if (this.getAirSupply() < this.getMaxAirSupply()) {
                this.setAirSupply(this.increaseAirSupply(this.getAirSupply()));
            }

            world1 = this.level();
            if (world1 instanceof ServerLevel) {
                worldserver1 = (ServerLevel) world1;
                BlockPos blockposition = this.blockPosition();

                if (!Objects.equal(this.lastPos, blockposition)) {
                    this.lastPos = blockposition;
                    this.onChangedBlock(worldserver1, blockposition);
                }
            }
        }

        if (this.isAlive() && (this.isInWaterRainOrBubble() || this.isInPowderSnow)) {
            this.extinguishFire();
        }

        if (this.hurtTime > 0) {
            --this.hurtTime;
        }

        if (this.invulnerableTime > 0 && !(this instanceof ServerPlayer)) {
            --this.invulnerableTime;
        }

        if (this.isDeadOrDying() && this.level().shouldTickDeath(this)) {
            this.tickDeath();
        } else { this.broadcastedDeath = false; } // Folia - region threading

        if (this.lastHurtByPlayerTime > 0) {
            --this.lastHurtByPlayerTime;
        } else {
            this.lastHurtByPlayer = null;
        }

        if (this.lastHurtMob != null && !this.lastHurtMob.isAlive()) {
            this.lastHurtMob = null;
        }

        if (this.lastHurtByMob != null) {
            if (!this.lastHurtByMob.isAlive()) {
                this.setLastHurtByMob((LivingEntity) null);
            } else if (this.tickCount - this.lastHurtByMobTimestamp > 100) {
                this.setLastHurtByMob((LivingEntity) null);
            }
        }

        this.tickEffects();
        this.animStepO = this.animStep;
        this.yBodyRotO = this.yBodyRot;
        this.yHeadRotO = this.yHeadRot;
        this.yRotO = this.getYRot();
        this.xRotO = this.getXRot();
        gameprofilerfiller.pop();
    }

    @Override
    protected float getBlockSpeedFactor() {
        return Mth.lerp((float) this.getAttributeValue(Attributes.MOVEMENT_EFFICIENCY), super.getBlockSpeedFactor(), 1.0F);
    }

    protected void removeFrost() {
        AttributeInstance attributemodifiable = this.getAttribute(Attributes.MOVEMENT_SPEED);

        if (attributemodifiable != null) {
            if (attributemodifiable.getModifier(LivingEntity.SPEED_MODIFIER_POWDER_SNOW_ID) != null) {
                attributemodifiable.removeModifier(LivingEntity.SPEED_MODIFIER_POWDER_SNOW_ID);
            }

        }
    }

    protected void tryAddFrost() {
        if (!this.getBlockStateOnLegacy().isAir()) {
            int i = this.getTicksFrozen();

            if (i > 0) {
                AttributeInstance attributemodifiable = this.getAttribute(Attributes.MOVEMENT_SPEED);

                if (attributemodifiable == null) {
                    return;
                }

                float f = -0.05F * this.getPercentFrozen();

                attributemodifiable.addTransientModifier(new AttributeModifier(LivingEntity.SPEED_MODIFIER_POWDER_SNOW_ID, (double) f, AttributeModifier.Operation.ADD_VALUE));
            }
        }

    }

    protected void onChangedBlock(ServerLevel world, BlockPos pos) {
        EnchantmentHelper.runLocationChangedEffects(world, this);
    }

    public boolean isBaby() {
        return false;
    }

    public float getAgeScale() {
        return this.isBaby() ? 0.5F : 1.0F;
    }

    public final float getScale() {
        AttributeMap attributemapbase = this.getAttributes();

        return attributemapbase == null ? 1.0F : this.sanitizeScale((float) attributemapbase.getValue(Attributes.SCALE));
    }

    protected float sanitizeScale(float scale) {
        return scale;
    }

    protected boolean isAffectedByFluids() {
        return true;
    }

    public boolean broadcastedDeath = false; // Folia - region threading
    protected void tickDeath() {
        ++this.deathTime;
        if (this.deathTime >= 20 && !this.level().isClientSide() && !this.isRemoved() && !this.broadcastedDeath) { // Folia - region threading
            this.level().broadcastEntityEvent(this, (byte) 60);
            this.broadcastedDeath = true; // Folia - region threading - death has been broadcasted
            if (!(this instanceof ServerPlayer)) this.remove(Entity.RemovalReason.KILLED, EntityRemoveEvent.Cause.DEATH); // CraftBukkit - add Bukkit remove cause  // Folia - region threading - don't remove, we want the tick scheduler to be running
            if ((this instanceof ServerPlayer)) this.unRide(); // Folia - region threading - unmount player when dead
        }

    }

    public boolean shouldDropExperience() {
        return !this.isBaby();
    }

    protected boolean shouldDropLoot() {
        return !this.isBaby();
    }

    protected int decreaseAirSupply(int air) {
        AttributeInstance attributemodifiable = this.getAttribute(Attributes.OXYGEN_BONUS);
        double d0;

        if (attributemodifiable != null) {
            d0 = attributemodifiable.getValue();
        } else {
            d0 = 0.0D;
        }

        return d0 > 0.0D && this.random.nextDouble() >= 1.0D / (d0 + 1.0D) ? air : air - 1;
    }

    protected int increaseAirSupply(int air) {
        return Math.min(air + 4, this.getMaxAirSupply());
    }

    public final int getExperienceReward(ServerLevel world, @Nullable Entity attacker) {
        return EnchantmentHelper.processMobExperience(world, attacker, this, this.getBaseExperienceReward(world));
    }

    protected int getBaseExperienceReward(ServerLevel world) {
        return 0;
    }

    protected boolean isAlwaysExperienceDropper() {
        return false;
    }

    @Nullable
    public LivingEntity getLastHurtByMob() {
        return this.lastHurtByMob;
    }

    @Override
    public LivingEntity getLastAttacker() {
        return this.getLastHurtByMob();
    }

    public int getLastHurtByMobTimestamp() {
        return this.lastHurtByMobTimestamp;
    }

    public void setLastHurtByPlayer(@Nullable net.minecraft.world.entity.player.Player attacking) {
        this.lastHurtByPlayer = attacking;
        this.lastHurtByPlayerTime = this.tickCount;
    }

    public void setLastHurtByMob(@Nullable LivingEntity attacker) {
        this.lastHurtByMob = attacker;
        this.lastHurtByMobTimestamp = this.tickCount;
    }

    @Nullable
    public LivingEntity getLastHurtMob() {
        return this.lastHurtMob;
    }

    public int getLastHurtMobTimestamp() {
        return this.lastHurtMobTimestamp;
    }

    public void setLastHurtMob(Entity target) {
        if (target instanceof LivingEntity) {
            this.lastHurtMob = (LivingEntity) target;
        } else {
            this.lastHurtMob = null;
        }

        this.lastHurtMobTimestamp = this.tickCount;
    }

    public int getNoActionTime() {
        return this.noActionTime;
    }

    public void setNoActionTime(int despawnCounter) {
        this.noActionTime = despawnCounter;
    }

    public boolean shouldDiscardFriction() {
        return !this.frictionState.toBooleanOrElse(!this.discardFriction); // Paper - Friction API
    }

    public void setDiscardFriction(boolean noDrag) {
        this.discardFriction = noDrag;
    }

    protected boolean doesEmitEquipEvent(EquipmentSlot slot) {
        return true;
    }

    public void onEquipItem(EquipmentSlot slot, ItemStack oldStack, ItemStack newStack) {
        // CraftBukkit start
        this.onEquipItem(slot, oldStack, newStack, false);
    }

    public void onEquipItem(EquipmentSlot enumitemslot, ItemStack itemstack, ItemStack itemstack1, boolean silent) {
        // CraftBukkit end
        if (!this.level().isClientSide() && !this.isSpectator()) {
            boolean flag = itemstack1.isEmpty() && itemstack.isEmpty();

            if (!flag && !ItemStack.isSameItemSameComponents(itemstack, itemstack1) && !this.firstTick) {
                Equippable equippable = (Equippable) itemstack1.get(DataComponents.EQUIPPABLE);

                if (!this.isSilent() && equippable != null && enumitemslot == equippable.slot() && !silent) { // CraftBukkit
                    this.level().playSeededSound((net.minecraft.world.entity.player.Player) null, this.getX(), this.getY(), this.getZ(), equippable.equipSound(), this.getSoundSource(), 1.0F, 1.0F, this.random.nextLong());
                }

                if (this.doesEmitEquipEvent(enumitemslot)) {
                    this.gameEvent(equippable != null ? GameEvent.EQUIP : GameEvent.UNEQUIP);
                }

            }
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        // CraftBukkit start - add Bukkit remove cause
        this.remove(reason, null);
    }

    @Override
    public void remove(Entity.RemovalReason entity_removalreason, EntityRemoveEvent.Cause cause) {
        // CraftBukkit end
        if (entity_removalreason == Entity.RemovalReason.KILLED || entity_removalreason == Entity.RemovalReason.DISCARDED) {
            Level world = this.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                this.triggerOnDeathMobEffects(worldserver, entity_removalreason);
            }
        }

        super.remove(entity_removalreason, cause); // CraftBukkit
        this.brain.clearMemories();
    }

    protected void triggerOnDeathMobEffects(ServerLevel world, Entity.RemovalReason reason) {
        Iterator iterator = this.getActiveEffects().iterator();

        while (iterator.hasNext()) {
            MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

            mobeffect.onMobRemoved(world, this, reason);
        }

        this.removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.DEATH); // CraftBukkit
        this.activeEffects.clear();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag nbt) {
        // Paper start - Friction API
        if (this.frictionState != net.kyori.adventure.util.TriState.NOT_SET) {
            nbt.putString("Paper.FrictionState", this.frictionState.toString());
        }
        // Paper end - Friction API
        nbt.putFloat("Health", this.getHealth());
        nbt.putShort("HurtTime", (short) this.hurtTime);
        nbt.putInt("HurtByTimestamp", this.lastHurtByMobTimestamp);
        nbt.putShort("DeathTime", (short) this.deathTime);
        nbt.putFloat("AbsorptionAmount", this.getAbsorptionAmount());
        nbt.put("attributes", this.getAttributes().save());
        if (!this.activeEffects.isEmpty()) {
            ListTag nbttaglist = new ListTag();
            Iterator iterator = this.activeEffects.values().iterator();

            while (iterator.hasNext()) {
                MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

                nbttaglist.add(mobeffect.save());
            }

            nbt.put("active_effects", nbttaglist);
        }

        nbt.putBoolean("FallFlying", this.isFallFlying());
        this.getSleepingPos().ifPresent((blockposition) -> {
            nbt.putInt("SleepingX", blockposition.getX());
            nbt.putInt("SleepingY", blockposition.getY());
            nbt.putInt("SleepingZ", blockposition.getZ());
        });
        DataResult<Tag> dataresult = this.brain.serializeStart(NbtOps.INSTANCE);
        Logger logger = LivingEntity.LOGGER;

        java.util.Objects.requireNonNull(logger);
        dataresult.resultOrPartial(logger::error).ifPresent((nbtbase) -> {
            nbt.put("Brain", nbtbase);
        });
    }

    @Override
    public void readAdditionalSaveData(CompoundTag nbt) {
        // Paper start - Check for NaN
        float absorptionAmount = nbt.getFloat("AbsorptionAmount");
        if (Float.isNaN(absorptionAmount)) {
            absorptionAmount = 0;
        }
        this.internalSetAbsorptionAmount(absorptionAmount);
        // Paper end - Check for NaN
        // Paper start - Friction API
        if (nbt.contains("Paper.FrictionState")) {
            String fs = nbt.getString("Paper.FrictionState");
            try {
                frictionState = net.kyori.adventure.util.TriState.valueOf(fs);
            } catch (Exception ignored) {
                LOGGER.error("Unknown friction state " + fs + " for " + this);
            }
        }
        // Paper end - Friction API
        if (nbt.contains("attributes", 9) && this.level() != null && !this.level().isClientSide) {
            this.getAttributes().load(nbt.getList("attributes", 10));
        }

        if (nbt.contains("active_effects", 9)) {
            ListTag nbttaglist = nbt.getList("active_effects", 10);

            for (int i = 0; i < nbttaglist.size(); ++i) {
                CompoundTag nbttagcompound1 = nbttaglist.getCompound(i);
                MobEffectInstance mobeffect = MobEffectInstance.load(nbttagcompound1);

                if (mobeffect != null) {
                    this.activeEffects.put(mobeffect.getEffect(), mobeffect);
                }
            }
        }

        // CraftBukkit start
        if (nbt.contains("Bukkit.MaxHealth")) {
            Tag nbtbase = nbt.get("Bukkit.MaxHealth");
            if (nbtbase.getId() == 5) {
                this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(((FloatTag) nbtbase).getAsDouble());
            } else if (nbtbase.getId() == 3) {
                this.getAttribute(Attributes.MAX_HEALTH).setBaseValue(((IntTag) nbtbase).getAsDouble());
            }
        }
        // CraftBukkit end

        if (nbt.contains("Health", 99)) {
            this.setHealth(nbt.getFloat("Health"));
        }

        this.hurtTime = nbt.getShort("HurtTime");
        this.deathTime = nbt.getShort("DeathTime"); this.broadcastedDeath = false; // Folia - region threading
        this.lastHurtByMobTimestamp = nbt.getInt("HurtByTimestamp");
        if (false && nbt.contains("Team", 8)) { // Folia start - region threading
            String s = nbt.getString("Team");
            Scoreboard scoreboard = this.level().getScoreboard();
            PlayerTeam scoreboardteam = scoreboard.getPlayerTeam(s);
            if (!this.level().paperConfig().scoreboards.allowNonPlayerEntitiesOnScoreboards && !(this instanceof net.minecraft.world.entity.player.Player)) { scoreboardteam = null; } // Paper - Perf: Disable Scoreboards for non players by default
            boolean flag = scoreboardteam != null && scoreboard.addPlayerToTeam(this.getStringUUID(), scoreboardteam);

            if (!flag) {
                LivingEntity.LOGGER.warn("Unable to add mob to team \"{}\" (that team probably doesn't exist)", s);
            }
        }

        if (nbt.getBoolean("FallFlying")) {
            this.setSharedFlag(7, true);
        }

        if (nbt.contains("SleepingX", 99) && nbt.contains("SleepingY", 99) && nbt.contains("SleepingZ", 99)) {
            BlockPos blockposition = new BlockPos(nbt.getInt("SleepingX"), nbt.getInt("SleepingY"), nbt.getInt("SleepingZ"));

            if (this.position().distanceToSqr(blockposition.getX(), blockposition.getY(), blockposition.getZ()) < 16 * 16) { // Paper - The sleeping pos will always also set the actual pos, so a desync suggests something is wrong
            this.setSleepingPos(blockposition);
            this.entityData.set(LivingEntity.DATA_POSE, Pose.SLEEPING);
            if (!this.firstTick) {
                this.setPosToBed(blockposition);
            }
            } // Paper - The sleeping pos will always also set the actual pos, so a desync suggests something is wrong
        }

        if (nbt.contains("Brain", 10)) {
            this.brain = this.makeBrain(new Dynamic(NbtOps.INSTANCE, nbt.get("Brain")));
        }

    }

    // CraftBukkit start
    private boolean isTickingEffects = false;
    private List<ProcessableEffect> effectsToProcess = Lists.newArrayList();

    private static class ProcessableEffect {

        private Holder<MobEffect> type;
        private MobEffectInstance effect;
        private final EntityPotionEffectEvent.Cause cause;

        private ProcessableEffect(MobEffectInstance effect, EntityPotionEffectEvent.Cause cause) {
            this.effect = effect;
            this.cause = cause;
        }

        private ProcessableEffect(Holder<MobEffect> type, EntityPotionEffectEvent.Cause cause) {
            this.type = type;
            this.cause = cause;
        }
    }
    // CraftBukkit end

    protected void tickEffects() {
        Iterator<Holder<MobEffect>> iterator = this.activeEffects.keySet().iterator();

        this.isTickingEffects = true; // CraftBukkit
        try {
            while (iterator.hasNext()) {
                Holder<MobEffect> holder = (Holder) iterator.next();
                MobEffectInstance mobeffect = (MobEffectInstance) this.activeEffects.get(holder);

                if (!mobeffect.tick(this, () -> {
                    this.onEffectUpdated(mobeffect, true, (Entity) null);
                })) {
                    if (!this.level().isClientSide) {
                        // CraftBukkit start
                        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent(this, mobeffect, null, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.EXPIRATION);
                        if (event.isCancelled()) {
                            continue;
                        }
                        // CraftBukkit end
                        iterator.remove();
                        this.onEffectsRemoved(List.of(mobeffect));
                    }
                } else if (mobeffect.getDuration() % 600 == 0) {
                    this.onEffectUpdated(mobeffect, false, (Entity) null);
                }
            }
        } catch (ConcurrentModificationException concurrentmodificationexception) {
            ;
        }
        // CraftBukkit start
        this.isTickingEffects = false;
        for (ProcessableEffect e : this.effectsToProcess) {
            if (e.effect != null) {
                this.addEffect(e.effect, e.cause);
            } else {
                this.removeEffect(e.type, e.cause);
            }
        }
        this.effectsToProcess.clear();
        // CraftBukkit end

        if (this.effectsDirty) {
            if (!this.level().isClientSide) {
                this.updateInvisibilityStatus();
                this.updateGlowingStatus();
            }

            this.effectsDirty = false;
        }

        List<ParticleOptions> list = (List) this.entityData.get(LivingEntity.DATA_EFFECT_PARTICLES);

        if (!list.isEmpty()) {
            boolean flag = (Boolean) this.entityData.get(LivingEntity.DATA_EFFECT_AMBIENCE_ID);
            int i = this.isInvisible() ? 15 : 4;
            int j = flag ? 5 : 1;

            if (this.random.nextInt(i * j) == 0) {
                this.level().addParticle((ParticleOptions) Util.getRandom(list, this.random), this.getRandomX(0.5D), this.getRandomY(), this.getRandomZ(0.5D), 1.0D, 1.0D, 1.0D);
            }
        }

    }

    protected void updateInvisibilityStatus() {
        if (this.activeEffects.isEmpty()) {
            this.removeEffectParticles();
            this.setInvisible(false);
        } else {
            this.setInvisible(this.hasEffect(MobEffects.INVISIBILITY));
            this.updateSynchronizedMobEffectParticles();
        }
    }

    private void updateSynchronizedMobEffectParticles() {
        List<ParticleOptions> list = this.activeEffects.values().stream().filter(MobEffectInstance::isVisible).map(MobEffectInstance::getParticleOptions).toList();

        this.entityData.set(LivingEntity.DATA_EFFECT_PARTICLES, list);
        this.entityData.set(LivingEntity.DATA_EFFECT_AMBIENCE_ID, LivingEntity.areAllEffectsAmbient(this.activeEffects.values()));
    }

    private void updateGlowingStatus() {
        boolean flag = this.isCurrentlyGlowing();

        if (this.getSharedFlag(6) != flag) {
            this.setSharedFlag(6, flag);
        }

    }

    public double getVisibilityPercent(@Nullable Entity entity) {
        double d0 = 1.0D;

        if (this.isDiscrete()) {
            d0 *= 0.8D;
        }

        if (this.isInvisible()) {
            float f = this.getArmorCoverPercentage();

            if (f < 0.1F) {
                f = 0.1F;
            }

            d0 *= 0.7D * (double) f;
        }

        if (entity != null) {
            ItemStack itemstack = this.getItemBySlot(EquipmentSlot.HEAD);
            EntityType<?> entitytypes = entity.getType();

            if (entitytypes == EntityType.SKELETON && itemstack.is(Items.SKELETON_SKULL) || entitytypes == EntityType.ZOMBIE && itemstack.is(Items.ZOMBIE_HEAD) || entitytypes == EntityType.PIGLIN && itemstack.is(Items.PIGLIN_HEAD) || entitytypes == EntityType.PIGLIN_BRUTE && itemstack.is(Items.PIGLIN_HEAD) || entitytypes == EntityType.CREEPER && itemstack.is(Items.CREEPER_HEAD)) {
                d0 *= 0.5D;
            }
        }

        return d0;
    }

    public boolean canAttack(LivingEntity target) {
        return target instanceof net.minecraft.world.entity.player.Player && this.level().getDifficulty() == Difficulty.PEACEFUL ? false : target.canBeSeenAsEnemy();
    }

    public boolean canBeSeenAsEnemy() {
        return !this.isInvulnerable() && this.canBeSeenByAnyone();
    }

    public boolean canBeSeenByAnyone() {
        return !this.isSpectator() && this.isAlive();
    }

    public static boolean areAllEffectsAmbient(Collection<MobEffectInstance> effects) {
        Iterator iterator = effects.iterator();

        MobEffectInstance mobeffect;

        do {
            if (!iterator.hasNext()) {
                return true;
            }

            mobeffect = (MobEffectInstance) iterator.next();
        } while (!mobeffect.isVisible() || mobeffect.isAmbient());

        return false;
    }

    protected void removeEffectParticles() {
        this.entityData.set(LivingEntity.DATA_EFFECT_PARTICLES, List.of());
    }

    // CraftBukkit start
    public boolean removeAllEffects() {
        return this.removeAllEffects(org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN);
    }

    public boolean removeAllEffects(EntityPotionEffectEvent.Cause cause) {
        // CraftBukkit end
        if (this.level().isClientSide) {
            return false;
        } else if (this.activeEffects.isEmpty()) {
            return false;
        } else {
            // CraftBukkit start
            List<MobEffectInstance> toRemove = new LinkedList<>();
            Iterator<MobEffectInstance> iterator = this.activeEffects.values().iterator();

            while (iterator.hasNext()) {
                MobEffectInstance effect = iterator.next();
                EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent(this, effect, null, cause, EntityPotionEffectEvent.Action.CLEARED);
                if (event.isCancelled()) {
                    continue;
                }

                iterator.remove();
                toRemove.add(effect);
            }

            this.onEffectsRemoved(toRemove);
            return !toRemove.isEmpty();
            // CraftBukkit end
        }
    }

    public Collection<MobEffectInstance> getActiveEffects() {
        return this.activeEffects.values();
    }

    public Map<Holder<MobEffect>, MobEffectInstance> getActiveEffectsMap() {
        return this.activeEffects;
    }

    public boolean hasEffect(Holder<MobEffect> effect) {
        return this.activeEffects.containsKey(effect);
    }

    @Nullable
    public MobEffectInstance getEffect(Holder<MobEffect> effect) {
        return (MobEffectInstance) this.activeEffects.get(effect);
    }

    public final boolean addEffect(MobEffectInstance effect) {
        return this.addEffect(effect, (Entity) null);
    }

    // CraftBukkit start
    public boolean addEffect(MobEffectInstance mobeffect, EntityPotionEffectEvent.Cause cause) {
        return this.addEffect(mobeffect, (Entity) null, cause);
    }

    public boolean addEffect(MobEffectInstance effect, @Nullable Entity source) {
        return this.addEffect(effect, source, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN);
    }

    public boolean addEffect(MobEffectInstance mobeffect, @Nullable Entity entity, EntityPotionEffectEvent.Cause cause) {
        // Paper start - Don't fire sync event during generation
        return this.addEffect(mobeffect, entity, cause, true);
    }
    public boolean addEffect(MobEffectInstance mobeffect, @Nullable Entity entity, EntityPotionEffectEvent.Cause cause, boolean fireEvent) {
        // Paper end - Don't fire sync event during generation
        // org.spigotmc.AsyncCatcher.catchOp("effect add"); // Spigot // Paper - move to API
        if (!this.hasNullCallback()) ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(this, "Cannot add effects to entities asynchronously"); // Folia - region threading
        if (this.isTickingEffects) {
            this.effectsToProcess.add(new ProcessableEffect(mobeffect, cause));
            return true;
        }
        // CraftBukkit end

        if (!this.canBeAffected(mobeffect)) {
            return false;
        } else {
            MobEffectInstance mobeffect1 = (MobEffectInstance) this.activeEffects.get(mobeffect.getEffect());
            boolean flag = false;

            // CraftBukkit start
            boolean override = false;
            if (mobeffect1 != null) {
                override = new MobEffectInstance(mobeffect1).update(mobeffect);
            }

            if (fireEvent) { // Paper - Don't fire sync event during generation
            EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent(this, mobeffect1, mobeffect, cause, override);
            override = event.isOverride(); // Paper - Don't fire sync event during generation
            if (event.isCancelled()) {
                return false;
            }
            } // Paper - Don't fire sync event during generation
            // CraftBukkit end

            if (mobeffect1 == null) {
                this.activeEffects.put(mobeffect.getEffect(), mobeffect);
                this.onEffectAdded(mobeffect, entity);
                flag = true;
                mobeffect.onEffectAdded(this);
                // CraftBukkit start
            } else if (override) { // Paper - Don't fire sync event during generation
                mobeffect1.update(mobeffect);
                this.onEffectUpdated(mobeffect1, true, entity);
                // CraftBukkit end
                flag = true;
            }

            mobeffect.onEffectStarted(this);
            return flag;
        }
    }

    public boolean canBeAffected(MobEffectInstance effect) {
        return this.getType().is(EntityTypeTags.IMMUNE_TO_INFESTED) ? !effect.is(MobEffects.INFESTED) : (this.getType().is(EntityTypeTags.IMMUNE_TO_OOZING) ? !effect.is(MobEffects.OOZING) : (!this.getType().is(EntityTypeTags.IGNORES_POISON_AND_REGEN) ? true : !effect.is(MobEffects.REGENERATION) && !effect.is(MobEffects.POISON)));
    }

    public void forceAddEffect(MobEffectInstance effect, @Nullable Entity source) {
        if (this.canBeAffected(effect)) {
            MobEffectInstance mobeffect1 = (MobEffectInstance) this.activeEffects.put(effect.getEffect(), effect);

            if (mobeffect1 == null) {
                this.onEffectAdded(effect, source);
            } else {
                effect.copyBlendState(mobeffect1);
                this.onEffectUpdated(effect, true, source);
            }

        }
    }

    public boolean isInvertedHealAndHarm() {
        return this.getType().is(EntityTypeTags.INVERTED_HEALING_AND_HARM);
    }

    // CraftBukkit start
    @Nullable
    public MobEffectInstance removeEffectNoUpdate(Holder<MobEffect> effect) {
        return this.removeEffectNoUpdate(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN);
    }

    @Nullable
    public MobEffectInstance removeEffectNoUpdate(Holder<MobEffect> holder, EntityPotionEffectEvent.Cause cause) {
        if (this.isTickingEffects) {
            this.effectsToProcess.add(new ProcessableEffect(holder, cause));
            return null;
        }

        MobEffectInstance effect = this.activeEffects.get(holder);
        if (effect == null) {
            return null;
        }

        EntityPotionEffectEvent event = CraftEventFactory.callEntityPotionEffectChangeEvent(this, effect, null, cause);
        if (event.isCancelled()) {
            return null;
        }

        return (MobEffectInstance) this.activeEffects.remove(holder);
    }

    public boolean removeEffect(Holder<MobEffect> effect) {
        return this.removeEffect(effect, org.bukkit.event.entity.EntityPotionEffectEvent.Cause.UNKNOWN);
    }

    public boolean removeEffect(Holder<MobEffect> holder, EntityPotionEffectEvent.Cause cause) {
        MobEffectInstance mobeffect = this.removeEffectNoUpdate(holder, cause);
        // CraftBukkit end

        if (mobeffect != null) {
            this.onEffectsRemoved(List.of(mobeffect));
            return true;
        } else {
            return false;
        }
    }

    protected void onEffectAdded(MobEffectInstance effect, @Nullable Entity source) {
        this.effectsDirty = true;
        if (!this.level().isClientSide) {
            ((MobEffect) effect.getEffect().value()).addAttributeModifiers(this.getAttributes(), effect.getAmplifier());
            this.sendEffectToPassengers(effect);
        }

    }

    public void sendEffectToPassengers(MobEffectInstance effect) {
        Iterator iterator = this.getPassengers().iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();

            if (entity instanceof ServerPlayer entityplayer) {
                entityplayer.connection.send(new ClientboundUpdateMobEffectPacket(this.getId(), effect, false));
            }
        }

    }

    protected void onEffectUpdated(MobEffectInstance effect, boolean reapplyEffect, @Nullable Entity source) {
        this.effectsDirty = true;
        if (reapplyEffect && !this.level().isClientSide) {
            MobEffect mobeffectlist = (MobEffect) effect.getEffect().value();

            mobeffectlist.removeAttributeModifiers(this.getAttributes());
            mobeffectlist.addAttributeModifiers(this.getAttributes(), effect.getAmplifier());
            this.refreshDirtyAttributes();
        }

        if (!this.level().isClientSide) {
            this.sendEffectToPassengers(effect);
        }

    }

    protected void onEffectsRemoved(Collection<MobEffectInstance> effects) {
        this.effectsDirty = true;
        if (!this.level().isClientSide) {
            Iterator iterator = effects.iterator();

            while (iterator.hasNext()) {
                MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

                ((MobEffect) mobeffect.getEffect().value()).removeAttributeModifiers(this.getAttributes());
                Iterator iterator1 = this.getPassengers().iterator();

                while (iterator1.hasNext()) {
                    Entity entity = (Entity) iterator1.next();

                    if (entity instanceof ServerPlayer) {
                        ServerPlayer entityplayer = (ServerPlayer) entity;

                        entityplayer.connection.send(new ClientboundRemoveMobEffectPacket(this.getId(), mobeffect.getEffect()));
                    }
                }
            }

            this.refreshDirtyAttributes();
        }

    }

    private void refreshDirtyAttributes() {
        Set<AttributeInstance> set = this.getAttributes().getAttributesToUpdate();
        Iterator iterator = set.iterator();

        while (iterator.hasNext()) {
            AttributeInstance attributemodifiable = (AttributeInstance) iterator.next();

            this.onAttributeUpdated(attributemodifiable.getAttribute());
        }

        set.clear();
    }

    protected void onAttributeUpdated(Holder<Attribute> attribute) {
        float f;

        if (attribute.is(Attributes.MAX_HEALTH)) {
            f = this.getMaxHealth();
            if (this.getHealth() > f) {
                this.setHealth(f);
            }
        } else if (attribute.is(Attributes.MAX_ABSORPTION)) {
            f = this.getMaxAbsorption();
            if (this.getAbsorptionAmount() > f) {
                this.setAbsorptionAmount(f);
            }
        }

    }

    // CraftBukkit start - Delegate so we can handle providing a reason for health being regained
    public void heal(float amount) {
        this.heal(amount, EntityRegainHealthEvent.RegainReason.CUSTOM);
    }

    public void heal(float f, EntityRegainHealthEvent.RegainReason regainReason) {
        // Paper start - Forward
        heal(f, regainReason, false);
    }

    public void heal(float f, EntityRegainHealthEvent.RegainReason regainReason, boolean isFastRegen) {
        // Paper end
        float f1 = this.getHealth();

        if (f1 > 0.0F) {
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(this.getBukkitEntity(), f, regainReason, isFastRegen); // Paper
            // Suppress during worldgen
            if (this.valid) {
                this.level().getCraftServer().getPluginManager().callEvent(event);
            }

            if (!event.isCancelled()) {
                this.setHealth((float) (this.getHealth() + event.getAmount()));
            }
            // CraftBukkit end
        }

    }

    public float getHealth() {
        // CraftBukkit start - Use unscaled health
        if (this instanceof ServerPlayer) {
            return (float) ((ServerPlayer) this).getBukkitEntity().getHealth();
        }
        // CraftBukkit end
        return (Float) this.entityData.get(LivingEntity.DATA_HEALTH_ID);
    }

    public void setHealth(float health) {
        // Paper start - Check for NaN
        if (Float.isNaN(health)) { health = getMaxHealth(); if (this.valid) {
            System.err.println("[NAN-HEALTH] " + getScoreboardName() + " had NaN health set");
        } } // Paper end - Check for NaN
        // CraftBukkit start - Handle scaled health
        if (this instanceof ServerPlayer) {
            org.bukkit.craftbukkit.entity.CraftPlayer player = ((ServerPlayer) this).getBukkitEntity();
            // Squeeze
            if (health < 0.0F) {
                player.setRealHealth(0.0D);
            } else if (health > player.getMaxHealth()) {
                player.setRealHealth(player.getMaxHealth());
            } else {
                player.setRealHealth(health);
            }

            player.updateScaledHealth(false);
            return;
        }
        // CraftBukkit end
        this.entityData.set(LivingEntity.DATA_HEALTH_ID, Mth.clamp(health, 0.0F, this.getMaxHealth()));
    }

    public boolean isDeadOrDying() {
        return this.getHealth() <= 0.0F;
    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (this.isInvulnerableTo(world, source)) {
            return false;
        } else if (this.isRemoved() || this.dead || this.getHealth() <= 0.0F) { // CraftBukkit - Don't allow entities that got set to dead/killed elsewhere to get damaged and die
            return false;
        } else if (source.is(DamageTypeTags.IS_FIRE) && this.hasEffect(MobEffects.FIRE_RESISTANCE)) {
            return false;
        } else {
            if (this.isSleeping()) {
                this.stopSleeping();
            }

            this.noActionTime = 0;
            if (amount < 0.0F) {
                amount = 0.0F;
            }

            float f1 = amount; final float originalAmount = f1; // Paper - revert to vanilla #hurt - OBFHELPER
            boolean flag = amount > 0.0F && this.isDamageSourceBlocked(source); // Copied from below
            float f2 = 0.0F;

            // CraftBukkit - Moved into handleEntityDamage(DamageSource, float) for get f and actuallyHurt(DamageSource, float, EntityDamageEvent) for handle damage
            if (false && amount > 0.0F && this.isDamageSourceBlocked(source)) {
                this.hurtCurrentlyUsedShield(amount);
                f2 = amount;
                amount = 0.0F;
                if (!source.is(DamageTypeTags.IS_PROJECTILE)) {
                    Entity entity = source.getDirectEntity();

                    if (entity instanceof LivingEntity && entity.distanceToSqr(this) <= (200.0D * 200.0D)) { // Paper - Check distance in entity interactions
                        LivingEntity entityliving = (LivingEntity) entity;

                        this.blockUsingShield(entityliving);
                    }
                }

                flag = true;
            }

            // CraftBukkit - Moved into handleEntityDamage(DamageSource, float) for get f
            if (false && source.is(DamageTypeTags.IS_FREEZING) && this.getType().is(EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES)) {
                amount *= 5.0F;
            }

            // CraftBukkit - Moved into handleEntityDamage(DamageSource, float) for get f and actuallyHurt(DamageSource, float, EntityDamageEvent) for handle damage
            if (false && source.is(DamageTypeTags.DAMAGES_HELMET) && !this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                this.hurtHelmet(source, amount);
                amount *= 0.75F;
            }

            // CraftBukkit start
            EntityDamageEvent event; // Paper - move this into the actual invuln check....
            // CraftBukkit end

            this.walkAnimation.setSpeed(1.5F);
            if (Float.isNaN(amount) || Float.isInfinite(amount)) {
                amount = Float.MAX_VALUE;
            }

            boolean flag1 = true;

            if ((float) this.invulnerableTime > (float) this.invulnerableDuration / 2.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) { // CraftBukkit - restore use of maxNoDamageTicks
                if (amount <= this.lastHurt) {
                    return false;
                }

                // Paper start - only call damage event when actuallyHurt will be called - move call logic down
                event = this.handleEntityDamage(source, amount, this.lastHurt); // Paper - fix invulnerability reduction in EntityDamageEvent - pass lastDamage reduction
                amount = computeAmountFromEntityDamageEvent(event);
                // Paper end - only call damage event when actuallyHurt will be called - move call logic down

                // CraftBukkit start
                if (!this.actuallyHurt(world, source, (float) event.getFinalDamage(), event)) { // Paper - fix invulnerability reduction in EntityDamageEvent - no longer subtract lastHurt, that is part of the damage event calc now
                    return false;
                }
                if (this instanceof ServerPlayer && event.getDamage() == 0 && originalAmount == 0) return false; // Paper - revert to vanilla damage - players are not affected by damage that is 0 - skip damage if the vanilla damage is 0 and was not modified by plugins in the event.
                // CraftBukkit end
                this.lastHurt = amount;
                flag1 = false;
            } else {
                // Paper start - only call damage event when actuallyHurt will be called - move call logic down
                event = this.handleEntityDamage(source, amount, 0); // Paper - fix invulnerability reduction in EntityDamageEvent - pass lastDamage reduction (none in this branch)
                amount = computeAmountFromEntityDamageEvent(event);
                // Paper end - only call damage event when actuallyHurt will be called - move call logic down
                // CraftBukkit start
                if (!this.actuallyHurt(world, source, (float) event.getFinalDamage(), event)) {
                    return false;
                }
                if (this instanceof ServerPlayer && event.getDamage() == 0 && originalAmount == 0) return false; // Paper - revert to vanilla damage - players are not affected by damage that is 0 - skip damage if the vanilla damage is 0 and was not modified by plugins in the event.
                this.lastHurt = amount;
                this.invulnerableTime = this.invulnerableDuration; // CraftBukkit - restore use of maxNoDamageTicks
                // this.actuallyHurt(worldserver, damagesource, f);
                // CraftBukkit end
                this.hurtDuration = 10;
                this.hurtTime = this.hurtDuration;
            }

            this.resolveMobResponsibleForDamage(source);
            this.resolvePlayerResponsibleForDamage(source);
            if (flag1) {
                if (flag) {
                    world.broadcastEntityEvent(this, (byte) 29);
                } else {
                    world.broadcastDamageEvent(this, source);
                }

                if (!source.is(DamageTypeTags.NO_IMPACT) && !flag) { // CraftBukkit - Prevent marking hurt if the damage is blocked
                    this.markHurt();
                }

                if (!source.is(DamageTypeTags.NO_KNOCKBACK)) {
                    double d0 = 0.0D;
                    double d1 = 0.0D;
                    Entity entity1 = source.getDirectEntity();

                    if (entity1 instanceof Projectile) {
                        Projectile iprojectile = (Projectile) entity1;
                        DoubleDoubleImmutablePair doubledoubleimmutablepair = iprojectile.calculateHorizontalHurtKnockbackDirection(this, source);

                        d0 = -doubledoubleimmutablepair.leftDouble();
                        d1 = -doubledoubleimmutablepair.rightDouble();
                    } else if (source.getSourcePosition() != null) {
                        d0 = source.getSourcePosition().x() - this.getX();
                        d1 = source.getSourcePosition().z() - this.getZ();
                    }
                    // Paper start - Check distance in entity interactions; see for loop in knockback method
                    if (Math.abs(d0) > 200) {
                        d0 = Math.random() - Math.random();
                    }
                    if (Math.abs(d1) > 200) {
                        d1 = Math.random() - Math.random();
                    }
                    // Paper end - Check distance in entity interactions

                    this.knockback(0.4000000059604645D, d0, d1, entity1, entity1 == null ? io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.DAMAGE : io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.ENTITY_ATTACK); // CraftBukkit // Paper - knockback events
                    if (!flag) {
                        this.indicateDamage(d0, d1);
                    }
                }
            }

            if (this.isDeadOrDying()) {
                if (!this.checkTotemDeathProtection(source)) {
                    // Paper start - moved into CraftEventFactory event caller for cancellable death event
                    this.silentDeath = !flag1; // mark entity as dying silently
                    // Paper end

                    this.die(source);
                    this.silentDeath = false; // Paper - cancellable death event - reset to default
                }
            } else if (flag1) {
                this.playHurtSound(source);
            }

            boolean flag2 = !flag; // CraftBukkit - Ensure to return false if damage is blocked

            if (flag2) {
                this.lastDamageSource = source;
                this.lastDamageStamp = this.level().getRedstoneGameTime(); // Folia - region threading
                Iterator iterator = this.getActiveEffects().iterator();

                while (iterator.hasNext()) {
                    MobEffectInstance mobeffect = (MobEffectInstance) iterator.next();

                    mobeffect.onMobHurt(world, this, source, amount);
                }
            }

            ServerPlayer entityplayer;

            if (this instanceof ServerPlayer) {
                entityplayer = (ServerPlayer) this;
                CriteriaTriggers.ENTITY_HURT_PLAYER.trigger(entityplayer, source, f1, amount, flag);
                if (f2 > 0.0F && f2 < 3.4028235E37F) {
                    entityplayer.awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(f2 * 10.0F));
                }
            }

            Entity entity2 = source.getEntity();

            if (entity2 instanceof ServerPlayer) {
                entityplayer = (ServerPlayer) entity2;
                CriteriaTriggers.PLAYER_HURT_ENTITY.trigger(entityplayer, this, source, f1, amount, flag);
            }

            return flag2;
        }
    }

    protected void resolveMobResponsibleForDamage(DamageSource damageSource) {
        Entity entity = damageSource.getEntity();

        if (entity instanceof LivingEntity entityliving) {
            if (!damageSource.is(DamageTypeTags.NO_ANGER) && (!damageSource.is(DamageTypes.WIND_CHARGE) || !this.getType().is(EntityTypeTags.NO_ANGER_FROM_WIND_CHARGE))) {
                this.setLastHurtByMob(entityliving);
            }
        }

    }

    @Nullable
    protected net.minecraft.world.entity.player.Player resolvePlayerResponsibleForDamage(DamageSource damageSource) {
        Entity entity = damageSource.getEntity();

        if (entity instanceof net.minecraft.world.entity.player.Player entityhuman) {
            this.lastHurtByPlayerTime = 100;
            this.lastHurtByPlayer = entityhuman;
            return entityhuman;
        } else {
            if (entity instanceof Wolf entitywolf) {
                if (entitywolf.isTame()) {
                    this.lastHurtByPlayerTime = 100;
                    LivingEntity entityliving = entitywolf.getOwner();

                    if (entityliving instanceof net.minecraft.world.entity.player.Player) {
                        net.minecraft.world.entity.player.Player entityhuman1 = (net.minecraft.world.entity.player.Player) entityliving;

                        this.lastHurtByPlayer = entityhuman1;
                    } else {
                        this.lastHurtByPlayer = null;
                    }

                    return this.lastHurtByPlayer;
                }
            }

            return null;
        }
    }

    // Paper start - only call damage event when actuallyHurt will be called - move out amount computation logic
    private float computeAmountFromEntityDamageEvent(final EntityDamageEvent event) {
        // Taken from hurt()'s craftbukkit diff.
        float amount = 0;
        amount += (float) event.getDamage(DamageModifier.BASE);
        amount += (float) event.getDamage(DamageModifier.BLOCKING);
        amount += (float) event.getDamage(DamageModifier.FREEZING);
        amount += (float) event.getDamage(DamageModifier.HARD_HAT);
        return amount;
    }
    // Paper end - only call damage event when actuallyHurt will be called - move out amount computation logic

    protected void blockUsingShield(LivingEntity attacker) {
        attacker.blockedByShield(this);
    }

    protected void blockedByShield(LivingEntity target) {
        target.knockback(0.5D, target.getX() - this.getX(), target.getZ() - this.getZ(), this, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.SHIELD_BLOCK); // CraftBukkit // Paper - fix attacker & knockback events
    }

    private boolean checkTotemDeathProtection(DamageSource source) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return false;
        } else {
            ItemStack itemstack = null;
            DeathProtection deathprotection = null;
            InteractionHand[] aenumhand = InteractionHand.values();
            int i = aenumhand.length;

            // CraftBukkit start
            InteractionHand hand = null;
            ItemStack itemstack1 = ItemStack.EMPTY;
            for (int j = 0; j < i; ++j) {
                InteractionHand enumhand = aenumhand[j];
                itemstack1 = this.getItemInHand(enumhand);

                deathprotection = (DeathProtection) itemstack1.get(DataComponents.DEATH_PROTECTION);
                if (deathprotection != null) {
                    hand = enumhand; // CraftBukkit
                    itemstack = itemstack1.copy();
                    // itemstack1.subtract(1); // CraftBukkit
                    break;
                }
            }

            org.bukkit.inventory.EquipmentSlot handSlot = (hand != null) ? org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(hand) : null;
            EntityResurrectEvent event = new EntityResurrectEvent((org.bukkit.entity.LivingEntity) this.getBukkitEntity(), handSlot);
            event.setCancelled(itemstack == null);
            this.level().getCraftServer().getPluginManager().callEvent(event);

            if (!event.isCancelled()) {
                if (!itemstack1.isEmpty() && itemstack != null) { // Paper - only reduce item if actual totem was found
                    itemstack1.shrink(1);
                }
                // Paper start - fix NPE when pre-cancelled EntityResurrectEvent is uncancelled
                // restore the previous behavior in that case by defaulting to vanillas totem of undying efect
                if (deathprotection == null) {
                    deathprotection = DeathProtection.TOTEM_OF_UNDYING;
                }
                // Paper end - fix NPE when pre-cancelled EntityResurrectEvent is uncancelled
                if (itemstack != null && this instanceof ServerPlayer) {
                    // CraftBukkit end
                    ServerPlayer entityplayer = (ServerPlayer) this;

                    entityplayer.awardStat(Stats.ITEM_USED.get(itemstack.getItem()));
                    CriteriaTriggers.USED_TOTEM.trigger(entityplayer, itemstack);
                    this.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
                }

                this.setHealth(1.0F);
                deathprotection.applyEffects(itemstack, this);
                this.level().broadcastEntityEvent(this, (byte) 35);
            }

            return deathprotection != null;
        }
    }

    @Nullable
    public DamageSource getLastDamageSource() {
        if (this.level().getRedstoneGameTime() - this.lastDamageStamp > 40L || this.lastDamageStamp == Long.MIN_VALUE) { // Folia - region threading
            this.lastDamageSource = null;
        }

        return this.lastDamageSource;
    }

    protected void playHurtSound(DamageSource damageSource) {
        this.makeSound(this.getHurtSound(damageSource));
    }

    public void makeSound(@Nullable SoundEvent sound) {
        if (sound != null) {
            this.playSound(sound, this.getSoundVolume(), this.getVoicePitch());
        }

    }

    public boolean isDamageSourceBlocked(DamageSource source) {
        Entity entity = source.getDirectEntity();
        boolean flag = false;

        if (entity instanceof AbstractArrow entityarrow) {
            if (entityarrow.getPierceLevel() > 0) {
                flag = true;
            }
        }

        ItemStack itemstack = this.getItemBlockingWith();

        if (!source.is(DamageTypeTags.BYPASSES_SHIELD) && itemstack != null && itemstack.getItem() instanceof ShieldItem && !flag) {
            Vec3 vec3d = source.getSourcePosition();

            if (vec3d != null) {
                Vec3 vec3d1 = this.calculateViewVector(0.0F, this.getYHeadRot());
                Vec3 vec3d2 = vec3d.vectorTo(this.position());

                vec3d2 = (new Vec3(vec3d2.x, 0.0D, vec3d2.z)).normalize();
                return vec3d2.dot(vec3d1) < 0.0D;
            }
        }

        return false;
    }

    private void breakItem(ItemStack stack) {
        if (!stack.isEmpty()) {
            if (!this.isSilent()) {
                this.level().playLocalSound(this.getX(), this.getY(), this.getZ(), stack.getBreakingSound(), this.getSoundSource(), 0.8F, 0.8F + this.level().random.nextFloat() * 0.4F, false);
            }

            this.spawnItemParticles(stack, 5);
        }

    }

    public void die(DamageSource damageSource) {
        if (!this.isRemoved() && !this.dead) {
            Entity entity = damageSource.getEntity();
            LivingEntity entityliving = this.getKillCredit();

            /* // Paper - move down to make death event cancellable - this is the awardKillScore below
            if (entityliving != null) {
                entityliving.awardKillScore(this, damageSource);
            }

            if (this.isSleeping()) {
                this.stopSleeping();
            }

            if (!this.level().isClientSide && this.hasCustomName()) {
                if (org.spigotmc.SpigotConfig.logNamedDeaths) LivingEntity.LOGGER.info("Named entity {} died: {}", this, this.getCombatTracker().getDeathMessage().getString()); // Spigot
            }
            */ // Paper - move down to make death event cancellable - this is the awardKillScore below

            this.dead = true;
            // Paper - moved into if below
            Level world = this.level();

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;
                // Paper - move below into if for onKill

                // Paper start
                org.bukkit.event.entity.EntityDeathEvent deathEvent = this.dropAllDeathLoot(worldserver, damageSource);
                if (deathEvent == null || !deathEvent.isCancelled()) {
                    //if (entityliving != null) { // Paper - Fix item duplication and teleport issues; moved to be run earlier in #dropAllDeathLoot before destroying the drop items in CraftEventFactory#callEntityDeathEvent
                    //    entityliving.awardKillScore(this, damageSource);
                    //}
                    // Paper start - clear equipment if event is not cancelled
                    if (this instanceof Mob) {
                        for (EquipmentSlot slot : this.clearedEquipmentSlots) {
                            this.setItemSlot(slot, ItemStack.EMPTY);
                        }
                        this.clearedEquipmentSlots.clear();
                    }
                    // Paper end

                    if (this.isSleeping()) {
                        this.stopSleeping();
                    }

                    if (!this.level().isClientSide && this.hasCustomName()) {
                        if (org.spigotmc.SpigotConfig.logNamedDeaths) LivingEntity.LOGGER.info("Named entity {} died: {}", this, this.getCombatTracker().getDeathMessage().getString()); // Spigot
                    }

                    this.getCombatTracker().recheckStatus();
                    if (entity != null) {
                        entity.killedEntity((ServerLevel) this.level(), this);
                    }
                    this.gameEvent(GameEvent.ENTITY_DIE);
                } else {
                    this.dead = false;
                    this.setHealth((float) deathEvent.getReviveHealth());
                }
                // Paper end
                    this.createWitherRose(entityliving);
                }

            // Paper start
            if (this.dead) { // Paper
                this.level().broadcastEntityEvent(this, (byte) 3);

            this.setPose(Pose.DYING);
            }
            // Paper end
        }
    }

    protected void createWitherRose(@Nullable LivingEntity adversary) {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            boolean flag = false;

            if (this.dead && adversary instanceof WitherBoss) { // Paper
                if (worldserver.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
                    BlockPos blockposition = this.blockPosition();
                    BlockState iblockdata = Blocks.WITHER_ROSE.defaultBlockState();

                    if (this.level().getBlockState(blockposition).isAir() && iblockdata.canSurvive(this.level(), blockposition)) {
                        // CraftBukkit start - call EntityBlockFormEvent for Wither Rose
                        flag = org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this.level(), blockposition, iblockdata, 3, this);
                        // CraftBukkit end
                    }
                }

                if (!flag) {
                    ItemEntity entityitem = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), new ItemStack(Items.WITHER_ROSE));

                    // CraftBukkit start
                    org.bukkit.event.entity.EntityDropItemEvent event = new org.bukkit.event.entity.EntityDropItemEvent(this.getBukkitEntity(), (org.bukkit.entity.Item) entityitem.getBukkitEntity());
                    CraftEventFactory.callEvent(event);
                    if (event.isCancelled()) {
                        return;
                    }
                    // CraftBukkit end
                    this.level().addFreshEntity(entityitem);
                }
            }

        }
    }

    // Paper start
    protected boolean clearEquipmentSlots = true;
    protected Set<EquipmentSlot> clearedEquipmentSlots = new java.util.HashSet<>();
    protected org.bukkit.event.entity.EntityDeathEvent dropAllDeathLoot(ServerLevel world, DamageSource damageSource) {
        // Paper end
        boolean flag = this.lastHurtByPlayerTime > 0;

        this.dropEquipment(world); // CraftBukkit - from below
        if (this.shouldDropLoot() && world.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) {
            this.dropFromLootTable(world, damageSource, flag);
            // Paper start
            final boolean prev = this.clearEquipmentSlots;
            this.clearEquipmentSlots = false;
            this.clearedEquipmentSlots.clear();
            // Paper end
            this.dropCustomDeathLoot(world, damageSource, flag);
            this.clearEquipmentSlots = prev; // Paper
        }
        // CraftBukkit start - Call death event // Paper start - call advancement triggers with correct entity equipment
        org.bukkit.event.entity.EntityDeathEvent deathEvent = CraftEventFactory.callEntityDeathEvent(this, damageSource, this.drops, () -> {
            final LivingEntity entityliving = this.getKillCredit();
            if (entityliving != null) {
                entityliving.awardKillScore(this, damageSource);
            }
        }); // Paper end
        this.postDeathDropItems(deathEvent); // Paper
        this.drops = new ArrayList<>();
        // CraftBukkit end

        // this.dropEquipment(worldserver);// CraftBukkit - moved up
        this.dropExperience(world, damageSource.getEntity());
        return deathEvent; // Paper
    }

    protected void dropEquipment(ServerLevel world) {}
    protected void postDeathDropItems(org.bukkit.event.entity.EntityDeathEvent event) {} // Paper - method for post death logic that cannot be ran before the event is potentially cancelled

    public int getExpReward(ServerLevel worldserver, @Nullable Entity entity) { // CraftBukkit
        if (!this.wasExperienceConsumed() && (this.isAlwaysExperienceDropper() || this.lastHurtByPlayerTime > 0 && this.shouldDropExperience() && worldserver.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT))) {
            return this.getExperienceReward(worldserver, entity); // CraftBukkit            }
        }

        return 0; // CraftBukkit
    }

    protected void dropExperience(ServerLevel world, @Nullable Entity attacker) {
        // CraftBukkit start - Update getExpReward() above if the removed if() changes!
        if (!(this instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon)) { // CraftBukkit - SPIGOT-2420: Special case ender dragon will drop the xp over time
            ExperienceOrb.award(world, this.position(), this.expToDrop, this instanceof ServerPlayer ? org.bukkit.entity.ExperienceOrb.SpawnReason.PLAYER_DEATH : org.bukkit.entity.ExperienceOrb.SpawnReason.ENTITY_DEATH, attacker, this); // Paper
            this.expToDrop = 0;
        }
        // CraftBukkit end
    }

    protected void dropCustomDeathLoot(ServerLevel world, DamageSource source, boolean causedByPlayer) {}

    public long getLootTableSeed() {
        return 0L;
    }

    protected float getKnockback(Entity target, DamageSource damageSource) {
        float f = (float) this.getAttributeValue(Attributes.ATTACK_KNOCKBACK);
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            return EnchantmentHelper.modifyKnockback(worldserver, this.getWeaponItem(), target, damageSource, f);
        } else {
            return f;
        }
    }

    protected void dropFromLootTable(ServerLevel world, DamageSource damageSource, boolean causedByPlayer) {
        Optional<ResourceKey<LootTable>> optional = this.getLootTable();

        if (!optional.isEmpty()) {
            LootTable loottable = world.getServer().reloadableRegistries().getLootTable((ResourceKey) optional.get());
            LootParams.Builder lootparams_a = (new LootParams.Builder(world)).withParameter(LootContextParams.THIS_ENTITY, this).withParameter(LootContextParams.ORIGIN, this.position()).withParameter(LootContextParams.DAMAGE_SOURCE, damageSource).withOptionalParameter(LootContextParams.ATTACKING_ENTITY, damageSource.getEntity()).withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, damageSource.getDirectEntity());

            if (causedByPlayer && this.lastHurtByPlayer != null) {
                lootparams_a = lootparams_a.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, this.lastHurtByPlayer).withLuck(this.lastHurtByPlayer.getLuck());
            }

            LootParams lootparams = lootparams_a.create(LootContextParamSets.ENTITY);

            loottable.getRandomItems(lootparams, this.getLootTableSeed(), (itemstack) -> {
                this.spawnAtLocation(world, itemstack);
            });
        }
    }

    public boolean dropFromGiftLootTable(ServerLevel world, ResourceKey<LootTable> lootTableKey, BiConsumer<ServerLevel, ItemStack> lootConsumer) {
        return this.dropFromLootTable(world, lootTableKey, (lootparams_a) -> {
            return lootparams_a.withParameter(LootContextParams.ORIGIN, this.position()).withParameter(LootContextParams.THIS_ENTITY, this).create(LootContextParamSets.GIFT);
        }, lootConsumer);
    }

    protected void dropFromShearingLootTable(ServerLevel world, ResourceKey<LootTable> lootTableKey, ItemStack tool, BiConsumer<ServerLevel, ItemStack> lootConsumer) {
        this.dropFromLootTable(world, lootTableKey, (lootparams_a) -> {
            return lootparams_a.withParameter(LootContextParams.ORIGIN, this.position()).withParameter(LootContextParams.THIS_ENTITY, this).withParameter(LootContextParams.TOOL, tool).create(LootContextParamSets.SHEARING);
        }, lootConsumer);
    }

    protected boolean dropFromLootTable(ServerLevel world, ResourceKey<LootTable> lootTableKey, Function<LootParams.Builder, LootParams> lootContextParametersFactory, BiConsumer<ServerLevel, ItemStack> lootConsumer) {
        LootTable loottable = world.getServer().reloadableRegistries().getLootTable(lootTableKey);
        LootParams lootparams = (LootParams) lootContextParametersFactory.apply(new LootParams.Builder(world));
        List<ItemStack> list = loottable.getRandomItems(lootparams);

        if (!list.isEmpty()) {
            list.forEach((itemstack) -> {
                lootConsumer.accept(world, itemstack);
            });
            return true;
        } else {
            return false;
        }
    }

    public void knockback(double strength, double x, double z) {
        // CraftBukkit start - EntityKnockbackEvent
        this.knockback(strength, x, z, null, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause.UNKNOWN); // Paper - knockback events
    }

    public void knockback(double d0, double d1, double d2, @Nullable Entity attacker, io.papermc.paper.event.entity.EntityKnockbackEvent.Cause cause) { // Paper - knockback events
        d0 *= 1.0D - this.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        if (true || d0 > 0.0D) { // CraftBukkit - Call event even when force is 0
            //this.hasImpulse = true; // CraftBukkit - Move down

            Vec3 vec3d;

            for (vec3d = this.getDeltaMovement(); d1 * d1 + d2 * d2 < 9.999999747378752E-6D; d2 = (Math.random() - Math.random()) * 0.01D) {
                d1 = (Math.random() - Math.random()) * 0.01D;
            }

            Vec3 vec3d1 = (new Vec3(d1, 0.0D, d2)).normalize().scale(d0);

            // Paper start - knockback events
            Vec3 finalVelocity = new Vec3(vec3d.x / 2.0D - vec3d1.x, this.onGround() ? Math.min(0.4D, vec3d.y / 2.0D + d0) : vec3d.y, vec3d.z / 2.0D - vec3d1.z);
            Vec3 diff = finalVelocity.subtract(vec3d);
            io.papermc.paper.event.entity.EntityKnockbackEvent event = CraftEventFactory.callEntityKnockbackEvent((org.bukkit.craftbukkit.entity.CraftLivingEntity) this.getBukkitEntity(), attacker, attacker, cause, d0, diff);
            // Paper end - knockback events
            if (event.isCancelled()) {
                return;
            }

            this.hasImpulse = true;
            this.setDeltaMovement(vec3d.add(event.getKnockback().getX(), event.getKnockback().getY(), event.getKnockback().getZ())); // Paper - knockback events
            // CraftBukkit end
        }
    }

    public void indicateDamage(double deltaX, double deltaZ) {}

    @Nullable
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GENERIC_HURT;
    }

    @Nullable
    public SoundEvent getDeathSound() {
        return SoundEvents.GENERIC_DEATH;
    }

    private SoundEvent getFallDamageSound(int distance) {
        return distance > 4 ? this.getFallSounds().big() : this.getFallSounds().small();
    }

    public void skipDropExperience() {
        this.skipDropExperience = true;
    }

    public boolean wasExperienceConsumed() {
        return this.skipDropExperience;
    }

    public float getHurtDir() {
        return 0.0F;
    }

    protected AABB getHitbox() {
        AABB axisalignedbb = this.getBoundingBox();
        Entity entity = this.getVehicle();

        if (entity != null) {
            Vec3 vec3d = entity.getPassengerRidingPosition(this);

            return axisalignedbb.setMinY(Math.max(vec3d.y, axisalignedbb.minY));
        } else {
            return axisalignedbb;
        }
    }

    public Map<Enchantment, Set<EnchantmentLocationBasedEffect>> activeLocationDependentEnchantments(EquipmentSlot slot) {
        return (Map) this.activeLocationDependentEnchantments.computeIfAbsent(slot, (enumitemslot1) -> {
            return new Reference2ObjectArrayMap();
        });
    }

    public boolean canBeNameTagged() {
        return true;
    }

    public LivingEntity.Fallsounds getFallSounds() {
        return new LivingEntity.Fallsounds(SoundEvents.GENERIC_SMALL_FALL, SoundEvents.GENERIC_BIG_FALL);
    }

    // CraftBukkit start - Add delegate methods
    public SoundEvent getHurtSound0(DamageSource damagesource) {
        return this.getHurtSound(damagesource);
    }

    public SoundEvent getDeathSound0() {
        return this.getDeathSound();
    }

    public SoundEvent getFallDamageSound0(int fallHeight) {
        return this.getFallDamageSound(fallHeight);
    }
    // CraftBukkit end

    public Optional<BlockPos> getLastClimbablePos() {
        return this.lastClimbablePos;
    }

    public boolean onClimbable() {
        if (this.isSpectator()) {
            return false;
        } else {
            BlockPos blockposition = this.blockPosition();
            BlockState iblockdata = this.getInBlockState();

            if (iblockdata.is(BlockTags.CLIMBABLE)) {
                this.lastClimbablePos = Optional.of(blockposition);
                return true;
            } else if (iblockdata.getBlock() instanceof TrapDoorBlock && this.trapdoorUsableAsLadder(blockposition, iblockdata)) {
                this.lastClimbablePos = Optional.of(blockposition);
                return true;
            } else {
                return false;
            }
        }
    }

    private boolean trapdoorUsableAsLadder(BlockPos pos, BlockState state) {
        if (!(Boolean) state.getValue(TrapDoorBlock.OPEN)) {
            return false;
        } else {
            BlockState iblockdata1 = this.level().getBlockState(pos.below());

            return iblockdata1.is(Blocks.LADDER) && iblockdata1.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
        }
    }

    @Override
    public boolean isAlive() {
        return !this.isRemoved() && this.getHealth() > 0.0F && !this.dead; // Paper - Check this.dead
    }

    public boolean isLookingAtMe(LivingEntity entity, double d0, boolean flag, boolean visualShape, double... checkedYs) {
        Vec3 vec3d = entity.getViewVector(1.0F).normalize();
        double[] adouble1 = checkedYs;
        int i = checkedYs.length;

        for (int j = 0; j < i; ++j) {
            double d1 = adouble1[j];
            Vec3 vec3d1 = new Vec3(this.getX() - entity.getX(), d1 - entity.getEyeY(), this.getZ() - entity.getZ());
            double d2 = vec3d1.length();

            vec3d1 = vec3d1.normalize();
            double d3 = vec3d.dot(vec3d1);

            if (d3 > 1.0D - d0 / (flag ? d2 : 1.0D) && entity.hasLineOfSight(this, visualShape ? ClipContext.Block.VISUAL : ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, d1)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public int getMaxFallDistance() {
        return this.getComfortableFallDistance(0.0F);
    }

    protected final int getComfortableFallDistance(float health) {
        return Mth.floor(health + 3.0F);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        boolean flag = super.causeFallDamage(fallDistance, damageMultiplier, damageSource);
        int i = this.calculateFallDamage(fallDistance, damageMultiplier);

        if (i > 0) {
            // CraftBukkit start
            if (!this.hurtServer((ServerLevel) this.level(), damageSource, (float) i)) {
                return true;
            }
            // CraftBukkit end
            this.playSound(this.getFallDamageSound(i), 1.0F, 1.0F);
            this.playBlockFallSound();
            // this.damageEntity(damagesource, (float) i); // CraftBukkit - moved up
            return true;
        } else {
            return flag;
        }
    }

    protected int calculateFallDamage(float fallDistance, float damageMultiplier) {
        if (this.getType().is(EntityTypeTags.FALL_DAMAGE_IMMUNE)) {
            return 0;
        } else {
            float f2 = (float) this.getAttributeValue(Attributes.SAFE_FALL_DISTANCE);
            float f3 = fallDistance - f2;

            return Mth.ceil((double) (f3 * damageMultiplier) * this.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER));
        }
    }

    protected void playBlockFallSound() {
        if (!this.isSilent()) {
            int i = Mth.floor(this.getX());
            int j = Mth.floor(this.getY() - 0.20000000298023224D);
            int k = Mth.floor(this.getZ());
            BlockState iblockdata = this.level().getBlockState(new BlockPos(i, j, k));

            if (!iblockdata.isAir()) {
                SoundType soundeffecttype = iblockdata.getSoundType();

                this.playSound(soundeffecttype.getFallSound(), soundeffecttype.getVolume() * 0.5F, soundeffecttype.getPitch() * 0.75F);
            }

        }
    }

    @Override
    public void animateHurt(float yaw) {
        this.hurtDuration = 10;
        this.hurtTime = this.hurtDuration;
    }

    public int getArmorValue() {
        return Mth.floor(this.getAttributeValue(Attributes.ARMOR));
    }

    protected void hurtArmor(DamageSource source, float amount) {}

    protected void hurtHelmet(DamageSource source, float amount) {}

    protected void hurtCurrentlyUsedShield(float amount) {}

    protected void doHurtEquipment(DamageSource source, float amount, EquipmentSlot... slots) {
        if (amount > 0.0F) {
            int i = (int) Math.max(1.0F, amount / 4.0F);
            EquipmentSlot[] aenumitemslot1 = slots;
            int j = slots.length;

            for (int k = 0; k < j; ++k) {
                EquipmentSlot enumitemslot = aenumitemslot1[k];
                ItemStack itemstack = this.getItemBySlot(enumitemslot);
                Equippable equippable = (Equippable) itemstack.get(DataComponents.EQUIPPABLE);

                if (equippable != null && equippable.damageOnHurt() && itemstack.isDamageableItem() && itemstack.canBeHurtBy(source)) {
                    itemstack.hurtAndBreak(i, this, enumitemslot);
                }
            }

        }
    }

    protected float getDamageAfterArmorAbsorb(DamageSource source, float amount) {
        if (!source.is(DamageTypeTags.BYPASSES_ARMOR)) {
            // this.hurtArmor(damagesource, f); // CraftBukkit - actuallyHurt(DamageSource, float, EntityDamageEvent) for handle damage
            amount = CombatRules.getDamageAfterAbsorb(this, amount, source, (float) this.getArmorValue(), (float) this.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        }

        return amount;
    }

    protected float getDamageAfterMagicAbsorb(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_EFFECTS)) {
            return amount;
        } else {
            // CraftBukkit - Moved to handleEntityDamage(DamageSource, float)
            if (false && this.hasEffect(MobEffects.DAMAGE_RESISTANCE) && !source.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                int i = (this.getEffect(MobEffects.DAMAGE_RESISTANCE).getAmplifier() + 1) * 5;
                int j = 25 - i;
                float f1 = amount * (float) j;
                float f2 = amount;

                amount = Math.max(f1 / 25.0F, 0.0F);
                float f3 = f2 - amount;

                if (f3 > 0.0F && f3 < 3.4028235E37F) {
                    if (this instanceof ServerPlayer) {
                        ((ServerPlayer) this).awardStat(Stats.DAMAGE_RESISTED, Math.round(f3 * 10.0F));
                    } else if (source.getEntity() instanceof ServerPlayer) {
                        ((ServerPlayer) source.getEntity()).awardStat(Stats.DAMAGE_DEALT_RESISTED, Math.round(f3 * 10.0F));
                    }
                }
            }

            if (amount <= 0.0F) {
                return 0.0F;
            } else if (source.is(DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
                return amount;
            } else {
                Level world = this.level();
                float f4;

                if (world instanceof ServerLevel) {
                    ServerLevel worldserver = (ServerLevel) world;

                    f4 = EnchantmentHelper.getDamageProtection(worldserver, this, source);
                } else {
                    f4 = 0.0F;
                }

                if (f4 > 0.0F) {
                    amount = CombatRules.getDamageAfterMagicAbsorb(amount, f4);
                }

                return amount;
            }
        }
    }

    // CraftBukkit start
    private EntityDamageEvent handleEntityDamage(final DamageSource damagesource, float f, final float invulnerabilityRelatedLastDamage) { // Paper - fix invulnerability reduction in EntityDamageEvent
        float originalDamage = f;
        // Paper start - fix invulnerability reduction in EntityDamageEvent
        final com.google.common.base.Function<Double, Double> invulnerabilityReductionEquation = d -> {
            if (invulnerabilityRelatedLastDamage == 0) return 0D; // no last damage, no reduction
            // last damage existed, this means the reduction *technically* is (new damage - last damage).
            // If the event damage was changed to something less than invul damage, hard lock it at 0.
            if (d < invulnerabilityRelatedLastDamage) return 0D;
            return (double) -invulnerabilityRelatedLastDamage;
        };
        final float originalInvulnerabilityReduction = invulnerabilityReductionEquation.apply((double) f).floatValue();
        f += originalInvulnerabilityReduction;
        // Paper end - fix invulnerability reduction in EntityDamageEvent

        com.google.common.base.Function<Double, Double> freezing = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                if (damagesource.is(DamageTypeTags.IS_FREEZING) && LivingEntity.this.getType().is(EntityTypeTags.FREEZE_HURTS_EXTRA_TYPES)) {
                    return -(f - (f * 5.0F));
                }
                return -0.0;
            }
        };
        float freezingModifier = freezing.apply((double) f).floatValue();
        f += freezingModifier;

        com.google.common.base.Function<Double, Double> hardHat = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                if (damagesource.is(DamageTypeTags.DAMAGES_HELMET) && !LivingEntity.this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                    return -(f - (f * 0.75F));
                }
                return -0.0;
            }
        };
        float hardHatModifier = hardHat.apply((double) f).floatValue();
        f += hardHatModifier;

        com.google.common.base.Function<Double, Double> blocking = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                return -((LivingEntity.this.isDamageSourceBlocked(damagesource)) ? f : 0.0);
            }
        };
        float blockingModifier = blocking.apply((double) f).floatValue();
        f += blockingModifier;

        com.google.common.base.Function<Double, Double> armor = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                return -(f - LivingEntity.this.getDamageAfterArmorAbsorb(damagesource, f.floatValue()));
            }
        };
        float armorModifier = armor.apply((double) f).floatValue();
        f += armorModifier;

        com.google.common.base.Function<Double, Double> resistance = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                if (!damagesource.is(DamageTypeTags.BYPASSES_EFFECTS) && LivingEntity.this.hasEffect(MobEffects.DAMAGE_RESISTANCE) && !damagesource.is(DamageTypeTags.BYPASSES_RESISTANCE)) {
                    int i = (LivingEntity.this.getEffect(MobEffects.DAMAGE_RESISTANCE).getAmplifier() + 1) * 5;
                    int j = 25 - i;
                    float f1 = f.floatValue() * (float) j;

                    return -(f - Math.max(f1 / 25.0F, 0.0F));
                }
                return -0.0;
            }
        };
        float resistanceModifier = resistance.apply((double) f).floatValue();
        f += resistanceModifier;

        com.google.common.base.Function<Double, Double> magic = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                return -(f - LivingEntity.this.getDamageAfterMagicAbsorb(damagesource, f.floatValue()));
            }
        };
        float magicModifier = magic.apply((double) f).floatValue();
        f += magicModifier;

        com.google.common.base.Function<Double, Double> absorption = new com.google.common.base.Function<Double, Double>() {
            @Override
            public Double apply(Double f) {
                return -(Math.max(f - Math.max(f - LivingEntity.this.getAbsorptionAmount(), 0.0F), 0.0F));
            }
        };
        float absorptionModifier = absorption.apply((double) f).floatValue();

        // Paper start - fix invulnerability reduction in EntityDamageEvent
        return CraftEventFactory.handleLivingEntityDamageEvent(this, damagesource, originalDamage, freezingModifier, hardHatModifier, blockingModifier, armorModifier, resistanceModifier, magicModifier, absorptionModifier, freezing, hardHat, blocking, armor, resistance, magic, absorption, (damageModifierDoubleMap, damageModifierFunctionMap) -> {
            damageModifierFunctionMap.put(DamageModifier.INVULNERABILITY_REDUCTION, invulnerabilityReductionEquation);
            damageModifierDoubleMap.put(DamageModifier.INVULNERABILITY_REDUCTION, (double) originalInvulnerabilityReduction);
        });
        // Paper end - fix invulnerability reduction in EntityDamageEvent
    }

    protected boolean actuallyHurt(ServerLevel worldserver, final DamageSource damagesource, float f, final EntityDamageEvent event) { // void -> boolean, add final
        if (!this.isInvulnerableTo(worldserver, damagesource)) {
            if (event.isCancelled()) {
                return false;
            }

            if (damagesource.getEntity() instanceof net.minecraft.world.entity.player.Player) {
                // Paper start - PlayerAttackEntityCooldownResetEvent
                //((net.minecraft.world.entity.player.Player) damagesource.getEntity()).resetAttackStrengthTicker(); // Moved from EntityHuman in order to make the cooldown reset get called after the damage event is fired
                if (damagesource.getEntity() instanceof ServerPlayer) {
                    ServerPlayer player = (ServerPlayer) damagesource.getEntity();
                    if (new com.destroystokyo.paper.event.player.PlayerAttackEntityCooldownResetEvent(player.getBukkitEntity(), this.getBukkitEntity(), player.getAttackStrengthScale(0F)).callEvent()) {
                        player.resetAttackStrengthTicker();
                    }
                } else {
                    ((net.minecraft.world.entity.player.Player) damagesource.getEntity()).resetAttackStrengthTicker();
                }
                // Paper end - PlayerAttackEntityCooldownResetEvent
            }

            // Resistance
            if (event.getDamage(DamageModifier.RESISTANCE) < 0) {
                float f3 = (float) -event.getDamage(DamageModifier.RESISTANCE);
                if (f3 > 0.0F && f3 < 3.4028235E37F) {
                    if (this instanceof ServerPlayer) {
                        ((ServerPlayer) this).awardStat(Stats.DAMAGE_RESISTED, Math.round(f3 * 10.0F));
                    } else if (damagesource.getEntity() instanceof ServerPlayer) {
                        ((ServerPlayer) damagesource.getEntity()).awardStat(Stats.DAMAGE_DEALT_RESISTED, Math.round(f3 * 10.0F));
                    }
                }
            }

            // Apply damage to helmet
            if (damagesource.is(DamageTypeTags.DAMAGES_HELMET) && !this.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
                this.hurtHelmet(damagesource, f);
            }

            // Apply damage to armor
            if (!damagesource.is(DamageTypeTags.BYPASSES_ARMOR)) {
                float armorDamage = (float) (event.getDamage() + event.getDamage(DamageModifier.BLOCKING) + event.getDamage(DamageModifier.HARD_HAT));
                this.hurtArmor(damagesource, armorDamage);
            }

            // Apply blocking code // PAIL: steal from above
            if (event.getDamage(DamageModifier.BLOCKING) < 0) {
                this.hurtCurrentlyUsedShield((float) -event.getDamage(DamageModifier.BLOCKING));
                Entity entity = damagesource.getDirectEntity();

                if (!damagesource.is(DamageTypeTags.IS_PROJECTILE) && entity instanceof LivingEntity && entity.distanceToSqr(this) <= (200.0D * 200.0D)) { // Paper - Fix shield disable inconsistency & Check distance in entity interactions
                    this.blockUsingShield((LivingEntity) entity);
                }
            }

            boolean human = this instanceof net.minecraft.world.entity.player.Player;
            float originalDamage = (float) event.getDamage();
            float absorptionModifier = (float) -event.getDamage(DamageModifier.ABSORPTION);
            this.setAbsorptionAmount(Math.max(this.getAbsorptionAmount() - absorptionModifier, 0.0F));
            float f2 = absorptionModifier;

            if (f2 > 0.0F && f2 < 3.4028235E37F && this instanceof net.minecraft.world.entity.player.Player) {
                ((net.minecraft.world.entity.player.Player) this).awardStat(Stats.DAMAGE_ABSORBED, Math.round(f2 * 10.0F));
            }
            // CraftBukkit end

            if (f2 > 0.0F && f2 < 3.4028235E37F) {
                Entity entity = damagesource.getEntity();

                if (entity instanceof ServerPlayer) {
                    ServerPlayer entityplayer = (ServerPlayer) entity;

                    entityplayer.awardStat(Stats.DAMAGE_DEALT_ABSORBED, Math.round(f2 * 10.0F));
                }
            }

            // CraftBukkit start
            if (f > 0 || !human) {
                if (human) {
                    // PAIL: Be sure to drag all this code from the EntityHuman subclass each update.
                    ((net.minecraft.world.entity.player.Player) this).causeFoodExhaustion(damagesource.getFoodExhaustion(), org.bukkit.event.entity.EntityExhaustionEvent.ExhaustionReason.DAMAGED); // CraftBukkit - EntityExhaustionEvent
                    if (f < 3.4028235E37F) {
                        ((net.minecraft.world.entity.player.Player) this).awardStat(Stats.DAMAGE_TAKEN, Math.round(f * 10.0F));
                    }
                }
                // CraftBukkit end
                this.getCombatTracker().recordDamage(damagesource, f);
                this.setHealth(this.getHealth() - f);
                // CraftBukkit start
                if (!human) {
                    this.setAbsorptionAmount(this.getAbsorptionAmount() - f);
                }
                this.gameEvent(GameEvent.ENTITY_DAMAGE);

                return true;
            } else {
                // Duplicate triggers if blocking
                if (event.getDamage(DamageModifier.BLOCKING) < 0) {
                    if (this instanceof ServerPlayer) {
                        CriteriaTriggers.ENTITY_HURT_PLAYER.trigger((ServerPlayer) this, damagesource, originalDamage, f, true); // Paper - fix taken/dealt param order
                        f2 = (float) -event.getDamage(DamageModifier.BLOCKING);
                        if (f2 > 0.0F && f2 < 3.4028235E37F) {
                            ((ServerPlayer) this).awardStat(Stats.DAMAGE_BLOCKED_BY_SHIELD, Math.round(originalDamage * 10.0F));
                        }
                    }

                    if (damagesource.getEntity() instanceof ServerPlayer) {
                        CriteriaTriggers.PLAYER_HURT_ENTITY.trigger((ServerPlayer) damagesource.getEntity(), this, damagesource, originalDamage, f, true); // Paper - fix taken/dealt param order
                    }

                    return !io.papermc.paper.configuration.GlobalConfiguration.get().unsupportedSettings.skipVanillaDamageTickWhenShieldBlocked; // Paper - this should always return true, however expose an unsupported setting to flip this to false to enable "shield stunning".
                } else {
                    return true; // Paper - return false ONLY if event was cancelled
                }
                // CraftBukkit end
            }
        }
        return true; // CraftBukkit // Paper - return false ONLY if event was cancelled
    }

    public CombatTracker getCombatTracker() {
        return this.combatTracker;
    }

    @Nullable
    public LivingEntity getKillCredit() {
        return (LivingEntity) (this.lastHurtByPlayer != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.lastHurtByPlayer) ? this.lastHurtByPlayer : (this.lastHurtByMob != null && ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(this.lastHurtByMob) ? this.lastHurtByMob : null)); // Folia - region threading
    }

    public final float getMaxHealth() {
        return (float) this.getAttributeValue(Attributes.MAX_HEALTH);
    }

    public final float getMaxAbsorption() {
        return (float) this.getAttributeValue(Attributes.MAX_ABSORPTION);
    }

    public final int getArrowCount() {
        return (Integer) this.entityData.get(LivingEntity.DATA_ARROW_COUNT_ID);
    }

    public final void setArrowCount(int stuckArrowCount) {
        // CraftBukkit start
        this.setArrowCount(stuckArrowCount, false);
    }

    public final void setArrowCount(int i, boolean flag) {
        ArrowBodyCountChangeEvent event = CraftEventFactory.callArrowBodyCountChangeEvent(this, this.getArrowCount(), i, flag);
        if (event.isCancelled()) {
            return;
        }
        this.entityData.set(LivingEntity.DATA_ARROW_COUNT_ID, event.getNewAmount());
    }
    // CraftBukkit end

    public final int getStingerCount() {
        return (Integer) this.entityData.get(LivingEntity.DATA_STINGER_COUNT_ID);
    }

    public final void setStingerCount(int stingerCount) {
        this.entityData.set(LivingEntity.DATA_STINGER_COUNT_ID, stingerCount);
    }

    private int getCurrentSwingDuration() {
        return MobEffectUtil.hasDigSpeed(this) ? 6 - (1 + MobEffectUtil.getDigSpeedAmplification(this)) : (this.hasEffect(MobEffects.DIG_SLOWDOWN) ? 6 + (1 + this.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) * 2 : 6);
    }

    public void swing(InteractionHand hand) {
        this.swing(hand, false);
    }

    public void swing(InteractionHand hand, boolean fromServerPlayer) {
        if (!this.swinging || this.swingTime >= this.getCurrentSwingDuration() / 2 || this.swingTime < 0) {
            this.swingTime = -1;
            this.swinging = true;
            this.swingingArm = hand;
            if (this.level() instanceof ServerLevel) {
                ClientboundAnimatePacket packetplayoutanimation = new ClientboundAnimatePacket(this, hand == InteractionHand.MAIN_HAND ? 0 : 3);
                ServerChunkCache chunkproviderserver = ((ServerLevel) this.level()).getChunkSource();

                if (fromServerPlayer) {
                    chunkproviderserver.broadcastAndSend(this, packetplayoutanimation);
                } else {
                    chunkproviderserver.broadcast(this, packetplayoutanimation);
                }
            }
        }

    }

    @Override
    public void handleDamageEvent(DamageSource damageSource) {
        this.walkAnimation.setSpeed(1.5F);
        this.invulnerableTime = 20;
        this.hurtDuration = 10;
        this.hurtTime = this.hurtDuration;
        SoundEvent soundeffect = this.getHurtSound(damageSource);

        if (soundeffect != null) {
            this.playSound(soundeffect, this.getSoundVolume(), (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
        }

        this.lastDamageSource = damageSource;
        this.lastDamageStamp = this.level().getRedstoneGameTime(); // Folia - region threading
    }

    @Override
    public void handleEntityEvent(byte status) {
        switch (status) {
            case 3:
                SoundEvent soundeffect = this.getDeathSound();

                if (soundeffect != null) {
                    this.playSound(soundeffect, this.getSoundVolume(), (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F);
                }

                if (!(this instanceof net.minecraft.world.entity.player.Player)) {
                    this.setHealth(0.0F);
                    this.die(this.damageSources().generic());
                }
                break;
            case 29:
                this.playSound(SoundEvents.SHIELD_BLOCK, 1.0F, 0.8F + this.level().random.nextFloat() * 0.4F);
                break;
            case 30:
                this.playSound(SoundEvents.SHIELD_BREAK, 0.8F, 0.8F + this.level().random.nextFloat() * 0.4F);
                break;
            case 46:
                boolean flag = true;

                for (int i = 0; i < 128; ++i) {
                    double d0 = (double) i / 127.0D;
                    float f = (this.random.nextFloat() - 0.5F) * 0.2F;
                    float f1 = (this.random.nextFloat() - 0.5F) * 0.2F;
                    float f2 = (this.random.nextFloat() - 0.5F) * 0.2F;
                    double d1 = Mth.lerp(d0, this.xo, this.getX()) + (this.random.nextDouble() - 0.5D) * (double) this.getBbWidth() * 2.0D;
                    double d2 = Mth.lerp(d0, this.yo, this.getY()) + this.random.nextDouble() * (double) this.getBbHeight();
                    double d3 = Mth.lerp(d0, this.zo, this.getZ()) + (this.random.nextDouble() - 0.5D) * (double) this.getBbWidth() * 2.0D;

                    this.level().addParticle(ParticleTypes.PORTAL, d1, d2, d3, (double) f, (double) f1, (double) f2);
                }

                return;
            case 47:
                this.breakItem(this.getItemBySlot(EquipmentSlot.MAINHAND));
                break;
            case 48:
                this.breakItem(this.getItemBySlot(EquipmentSlot.OFFHAND));
                break;
            case 49:
                this.breakItem(this.getItemBySlot(EquipmentSlot.HEAD));
                break;
            case 50:
                this.breakItem(this.getItemBySlot(EquipmentSlot.CHEST));
                break;
            case 51:
                this.breakItem(this.getItemBySlot(EquipmentSlot.LEGS));
                break;
            case 52:
                this.breakItem(this.getItemBySlot(EquipmentSlot.FEET));
                break;
            case 54:
                HoneyBlock.showJumpParticles(this);
                break;
            case 55:
                this.swapHandItems();
                break;
            case 60:
                this.makePoofParticles();
                break;
            case 65:
                this.breakItem(this.getItemBySlot(EquipmentSlot.BODY));
                break;
            default:
                super.handleEntityEvent(status);
        }

    }

    public void makePoofParticles() {
        for (int i = 0; i < 20; ++i) {
            double d0 = this.random.nextGaussian() * 0.02D;
            double d1 = this.random.nextGaussian() * 0.02D;
            double d2 = this.random.nextGaussian() * 0.02D;
            double d3 = 10.0D;

            this.level().addParticle(ParticleTypes.POOF, this.getRandomX(1.0D) - d0 * 10.0D, this.getRandomY() - d1 * 10.0D, this.getRandomZ(1.0D) - d2 * 10.0D, d0, d1, d2);
        }

    }

    private void swapHandItems() {
        ItemStack itemstack = this.getItemBySlot(EquipmentSlot.OFFHAND);

        this.setItemSlot(EquipmentSlot.OFFHAND, this.getItemBySlot(EquipmentSlot.MAINHAND));
        this.setItemSlot(EquipmentSlot.MAINHAND, itemstack);
    }

    @Override
    protected void onBelowWorld() {
        this.hurt(this.damageSources().fellOutOfWorld(), this.level().getWorld().getVoidDamageAmount()); // Paper - use configured void damage amount
    }

    protected void updateSwingTime() {
        int i = this.getCurrentSwingDuration();

        if (this.swinging) {
            ++this.swingTime;
            if (this.swingTime >= i) {
                this.swingTime = 0;
                this.swinging = false;
            }
        } else {
            this.swingTime = 0;
        }

        this.attackAnim = (float) this.swingTime / (float) i;
    }

    @Nullable
    public AttributeInstance getAttribute(Holder<Attribute> attribute) {
        return this.getAttributes().getInstance(attribute);
    }

    public double getAttributeValue(Holder<Attribute> attribute) {
        return this.getAttributes().getValue(attribute);
    }

    public double getAttributeBaseValue(Holder<Attribute> attribute) {
        return this.getAttributes().getBaseValue(attribute);
    }

    public AttributeMap getAttributes() {
        return this.attributes;
    }

    public ItemStack getMainHandItem() {
        return this.getItemBySlot(EquipmentSlot.MAINHAND);
    }

    public ItemStack getOffhandItem() {
        return this.getItemBySlot(EquipmentSlot.OFFHAND);
    }

    public ItemStack getItemHeldByArm(HumanoidArm arm) {
        return this.getMainArm() == arm ? this.getMainHandItem() : this.getOffhandItem();
    }

    @Nonnull
    @Override
    public ItemStack getWeaponItem() {
        return this.getMainHandItem();
    }

    public boolean isHolding(Item item) {
        return this.isHolding((itemstack) -> {
            return itemstack.is(item);
        });
    }

    public boolean isHolding(Predicate<ItemStack> predicate) {
        return predicate.test(this.getMainHandItem()) || predicate.test(this.getOffhandItem());
    }

    public ItemStack getItemInHand(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return this.getItemBySlot(EquipmentSlot.MAINHAND);
        } else if (hand == InteractionHand.OFF_HAND) {
            return this.getItemBySlot(EquipmentSlot.OFFHAND);
        } else {
            throw new IllegalArgumentException("Invalid hand " + String.valueOf(hand));
        }
    }

    public void setItemInHand(InteractionHand hand, ItemStack stack) {
        if (hand == InteractionHand.MAIN_HAND) {
            this.setItemSlot(EquipmentSlot.MAINHAND, stack);
        } else {
            if (hand != InteractionHand.OFF_HAND) {
                throw new IllegalArgumentException("Invalid hand " + String.valueOf(hand));
            }

            this.setItemSlot(EquipmentSlot.OFFHAND, stack);
        }

    }

    public boolean hasItemInSlot(EquipmentSlot slot) {
        return !this.getItemBySlot(slot).isEmpty();
    }

    public boolean canUseSlot(EquipmentSlot slot) {
        return false;
    }

    public abstract Iterable<ItemStack> getArmorSlots();

    public abstract ItemStack getItemBySlot(EquipmentSlot slot);

    // CraftBukkit start
    public void setItemSlot(EquipmentSlot enumitemslot, ItemStack itemstack, boolean silent) {
        this.setItemSlot(enumitemslot, itemstack);
    }
    // CraftBukkit end

    public abstract void setItemSlot(EquipmentSlot slot, ItemStack stack);

    public Iterable<ItemStack> getHandSlots() {
        return List.of();
    }

    public Iterable<ItemStack> getArmorAndBodyArmorSlots() {
        return this.getArmorSlots();
    }

    public Iterable<ItemStack> getAllSlots() {
        return Iterables.concat(this.getHandSlots(), this.getArmorAndBodyArmorSlots());
    }

    protected void verifyEquippedItem(ItemStack stack) {
        stack.getItem().verifyComponentsAfterLoad(stack);
    }

    public float getArmorCoverPercentage() {
        Iterable<ItemStack> iterable = this.getArmorSlots();
        int i = 0;
        int j = 0;

        for (Iterator iterator = iterable.iterator(); iterator.hasNext(); ++i) {
            ItemStack itemstack = (ItemStack) iterator.next();

            if (!itemstack.isEmpty()) {
                ++j;
            }
        }

        return i > 0 ? (float) j / (float) i : 0.0F;
    }

    @Override
    public void setSprinting(boolean sprinting) {
        super.setSprinting(sprinting);
        AttributeInstance attributemodifiable = this.getAttribute(Attributes.MOVEMENT_SPEED);

        attributemodifiable.removeModifier(LivingEntity.SPEED_MODIFIER_SPRINTING.id());
        if (sprinting) {
            attributemodifiable.addTransientModifier(LivingEntity.SPEED_MODIFIER_SPRINTING);
        }

    }

    public float getSoundVolume() {
        return 1.0F;
    }

    public float getVoicePitch() {
        return this.isBaby() ? (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.5F : (this.random.nextFloat() - this.random.nextFloat()) * 0.2F + 1.0F;
    }

    protected boolean isImmobile() {
        return this.isDeadOrDying();
    }

    @Override
    public void push(Entity entity) {
        if (!this.isSleeping()) {
            super.push(entity);
        }

    }

    private void dismountVehicle(Entity vehicle) {
        Vec3 vec3d;

        if (this.isRemoved()) {
            vec3d = this.position();
        } else if (!vehicle.isRemoved() && !this.level().getBlockState(vehicle.blockPosition()).is(BlockTags.PORTALS)) {
            vec3d = vehicle.getDismountLocationForPassenger(this);
        } else {
            double d0 = Math.max(this.getY(), vehicle.getY());

            vec3d = new Vec3(this.getX(), d0, this.getZ());
            boolean flag = this.getBbWidth() <= 4.0F && this.getBbHeight() <= 4.0F;

            if (flag) {
                double d1 = (double) this.getBbHeight() / 2.0D;
                Vec3 vec3d1 = vec3d.add(0.0D, d1, 0.0D);
                VoxelShape voxelshape = Shapes.create(AABB.ofSize(vec3d1, (double) this.getBbWidth(), (double) this.getBbHeight(), (double) this.getBbWidth()));

                vec3d = (Vec3) this.level().findFreePosition(this, voxelshape, vec3d1, (double) this.getBbWidth(), (double) this.getBbHeight(), (double) this.getBbWidth()).map((vec3d2) -> {
                    return vec3d2.add(0.0D, -d1, 0.0D);
                }).orElse(vec3d);
            }
        }

        this.dismountTo(vec3d.x, vec3d.y, vec3d.z);
    }

    @Override
    public boolean shouldShowName() {
        return this.isCustomNameVisible();
    }

    protected float getJumpPower() {
        return this.getJumpPower(1.0F);
    }

    protected float getJumpPower(float strength) {
        return (float) this.getAttributeValue(Attributes.JUMP_STRENGTH) * strength * this.getBlockJumpFactor() + this.getJumpBoostPower();
    }

    public float getJumpBoostPower() {
        return this.hasEffect(MobEffects.JUMP) ? 0.1F * ((float) this.getEffect(MobEffects.JUMP).getAmplifier() + 1.0F) : 0.0F;
    }

    protected long lastJumpTime = 0L; // Paper - Prevent excessive velocity through repeated crits
    @VisibleForTesting
    public void jumpFromGround() {
        float f = this.getJumpPower();

        if (f > 1.0E-5F) {
            Vec3 vec3d = this.getDeltaMovement();
            // Paper start - Prevent excessive velocity through repeated crits
            long time = System.nanoTime();
            boolean canCrit = true;
            if (this instanceof net.minecraft.world.entity.player.Player) {
                canCrit = false;
                if (time - this.lastJumpTime > (long)(0.250e9)) {
                    this.lastJumpTime = time;
                    canCrit = true;
                }
            }
            // Paper end - Prevent excessive velocity through repeated crits

            this.setDeltaMovement(vec3d.x, Math.max((double) f, vec3d.y), vec3d.z);
            if (this.isSprinting()) {
                float f1 = this.getYRot() * 0.017453292F;
                if (canCrit) // Paper - Prevent excessive velocity through repeated crits
                this.addDeltaMovement(new Vec3((double) (-Mth.sin(f1)) * 0.2D, 0.0D, (double) Mth.cos(f1) * 0.2D));
            }

            this.hasImpulse = true;
        }
    }

    protected void goDownInWater() {
        this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.03999999910593033D, 0.0D));
    }

    protected void jumpInLiquid(TagKey<Fluid> fluid) {
        this.setDeltaMovement(this.getDeltaMovement().add(0.0D, 0.03999999910593033D, 0.0D));
    }

    protected float getWaterSlowDown() {
        return 0.8F;
    }

    public boolean canStandOnFluid(FluidState state) {
        return false;
    }

    @Override
    protected double getDefaultGravity() {
        return this.getAttributeValue(Attributes.GRAVITY);
    }

    protected double getEffectiveGravity() {
        boolean flag = this.getDeltaMovement().y <= 0.0D;

        return flag && this.hasEffect(MobEffects.SLOW_FALLING) ? Math.min(this.getGravity(), 0.01D) : this.getGravity();
    }

    public void travel(Vec3 movementInput) {
        if (this.isControlledByLocalInstance()) {
            FluidState fluid = this.level().getFluidState(this.blockPosition());

            if ((this.isInWater() || this.isInLava()) && this.isAffectedByFluids() && !this.canStandOnFluid(fluid)) {
                this.travelInFluid(movementInput);
            } else if (this.isFallFlying()) {
                this.travelFallFlying();
            } else {
                this.travelInAir(movementInput);
            }

        }
    }

    private void travelInAir(Vec3 movementInput) {
        BlockPos blockposition = this.getBlockPosBelowThatAffectsMyMovement();
        float f = this.onGround() ? this.level().getBlockState(blockposition).getBlock().getFriction() : 1.0F;
        float f1 = f * 0.91F;
        Vec3 vec3d1 = this.handleRelativeFrictionAndCalculateMovement(movementInput, f);
        double d0 = vec3d1.y;
        MobEffectInstance mobeffect = this.getEffect(MobEffects.LEVITATION);

        if (mobeffect != null) {
            d0 += (0.05D * (double) (mobeffect.getAmplifier() + 1) - vec3d1.y) * 0.2D;
        } else if (this.level().isClientSide && !this.level().hasChunkAt(blockposition)) {
            if (this.getY() > (double) this.level().getMinY()) {
                d0 = -0.1D;
            } else {
                d0 = 0.0D;
            }
        } else {
            d0 -= this.getEffectiveGravity();
        }

        if (this.shouldDiscardFriction()) {
            this.setDeltaMovement(vec3d1.x, d0, vec3d1.z);
        } else {
            float f2 = this instanceof FlyingAnimal ? f1 : 0.98F;

            this.setDeltaMovement(vec3d1.x * (double) f1, d0 * (double) f2, vec3d1.z * (double) f1);
        }

    }

    private void travelInFluid(Vec3 movementInput) {
        boolean flag = this.getDeltaMovement().y <= 0.0D;
        double d0 = this.getY();
        double d1 = this.getEffectiveGravity();
        Vec3 vec3d1;

        if (this.isInWater()) {
            float f = this.isSprinting() ? 0.9F : this.getWaterSlowDown();
            float f1 = 0.02F;
            float f2 = (float) this.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);

            if (!this.onGround()) {
                f2 *= 0.5F;
            }

            if (f2 > 0.0F) {
                f += (0.54600006F - f) * f2;
                f1 += (this.getSpeed() - f1) * f2;
            }

            if (this.hasEffect(MobEffects.DOLPHINS_GRACE)) {
                f = 0.96F;
            }

            this.moveRelative(f1, movementInput);
            this.move(MoverType.SELF, this.getDeltaMovement());
            Vec3 vec3d2 = this.getDeltaMovement();

            if (this.horizontalCollision && this.onClimbable()) {
                vec3d2 = new Vec3(vec3d2.x, 0.2D, vec3d2.z);
            }

            vec3d2 = vec3d2.multiply((double) f, 0.800000011920929D, (double) f);
            this.setDeltaMovement(this.getFluidFallingAdjustedMovement(d1, flag, vec3d2));
        } else {
            this.moveRelative(0.02F, movementInput);
            this.move(MoverType.SELF, this.getDeltaMovement());
            if (this.getFluidHeight(FluidTags.LAVA) <= this.getFluidJumpThreshold()) {
                this.setDeltaMovement(this.getDeltaMovement().multiply(0.5D, 0.800000011920929D, 0.5D));
                vec3d1 = this.getFluidFallingAdjustedMovement(d1, flag, this.getDeltaMovement());
                this.setDeltaMovement(vec3d1);
            } else {
                this.setDeltaMovement(this.getDeltaMovement().scale(0.5D));
            }

            if (d1 != 0.0D) {
                this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -d1 / 4.0D, 0.0D));
            }
        }

        vec3d1 = this.getDeltaMovement();
        if (this.horizontalCollision && this.isFree(vec3d1.x, vec3d1.y + 0.6000000238418579D - this.getY() + d0, vec3d1.z)) {
            this.setDeltaMovement(vec3d1.x, 0.30000001192092896D, vec3d1.z);
        }

    }

    private void travelFallFlying() {
        Vec3 vec3d = this.getDeltaMovement();
        double d0 = vec3d.horizontalDistance();

        this.setDeltaMovement(this.updateFallFlyingMovement(vec3d));
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (!this.level().isClientSide) {
            double d1 = this.getDeltaMovement().horizontalDistance();

            this.handleFallFlyingCollisions(d0, d1);
        }

    }

    private Vec3 updateFallFlyingMovement(Vec3 oldVelocity) {
        Vec3 vec3d1 = this.getLookAngle();
        float f = this.getXRot() * 0.017453292F;
        double d0 = Math.sqrt(vec3d1.x * vec3d1.x + vec3d1.z * vec3d1.z);
        double d1 = oldVelocity.horizontalDistance();
        double d2 = this.getEffectiveGravity();
        double d3 = Mth.square(Math.cos((double) f));

        oldVelocity = oldVelocity.add(0.0D, d2 * (-1.0D + d3 * 0.75D), 0.0D);
        double d4;

        if (oldVelocity.y < 0.0D && d0 > 0.0D) {
            d4 = oldVelocity.y * -0.1D * d3;
            oldVelocity = oldVelocity.add(vec3d1.x * d4 / d0, d4, vec3d1.z * d4 / d0);
        }

        if (f < 0.0F && d0 > 0.0D) {
            d4 = d1 * (double) (-Mth.sin(f)) * 0.04D;
            oldVelocity = oldVelocity.add(-vec3d1.x * d4 / d0, d4 * 3.2D, -vec3d1.z * d4 / d0);
        }

        if (d0 > 0.0D) {
            oldVelocity = oldVelocity.add((vec3d1.x / d0 * d1 - oldVelocity.x) * 0.1D, 0.0D, (vec3d1.z / d0 * d1 - oldVelocity.z) * 0.1D);
        }

        return oldVelocity.multiply(0.9900000095367432D, 0.9800000190734863D, 0.9900000095367432D);
    }

    private void handleFallFlyingCollisions(double oldSpeed, double newSpeed) {
        if (this.horizontalCollision) {
            double d2 = oldSpeed - newSpeed;
            float f = (float) (d2 * 10.0D - 3.0D);

            if (f > 0.0F) {
                this.playSound(this.getFallDamageSound((int) f), 1.0F, 1.0F);
                this.hurt(this.damageSources().flyIntoWall(), f);
            }
        }

    }

    private void travelRidden(net.minecraft.world.entity.player.Player controllingPlayer, Vec3 movementInput) {
        Vec3 vec3d1 = this.getRiddenInput(controllingPlayer, movementInput);

        this.tickRidden(controllingPlayer, vec3d1);
        if (this.isControlledByLocalInstance()) {
            this.setSpeed(this.getRiddenSpeed(controllingPlayer));
            this.travel(vec3d1);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }

    }

    protected void tickRidden(net.minecraft.world.entity.player.Player controllingPlayer, Vec3 movementInput) {}

    protected Vec3 getRiddenInput(net.minecraft.world.entity.player.Player controllingPlayer, Vec3 movementInput) {
        return movementInput;
    }

    protected float getRiddenSpeed(net.minecraft.world.entity.player.Player controllingPlayer) {
        return this.getSpeed();
    }

    public void calculateEntityAnimation(boolean flutter) {
        float f = (float) Mth.length(this.getX() - this.xo, flutter ? this.getY() - this.yo : 0.0D, this.getZ() - this.zo);

        if (!this.isPassenger() && this.isAlive()) {
            this.updateWalkAnimation(f);
        } else {
            this.walkAnimation.stop();
        }

    }

    protected void updateWalkAnimation(float posDelta) {
        float f1 = Math.min(posDelta * 4.0F, 1.0F);

        this.walkAnimation.update(f1, 0.4F, this.isBaby() ? 3.0F : 1.0F);
    }

    private Vec3 handleRelativeFrictionAndCalculateMovement(Vec3 movementInput, float slipperiness) {
        this.moveRelative(this.getFrictionInfluencedSpeed(slipperiness), movementInput);
        this.setDeltaMovement(this.handleOnClimbable(this.getDeltaMovement()));
        this.move(MoverType.SELF, this.getDeltaMovement());
        Vec3 vec3d1 = this.getDeltaMovement();

        if ((this.horizontalCollision || this.jumping) && (this.onClimbable() || this.getInBlockState().is(Blocks.POWDER_SNOW) && PowderSnowBlock.canEntityWalkOnPowderSnow(this))) {
            vec3d1 = new Vec3(vec3d1.x, 0.2D, vec3d1.z);
        }

        return vec3d1;
    }

    public Vec3 getFluidFallingAdjustedMovement(double gravity, boolean falling, Vec3 motion) {
        if (gravity != 0.0D && !this.isSprinting()) {
            double d1;

            if (falling && Math.abs(motion.y - 0.005D) >= 0.003D && Math.abs(motion.y - gravity / 16.0D) < 0.003D) {
                d1 = -0.003D;
            } else {
                d1 = motion.y - gravity / 16.0D;
            }

            return new Vec3(motion.x, d1, motion.z);
        } else {
            return motion;
        }
    }

    private Vec3 handleOnClimbable(Vec3 motion) {
        if (this.onClimbable()) {
            this.resetFallDistance();
            float f = 0.15F;
            double d0 = Mth.clamp(motion.x, -0.15000000596046448D, 0.15000000596046448D);
            double d1 = Mth.clamp(motion.z, -0.15000000596046448D, 0.15000000596046448D);
            double d2 = Math.max(motion.y, -0.15000000596046448D);

            if (d2 < 0.0D && !this.getInBlockState().is(Blocks.SCAFFOLDING) && this.isSuppressingSlidingDownLadder() && this instanceof net.minecraft.world.entity.player.Player) {
                d2 = 0.0D;
            }

            motion = new Vec3(d0, d2, d1);
        }

        return motion;
    }

    private float getFrictionInfluencedSpeed(float slipperiness) {
        return this.onGround() ? this.getSpeed() * (0.21600002F / (slipperiness * slipperiness * slipperiness)) : this.getFlyingSpeed();
    }

    protected float getFlyingSpeed() {
        return this.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player ? this.getSpeed() * 0.1F : 0.02F;
    }

    public float getSpeed() {
        return this.speed;
    }

    public void setSpeed(float movementSpeed) {
        this.speed = movementSpeed;
    }

    public boolean doHurtTarget(ServerLevel world, Entity target) {
        this.setLastHurtMob(target);
        return false;
    }

    @Override
    public void tick() {
        super.tick();
        this.updatingUsingItem();
        this.updateSwimAmount();
        if (!this.level().isClientSide) {
            int i = this.getArrowCount();

            if (i > 0) {
                if (this.removeArrowTime <= 0) {
                    this.removeArrowTime = 20 * (30 - i);
                }

                --this.removeArrowTime;
                if (this.removeArrowTime <= 0) {
                    this.setArrowCount(i - 1);
                }
            }

            int j = this.getStingerCount();

            if (j > 0) {
                if (this.removeStingerTime <= 0) {
                    this.removeStingerTime = 20 * (30 - j);
                }

                --this.removeStingerTime;
                if (this.removeStingerTime <= 0) {
                    this.setStingerCount(j - 1);
                }
            }

            this.detectEquipmentUpdatesPublic(); // CraftBukkit
            if (this.tickCount % 20 == 0) {
                this.getCombatTracker().recheckStatus();
            }

            if (this.isSleeping() && !this.checkBedExists()) {
                this.stopSleeping();
            }
        }

        if (!this.isRemoved()) {
            this.aiStep();
        }

        double d0 = this.getX() - this.xo;
        double d1 = this.getZ() - this.zo;
        float f = (float) (d0 * d0 + d1 * d1);
        float f1 = this.yBodyRot;
        float f2 = 0.0F;

        this.oRun = this.run;
        float f3 = 0.0F;
        float f4;

        if (f > 0.0025000002F) {
            f3 = 1.0F;
            f2 = (float) Math.sqrt((double) f) * 3.0F;
            float f5 = (float) Mth.atan2(d1, d0) * 57.295776F - 90.0F;

            f4 = Mth.abs(Mth.wrapDegrees(this.getYRot()) - f5);
            if (95.0F < f4 && f4 < 265.0F) {
                f1 = f5 - 180.0F;
            } else {
                f1 = f5;
            }
        }

        if (this.attackAnim > 0.0F) {
            f1 = this.getYRot();
        }

        if (!this.onGround()) {
            f3 = 0.0F;
        }

        this.run += (f3 - this.run) * 0.3F;
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("headTurn");
        f2 = this.tickHeadTurn(f1, f2);
        gameprofilerfiller.pop();
        gameprofilerfiller.push("rangeChecks");

        // Paper start - stop large pitch and yaw changes from crashing the server
        this.yRotO += Math.round((this.getYRot() - this.yRotO) / 360.0F) * 360.0F;

        this.yBodyRotO += Math.round((this.yBodyRot - this.yBodyRotO) / 360.0F) * 360.0F;

        this.xRotO += Math.round((this.getXRot() - this.xRotO) / 360.0F) * 360.0F;

        this.yHeadRotO += Math.round((this.yHeadRot - this.yHeadRotO) / 360.0F) * 360.0F;
        // Paper end

        gameprofilerfiller.pop();
        this.animStep += f2;
        if (this.isFallFlying()) {
            ++this.fallFlyTicks;
        } else {
            this.fallFlyTicks = 0;
        }

        if (this.isSleeping()) {
            this.setXRot(0.0F);
        }

        this.refreshDirtyAttributes();
        f4 = this.getScale();
        if (f4 != this.appliedScale) {
            this.appliedScale = f4;
            this.refreshDimensions();
        }

        this.elytraAnimationState.tick();
    }

    public void detectEquipmentUpdatesPublic() { // CraftBukkit
        Map<EquipmentSlot, ItemStack> map = this.collectEquipmentChanges();

        if (map != null) {
            this.handleHandSwap(map);
            if (!map.isEmpty()) {
                this.handleEquipmentChanges(map);
            }
        }

    }

    @Nullable
    private Map<EquipmentSlot, ItemStack> collectEquipmentChanges() {
        Map<EquipmentSlot, ItemStack> map = null;
        Iterator iterator = EquipmentSlot.VALUES.iterator();

        ItemStack itemstack;

        while (iterator.hasNext()) {
            EquipmentSlot enumitemslot = (EquipmentSlot) iterator.next();
            ItemStack itemstack1;

            switch (enumitemslot.getType()) {
                case HAND:
                    itemstack1 = this.getLastHandItem(enumitemslot);
                    break;
                case HUMANOID_ARMOR:
                    itemstack1 = this.getLastArmorItem(enumitemslot);
                    break;
                case ANIMAL_ARMOR:
                    itemstack1 = this.lastBodyItemStack;
                    break;
                default:
                    throw new MatchException((String) null, (Throwable) null);
            }

            ItemStack itemstack2 = itemstack1; final ItemStack oldEquipment = itemstack2; // Paper - PlayerArmorChangeEvent - obfhelper 

            itemstack = this.getItemBySlot(enumitemslot); final ItemStack newEquipment = itemstack;// Paper - PlayerArmorChangeEvent - obfhelper
            if (this.equipmentHasChanged(itemstack2, itemstack)) {
                // Paper start - PlayerArmorChangeEvent
                if (this instanceof ServerPlayer && enumitemslot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                    final org.bukkit.inventory.ItemStack oldItem = CraftItemStack.asBukkitCopy(oldEquipment);
                    final org.bukkit.inventory.ItemStack newItem = CraftItemStack.asBukkitCopy(newEquipment);
                    new com.destroystokyo.paper.event.player.PlayerArmorChangeEvent((Player) this.getBukkitEntity(), com.destroystokyo.paper.event.player.PlayerArmorChangeEvent.SlotType.valueOf(enumitemslot.name()), oldItem, newItem).callEvent();
                }
                // Paper end - PlayerArmorChangeEvent
                if (map == null) {
                    map = Maps.newEnumMap(EquipmentSlot.class);
                }

                map.put(enumitemslot, itemstack);
                AttributeMap attributemapbase = this.getAttributes();

                if (!itemstack2.isEmpty()) {
                    this.stopLocationBasedEffects(itemstack2, enumitemslot, attributemapbase);
                }
            }
        }

        if (map != null) {
            iterator = map.entrySet().iterator();

            while (iterator.hasNext()) {
                Entry<EquipmentSlot, ItemStack> entry = (Entry) iterator.next();
                EquipmentSlot enumitemslot1 = (EquipmentSlot) entry.getKey();

                itemstack = (ItemStack) entry.getValue();
                if (!itemstack.isEmpty() && !itemstack.isBroken()) {
                    itemstack.forEachModifier(enumitemslot1, (holder, attributemodifier) -> {
                        AttributeInstance attributemodifiable = this.attributes.getInstance(holder);

                        if (attributemodifiable != null) {
                            attributemodifiable.removeModifier(attributemodifier.id());
                            attributemodifiable.addTransientModifier(attributemodifier);
                        }

                    });
                    Level world = this.level();

                    if (world instanceof ServerLevel) {
                        ServerLevel worldserver = (ServerLevel) world;

                        EnchantmentHelper.runLocationChangedEffects(worldserver, itemstack, this, enumitemslot1);
                    }
                }
            }
        }

        return map;
    }

    public boolean equipmentHasChanged(ItemStack stack, ItemStack stack2) {
        return !ItemStack.matches(stack2, stack);
    }

    private void handleHandSwap(Map<EquipmentSlot, ItemStack> equipmentChanges) {
        ItemStack itemstack = (ItemStack) equipmentChanges.get(EquipmentSlot.MAINHAND);
        ItemStack itemstack1 = (ItemStack) equipmentChanges.get(EquipmentSlot.OFFHAND);

        if (itemstack != null && itemstack1 != null && ItemStack.matches(itemstack, this.getLastHandItem(EquipmentSlot.OFFHAND)) && ItemStack.matches(itemstack1, this.getLastHandItem(EquipmentSlot.MAINHAND))) {
            ((ServerLevel) this.level()).getChunkSource().broadcast(this, new ClientboundEntityEventPacket(this, (byte) 55));
            equipmentChanges.remove(EquipmentSlot.MAINHAND);
            equipmentChanges.remove(EquipmentSlot.OFFHAND);
            this.setLastHandItem(EquipmentSlot.MAINHAND, itemstack.copy());
            this.setLastHandItem(EquipmentSlot.OFFHAND, itemstack1.copy());
        }

    }

    private void handleEquipmentChanges(Map<EquipmentSlot, ItemStack> equipmentChanges) {
        List<Pair<EquipmentSlot, ItemStack>> list = Lists.newArrayListWithCapacity(equipmentChanges.size());

        equipmentChanges.forEach((enumitemslot, itemstack) -> {
            ItemStack itemstack1 = itemstack.copy();

            list.add(Pair.of(enumitemslot, itemstack1));
            switch (enumitemslot.getType()) {
                case HAND:
                    this.setLastHandItem(enumitemslot, itemstack1);
                    break;
                case HUMANOID_ARMOR:
                    this.setLastArmorItem(enumitemslot, itemstack1);
                    break;
                case ANIMAL_ARMOR:
                    this.lastBodyItemStack = itemstack1;
            }

        });
        ((ServerLevel) this.level()).getChunkSource().broadcast(this, new ClientboundSetEquipmentPacket(this.getId(), list, true)); // Paper - data sanitization
    }

    private ItemStack getLastArmorItem(EquipmentSlot slot) {
        return (ItemStack) this.lastArmorItemStacks.get(slot.getIndex());
    }

    private void setLastArmorItem(EquipmentSlot slot, ItemStack armor) {
        this.lastArmorItemStacks.set(slot.getIndex(), armor);
    }

    private ItemStack getLastHandItem(EquipmentSlot slot) {
        return (ItemStack) this.lastHandItemStacks.get(slot.getIndex());
    }

    private void setLastHandItem(EquipmentSlot slot, ItemStack stack) {
        this.lastHandItemStacks.set(slot.getIndex(), stack);
    }

    protected float tickHeadTurn(float bodyRotation, float headRotation) {
        float f2 = Mth.wrapDegrees(bodyRotation - this.yBodyRot);

        this.yBodyRot += f2 * 0.3F;
        float f3 = Mth.wrapDegrees(this.getYRot() - this.yBodyRot);
        float f4 = this.getMaxHeadRotationRelativeToBody();

        if (Math.abs(f3) > f4) {
            this.yBodyRot += f3 - (float) Mth.sign((double) f3) * f4;
        }

        boolean flag = f3 < -90.0F || f3 >= 90.0F;

        if (flag) {
            headRotation *= -1.0F;
        }

        return headRotation;
    }

    protected float getMaxHeadRotationRelativeToBody() {
        return 50.0F;
    }

    public void aiStep() {
        if (this.noJumpDelay > 0) {
            --this.noJumpDelay;
        }

        if (this.lerpSteps > 0) {
            this.lerpPositionAndRotationStep(this.lerpSteps, this.lerpX, this.lerpY, this.lerpZ, this.lerpYRot, this.lerpXRot);
            --this.lerpSteps;
        } else if (!this.isEffectiveAi()) {
            this.setDeltaMovement(this.getDeltaMovement().scale(0.98D));
        }

        if (this.lerpHeadSteps > 0) {
            this.lerpHeadRotationStep(this.lerpHeadSteps, this.lerpYHeadRot);
            --this.lerpHeadSteps;
        }

        Vec3 vec3d = this.getDeltaMovement();
        double d0 = vec3d.x;
        double d1 = vec3d.y;
        double d2 = vec3d.z;

        if (Math.abs(vec3d.x) < 0.003D) {
            d0 = 0.0D;
        }

        if (Math.abs(vec3d.y) < 0.003D) {
            d1 = 0.0D;
        }

        if (Math.abs(vec3d.z) < 0.003D) {
            d2 = 0.0D;
        }

        this.setDeltaMovement(d0, d1, d2);
        ProfilerFiller gameprofilerfiller = Profiler.get();

        gameprofilerfiller.push("ai");
        if (this.isImmobile()) {
            this.jumping = false;
            this.xxa = 0.0F;
            this.zza = 0.0F;
        } else if (this.isEffectiveAi()) {
            gameprofilerfiller.push("newAi");
            this.serverAiStep();
            gameprofilerfiller.pop();
        }

        gameprofilerfiller.pop();
        gameprofilerfiller.push("jump");
        if (this.jumping && this.isAffectedByFluids()) {
            double d3;

            if (this.isInLava()) {
                d3 = this.getFluidHeight(FluidTags.LAVA);
            } else {
                d3 = this.getFluidHeight(FluidTags.WATER);
            }

            boolean flag = this.isInWater() && d3 > 0.0D;
            double d4 = this.getFluidJumpThreshold();

            if (flag && (!this.onGround() || d3 > d4)) {
                this.jumpInLiquid(FluidTags.WATER);
            } else if (this.isInLava() && (!this.onGround() || d3 > d4)) {
                this.jumpInLiquid(FluidTags.LAVA);
            } else if ((this.onGround() || flag && d3 <= d4) && this.noJumpDelay == 0) {
                if (new com.destroystokyo.paper.event.entity.EntityJumpEvent(getBukkitLivingEntity()).callEvent()) { // Paper - Entity Jump API
                this.jumpFromGround();
                this.noJumpDelay = 10;
                } else { this.setJumping(false); } // Paper - Entity Jump API; setJumping(false) stops a potential loop
            }
        } else {
            this.noJumpDelay = 0;
        }

        gameprofilerfiller.pop();
        gameprofilerfiller.push("travel");
        this.xxa *= 0.98F;
        this.zza *= 0.98F;
        if (this.isFallFlying()) {
            this.updateFallFlying();
        }

        AABB axisalignedbb = this.getBoundingBox();
        Vec3 vec3d1 = new Vec3((double) this.xxa, (double) this.yya, (double) this.zza);

        if (this.hasEffect(MobEffects.SLOW_FALLING) || this.hasEffect(MobEffects.LEVITATION)) {
            this.resetFallDistance();
        }

        label112:
        {
            LivingEntity entityliving = this.getControllingPassenger();

            if (entityliving instanceof net.minecraft.world.entity.player.Player entityhuman) {
                if (this.isAlive()) {
                    this.travelRidden(entityhuman, vec3d1);
                    break label112;
                }
            }

            this.travel(vec3d1);
        }

        if (!this.level().isClientSide() || this.isControlledByLocalInstance()) {
            this.applyEffectsFromBlocks();
        }

        this.calculateEntityAnimation(this instanceof FlyingAnimal);
        gameprofilerfiller.pop();
        gameprofilerfiller.push("freezing");
        if (!this.level().isClientSide && !this.isDeadOrDying() && !this.freezeLocked) { // Paper - Freeze Tick Lock API
            int i = this.getTicksFrozen();

            if (this.isInPowderSnow && this.canFreeze()) {
                this.setTicksFrozen(Math.min(this.getTicksRequiredToFreeze(), i + 1));
            } else {
                this.setTicksFrozen(Math.max(0, i - 2));
            }
        }

        this.removeFrost();
        this.tryAddFrost();
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            if (this.tickCount % 40 == 0 && this.isFullyFrozen() && this.canFreeze()) {
                this.hurtServer(worldserver, this.damageSources().freeze(), 1.0F);
            }
        }

        gameprofilerfiller.pop();
        gameprofilerfiller.push("push");
        if (this.autoSpinAttackTicks > 0) {
            --this.autoSpinAttackTicks;
            this.checkAutoSpinAttack(axisalignedbb, this.getBoundingBox());
        }

        this.pushEntities();
        gameprofilerfiller.pop();
        // Paper start - Add EntityMoveEvent
        if (((ServerLevel) this.level()).getCurrentWorldData().hasEntityMoveEvent && !(this instanceof net.minecraft.world.entity.player.Player)) { // Folia - region threading
            if (this.xo != this.getX() || this.yo != this.getY() || this.zo != this.getZ() || this.yRotO != this.getYRot() || this.xRotO != this.getXRot()) {
                Location from = new Location(this.level().getWorld(), this.xo, this.yo, this.zo, this.yRotO, this.xRotO);
                Location to = new Location(this.level().getWorld(), this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
                io.papermc.paper.event.entity.EntityMoveEvent event = new io.papermc.paper.event.entity.EntityMoveEvent(this.getBukkitLivingEntity(), from, to.clone());
                if (!event.callEvent()) {
                    this.absMoveTo(from.getX(), from.getY(), from.getZ(), from.getYaw(), from.getPitch());
                } else if (!to.equals(event.getTo())) {
                    this.absMoveTo(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ(), event.getTo().getYaw(), event.getTo().getPitch());
                }
            }
        }
        // Paper end - Add EntityMoveEvent
        world = this.level();
        if (world instanceof ServerLevel worldserver) {
            if (this.isSensitiveToWater() && this.isInWaterRainOrBubble()) {
                this.hurtServer(worldserver, this.damageSources().drown(), 1.0F);
            }
        }

    }

    public boolean isSensitiveToWater() {
        return false;
    }

    protected void updateFallFlying() {
        this.checkSlowFallDistance();
        if (!this.level().isClientSide) {
            if (!this.canGlide()) {
                if (this.getSharedFlag(7) != false && !CraftEventFactory.callToggleGlideEvent(this, false).isCancelled()) // CraftBukkit
                this.setSharedFlag(7, false);
                return;
            }

            int i = this.fallFlyTicks + 1;

            if (i % 10 == 0) {
                int j = i / 10;

                if (j % 2 == 0) {
                    List<EquipmentSlot> list = EquipmentSlot.VALUES.stream().filter((enumitemslot) -> {
                        return LivingEntity.canGlideUsing(this.getItemBySlot(enumitemslot), enumitemslot);
                    }).toList();
                    EquipmentSlot enumitemslot = (EquipmentSlot) Util.getRandom(list, this.random);

                    this.getItemBySlot(enumitemslot).hurtAndBreak(1, this, enumitemslot);
                }

                this.gameEvent(GameEvent.ELYTRA_GLIDE);
            }
        }

    }

    protected boolean canGlide() {
        if (!this.onGround() && !this.isPassenger() && !this.hasEffect(MobEffects.LEVITATION)) {
            Iterator iterator = EquipmentSlot.VALUES.iterator();

            EquipmentSlot enumitemslot;

            do {
                if (!iterator.hasNext()) {
                    return false;
                }

                enumitemslot = (EquipmentSlot) iterator.next();
            } while (!LivingEntity.canGlideUsing(this.getItemBySlot(enumitemslot), enumitemslot));

            return true;
        } else {
            return false;
        }
    }

    protected void serverAiStep() {}

    protected void pushEntities() {
        Level world = this.level();

        if (!(world instanceof ServerLevel worldserver)) {
            this.level().getEntities(EntityTypeTest.forClass(net.minecraft.world.entity.player.Player.class), this.getBoundingBox(), EntitySelector.pushableBy(this)).forEach(this::doPush);
        } else {
            // Paper start - don't run getEntities if we're not going to use its result
            if (!this.isPushable()) {
                return;
            }
            net.minecraft.world.scores.Team team = this.getTeam();
            if (team != null && team.getCollisionRule() == net.minecraft.world.scores.Team.CollisionRule.NEVER) {
                return;
            }

            int i = worldserver.getGameRules().getInt(GameRules.RULE_MAX_ENTITY_CRAMMING);
            if (i <= 0 && this.level().paperConfig().collisions.maxEntityCollisions <= 0) {
                return;
            }
            // Paper end - don't run getEntities if we're not going to use its result
            List list = this.level().getEntities((Entity) this, this.getBoundingBox(), EntitySelector.pushable(this, this.level().paperConfig().collisions.fixClimbingBypassingCrammingRule)); // Paper - Climbing should not bypass cramming gamerule

            if (!list.isEmpty()) {
                // Paper - don't run getEntities if we're not going to use its result; moved up

                if (i > 0 && list.size() > i - 1 && this.random.nextInt(4) == 0) {
                    int j = 0;
                    Iterator iterator = list.iterator();

                    while (iterator.hasNext()) {
                        Entity entity = (Entity) iterator.next();

                        if (!entity.isPassenger()) {
                            ++j;
                        }
                    }

                    if (j > i - 1) {
                        this.hurtServer(worldserver, this.damageSources().cramming(), 6.0F);
                    }
                }

                Iterator iterator1 = list.iterator();
                this.numCollisions = Math.max(0, this.numCollisions - this.level().paperConfig().collisions.maxEntityCollisions); // Paper - Cap entity collisions

                while (iterator1.hasNext() && this.numCollisions < this.level().paperConfig().collisions.maxEntityCollisions) { // Paper - Cap entity collisions
                    Entity entity1 = (Entity) iterator1.next();
                    entity1.numCollisions++; // Paper - Cap entity collisions
                    this.numCollisions++; // Paper - Cap entity collisions
                    this.doPush(entity1);
                }
            }

        }
    }

    protected void checkAutoSpinAttack(AABB a, AABB b) {
        AABB axisalignedbb2 = a.minmax(b);
        List<Entity> list = this.level().getEntities(this, axisalignedbb2);

        if (!list.isEmpty()) {
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                Entity entity = (Entity) iterator.next();

                if (entity instanceof LivingEntity) {
                    this.doAutoAttackOnTouch((LivingEntity) entity);
                    this.autoSpinAttackTicks = 0;
                    this.setDeltaMovement(this.getDeltaMovement().scale(-0.2D));
                    break;
                }
            }
        } else if (this.horizontalCollision) {
            this.autoSpinAttackTicks = 0;
        }

        if (!this.level().isClientSide && this.autoSpinAttackTicks <= 0) {
            this.setLivingEntityFlag(4, false);
            this.autoSpinAttackDmg = 0.0F;
            this.autoSpinAttackItemStack = null;
        }

    }

    protected void doPush(Entity entity) {
        entity.push((Entity) this);
    }

    protected void doAutoAttackOnTouch(LivingEntity target) {}

    public boolean isAutoSpinAttack() {
        return ((Byte) this.entityData.get(LivingEntity.DATA_LIVING_ENTITY_FLAGS) & 4) != 0;
    }

    @Override
    public void stopRiding() {
        // Paper start - Force entity dismount during teleportation
        this.stopRiding(false);
    }
    @Override
    public void stopRiding(boolean suppressCancellation) {
        // Paper end - Force entity dismount during teleportation
        Entity entity = this.getVehicle();

        super.stopRiding(suppressCancellation); // Paper - Force entity dismount during teleportation
        if (entity != null && entity != this.getVehicle() && !this.level().isClientSide && entity.valid) { // Paper - don't process on world gen
            this.dismountVehicle(entity);
        }

    }

    @Override
    public void rideTick() {
        super.rideTick();
        this.oRun = this.run;
        this.run = 0.0F;
        this.resetFallDistance();
    }

    @Override
    public void cancelLerp() {
        this.lerpSteps = 0;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int interpolationSteps) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = (double) yaw;
        this.lerpXRot = (double) pitch;
        this.lerpSteps = interpolationSteps;
    }

    @Override
    public double lerpTargetX() {
        return this.lerpSteps > 0 ? this.lerpX : this.getX();
    }

    @Override
    public double lerpTargetY() {
        return this.lerpSteps > 0 ? this.lerpY : this.getY();
    }

    @Override
    public double lerpTargetZ() {
        return this.lerpSteps > 0 ? this.lerpZ : this.getZ();
    }

    @Override
    public float lerpTargetXRot() {
        return this.lerpSteps > 0 ? (float) this.lerpXRot : this.getXRot();
    }

    @Override
    public float lerpTargetYRot() {
        return this.lerpSteps > 0 ? (float) this.lerpYRot : this.getYRot();
    }

    @Override
    public void lerpHeadTo(float yaw, int interpolationSteps) {
        this.lerpYHeadRot = (double) yaw;
        this.lerpHeadSteps = interpolationSteps;
    }

    public void setJumping(boolean jumping) {
        this.jumping = jumping;
    }

    public void onItemPickup(ItemEntity item) {
        Entity entity = item.thrower != null ? this.level().getGlobalPlayerByUUID(item.thrower) : null; // Paper - check global player list where appropriate

        if (entity instanceof ServerPlayer) {
            CriteriaTriggers.THROWN_ITEM_PICKED_UP_BY_ENTITY.trigger((ServerPlayer) entity, item.getItem(), this);
        }

    }

    public void take(Entity item, int count) {
        if (!item.isRemoved() && !this.level().isClientSide && (item instanceof ItemEntity || item instanceof AbstractArrow || item instanceof ExperienceOrb)) {
            ((ServerLevel) this.level()).getChunkSource().broadcastAndSend(this, new ClientboundTakeItemEntityPacket(item.getId(), this.getId(), count)); // Paper - broadcast with collector as source
        }

    }

    public boolean hasLineOfSight(Entity entity) {
        return this.hasLineOfSight(entity, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity.getEyeY());
    }

    public boolean hasLineOfSight(Entity entity, ClipContext.Block shapeType, ClipContext.Fluid fluidHandling, double entityY) {
        if (entity.level() != this.level()) {
            return false;
        } else {
            Vec3 vec3d = new Vec3(this.getX(), this.getEyeY(), this.getZ());
            Vec3 vec3d1 = new Vec3(entity.getX(), entityY, entity.getZ());

            // Paper - diff on change - used in CraftLivingEntity#hasLineOfSight(Location) and CraftWorld#lineOfSightExists
            return vec3d1.distanceToSqr(vec3d) > 128.0D * 128.0D ? false : this.level().clip(new ClipContext(vec3d, vec3d1, shapeType, fluidHandling, this)).getType() == HitResult.Type.MISS; // Paper - Perf: Use distance squared
        }
    }

    @Override
    public float getViewYRot(float tickDelta) {
        return tickDelta == 1.0F ? this.yHeadRot : Mth.rotLerp(tickDelta, this.yHeadRotO, this.yHeadRot);
    }

    public float getAttackAnim(float tickDelta) {
        float f1 = this.attackAnim - this.oAttackAnim;

        if (f1 < 0.0F) {
            ++f1;
        }

        return this.oAttackAnim + f1 * tickDelta;
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved() && this.collides; // CraftBukkit
    }

    // Paper start - Climbing should not bypass cramming gamerule
    @Override
    public boolean isPushable() {
        return this.isCollidable(this.level().paperConfig().collisions.fixClimbingBypassingCrammingRule);
    }

    @Override
    public boolean isCollidable(boolean ignoreClimbing) {
        return this.isAlive() && !this.isSpectator() && (ignoreClimbing || !this.onClimbable()) && this.collides; // CraftBukkit
        // Paper end - Climbing should not bypass cramming gamerule
    }

    // CraftBukkit start - collidable API
    @Override
    public boolean canCollideWithBukkit(Entity entity) {
        return this.isPushable() && this.collides != this.collidableExemptions.contains(entity.getUUID());
    }
    // CraftBukkit end

    @Override
    public float getYHeadRot() {
        return this.yHeadRot;
    }

    @Override
    public void setYHeadRot(float headYaw) {
        this.yHeadRot = headYaw;
    }

    @Override
    public void setYBodyRot(float bodyYaw) {
        this.yBodyRot = bodyYaw;
    }

    @Override
    public Vec3 getRelativePortalPosition(Direction.Axis portalAxis, BlockUtil.FoundRectangle portalRect) {
        return LivingEntity.resetForwardDirectionOfRelativePortalPosition(super.getRelativePortalPosition(portalAxis, portalRect));
    }

    public static Vec3 resetForwardDirectionOfRelativePortalPosition(Vec3 pos) {
        return new Vec3(pos.x, pos.y, 0.0D);
    }

    public float getAbsorptionAmount() {
        return this.absorptionAmount;
    }

    public final void setAbsorptionAmount(float absorptionAmount) {
        this.internalSetAbsorptionAmount(!Float.isNaN(absorptionAmount) ? Mth.clamp(absorptionAmount, 0.0F, this.getMaxAbsorption()) : 0.0F); // Paper - Check for NaN
    }

    protected void internalSetAbsorptionAmount(float absorptionAmount) {
        this.absorptionAmount = absorptionAmount;
    }

    public void onEnterCombat() {}

    public void onLeaveCombat() {}

    protected void updateEffectVisibility() {
        this.effectsDirty = true;
    }

    public abstract HumanoidArm getMainArm();

    public boolean isUsingItem() {
        return ((Byte) this.entityData.get(LivingEntity.DATA_LIVING_ENTITY_FLAGS) & 1) > 0;
    }

    public InteractionHand getUsedItemHand() {
        return ((Byte) this.entityData.get(LivingEntity.DATA_LIVING_ENTITY_FLAGS) & 2) > 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }

    // Paper start - Properly cancel usable items
    public void resyncUsingItem(ServerPlayer serverPlayer) {
        this.resendPossiblyDesyncedDataValues(java.util.List.of(DATA_LIVING_ENTITY_FLAGS), serverPlayer);
    }
    // Paper end - Properly cancel usable items
    // Paper start - lag compensate eating
    protected long eatStartTime;
    protected int totalEatTimeTicks;
    // Paper end - lag compensate eating
    private void updatingUsingItem() {
        if (this.isUsingItem()) {
            if (ItemStack.isSameItem(this.getItemInHand(this.getUsedItemHand()), this.useItem)) {
                this.useItem = this.getItemInHand(this.getUsedItemHand());
                this.updateUsingItem(this.useItem);
            } else {
                this.stopUsingItem();
            }
        }

    }

    protected void updateUsingItem(ItemStack stack) {
        stack.onUseTick(this.level(), this, this.getUseItemRemainingTicks());
        // Paper start - lag compensate eating
        // we add 1 to the expected time to avoid lag compensating when we should not
        final boolean shouldLagCompensate = this.useItem.has(DataComponents.FOOD) && this.eatStartTime != -1 && (System.nanoTime() - this.eatStartTime) > ((1L + this.totalEatTimeTicks) * 50L * (1000L * 1000L));
        if ((--this.useItemRemaining == 0 || shouldLagCompensate) && !this.level().isClientSide && !stack.useOnRelease()) {
            this.useItemRemaining = 0;
            // Paper end - lag compensate eating
            this.completeUsingItem();
        }

    }

    private void updateSwimAmount() {
        this.swimAmountO = this.swimAmount;
        if (this.isVisuallySwimming()) {
            this.swimAmount = Math.min(1.0F, this.swimAmount + 0.09F);
        } else {
            this.swimAmount = Math.max(0.0F, this.swimAmount - 0.09F);
        }

    }

    public void setLivingEntityFlag(int mask, boolean value) {
        int j = (Byte) this.entityData.get(LivingEntity.DATA_LIVING_ENTITY_FLAGS);

        if (value) {
            j |= mask;
        } else {
            j &= ~mask;
        }

        this.entityData.set(LivingEntity.DATA_LIVING_ENTITY_FLAGS, (byte) j);
    }

    public void startUsingItem(InteractionHand hand) {
        // Paper start - Prevent consuming the wrong itemstack
        this.startUsingItem(hand, false);
    }
    public void startUsingItem(InteractionHand hand, boolean forceUpdate) {
        // Paper end - Prevent consuming the wrong itemstack
        ItemStack itemstack = this.getItemInHand(hand);

        if (!itemstack.isEmpty() && !this.isUsingItem() || forceUpdate) { // Paper - Prevent consuming the wrong itemstack
            this.useItem = itemstack;
            // Paper start - lag compensate eating
            this.useItemRemaining = this.totalEatTimeTicks = itemstack.getUseDuration(this);
            this.eatStartTime = System.nanoTime();
            // Paper end - lag compensate eating
            if (!this.level().isClientSide) {
                this.setLivingEntityFlag(1, true);
                this.setLivingEntityFlag(2, hand == InteractionHand.OFF_HAND);
                this.gameEvent(GameEvent.ITEM_INTERACT_START);
            }

        }
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> data) {
        super.onSyncedDataUpdated(data);
        if (LivingEntity.SLEEPING_POS_ID.equals(data)) {
            if (this.level().isClientSide) {
                this.getSleepingPos().ifPresent(this::setPosToBed);
            }
        } else if (LivingEntity.DATA_LIVING_ENTITY_FLAGS.equals(data) && this.level().isClientSide) {
            if (this.isUsingItem() && this.useItem.isEmpty()) {
                this.useItem = this.getItemInHand(this.getUsedItemHand());
                if (!this.useItem.isEmpty()) {
                    this.useItemRemaining = this.useItem.getUseDuration(this);
                }
            } else if (!this.isUsingItem() && !this.useItem.isEmpty()) {
                this.useItem = ItemStack.EMPTY;
                // Paper start - lag compensate eating
                this.useItemRemaining = this.totalEatTimeTicks = 0;
                this.eatStartTime = -1L;
                // Paper end - lag compensate eating
            }
        }

    }

    @Override
    public void lookAt(EntityAnchorArgument.Anchor anchorPoint, Vec3 target) {
        super.lookAt(anchorPoint, target);
        this.yHeadRotO = this.yHeadRot;
        this.yBodyRot = this.yHeadRot;
        this.yBodyRotO = this.yBodyRot;
    }

    @Override
    public float getPreciseBodyRotation(float delta) {
        return Mth.lerp(delta, this.yBodyRotO, this.yBodyRot);
    }

    public void spawnItemParticles(ItemStack stack, int count) {
        for (int j = 0; j < count; ++j) {
            Vec3 vec3d = new Vec3(((double) this.random.nextFloat() - 0.5D) * 0.1D, Math.random() * 0.1D + 0.1D, 0.0D);

            vec3d = vec3d.xRot(-this.getXRot() * 0.017453292F);
            vec3d = vec3d.yRot(-this.getYRot() * 0.017453292F);
            double d0 = (double) (-this.random.nextFloat()) * 0.6D - 0.3D;
            Vec3 vec3d1 = new Vec3(((double) this.random.nextFloat() - 0.5D) * 0.3D, d0, 0.6D);

            vec3d1 = vec3d1.xRot(-this.getXRot() * 0.017453292F);
            vec3d1 = vec3d1.yRot(-this.getYRot() * 0.017453292F);
            vec3d1 = vec3d1.add(this.getX(), this.getEyeY(), this.getZ());
            this.level().addParticle(new ItemParticleOption(ParticleTypes.ITEM, stack), vec3d1.x, vec3d1.y, vec3d1.z, vec3d.x, vec3d.y + 0.05D, vec3d.z);
        }

    }

    public void completeUsingItem() {
        if (!this.level().isClientSide || this.isUsingItem()) {
            InteractionHand enumhand = this.getUsedItemHand();

            if (!this.useItem.equals(this.getItemInHand(enumhand))) {
                this.releaseUsingItem();
            } else {
                if (!this.useItem.isEmpty() && this.isUsingItem()) {
                    this.startUsingItem(this.getUsedItemHand(), true); // Paper - Prevent consuming the wrong itemstack
                    // CraftBukkit start - fire PlayerItemConsumeEvent
                    ItemStack itemstack;
                    PlayerItemConsumeEvent event = null; // Paper
                    if (this instanceof ServerPlayer entityPlayer) {
                        org.bukkit.inventory.ItemStack craftItem = CraftItemStack.asBukkitCopy(this.useItem);
                        org.bukkit.inventory.EquipmentSlot hand = org.bukkit.craftbukkit.CraftEquipmentSlot.getHand(enumhand);
                        event = new PlayerItemConsumeEvent((Player) this.getBukkitEntity(), craftItem, hand); // Paper
                        this.level().getCraftServer().getPluginManager().callEvent(event);

                        if (event.isCancelled()) {
                            // Update client
                            Consumable consumable = this.useItem.get(DataComponents.CONSUMABLE);
                            if (consumable != null) {
                                consumable.cancelUsingItem(entityPlayer, this.useItem);
                            }
                            entityPlayer.getBukkitEntity().updateInventory();
                            entityPlayer.getBukkitEntity().updateScaledHealth();
                            this.stopUsingItem(); // Paper - event is using an item, clear active item to reset its use
                            return;
                        }

                        itemstack = (craftItem.equals(event.getItem())) ? this.useItem.finishUsingItem(this.level(), this) : CraftItemStack.asNMSCopy(event.getItem()).finishUsingItem(this.level(), this);
                    } else {
                        itemstack = this.useItem.finishUsingItem(this.level(), this);
                    }
                    // Paper start - save the default replacement item and change it if necessary
                    final ItemStack defaultReplacement = itemstack;
                    if (event != null && event.getReplacement() != null) {
                        itemstack = CraftItemStack.asNMSCopy(event.getReplacement());
                    }
                    // Paper end
                    // CraftBukkit end

                    if (itemstack != this.useItem) {
                        this.setItemInHand(enumhand, itemstack);
                    }

                    this.stopUsingItem();
                    // Paper start
                    if (this instanceof ServerPlayer) {
                        ((ServerPlayer) this).getBukkitEntity().updateInventory();
                    }
                    // Paper end
                }

            }
        }
    }

    public void handleExtraItemsCreatedOnUse(ItemStack stack) {}

    public ItemStack getUseItem() {
        return this.useItem;
    }

    public int getUseItemRemainingTicks() {
        return this.useItemRemaining;
    }

    public int getTicksUsingItem() {
        return this.isUsingItem() ? this.useItem.getUseDuration(this) - this.getUseItemRemainingTicks() : 0;
    }

    public void releaseUsingItem() {
        if (!this.useItem.isEmpty()) {
            if (this instanceof ServerPlayer) new io.papermc.paper.event.player.PlayerStopUsingItemEvent((Player) getBukkitEntity(), useItem.asBukkitMirror(), getTicksUsingItem()).callEvent(); // Paper - Add PlayerStopUsingItemEvent
            this.useItem.releaseUsing(this.level(), this, this.getUseItemRemainingTicks());
            if (this.useItem.useOnRelease()) {
                this.updatingUsingItem();
            }
        }

        this.stopUsingItem();
    }

    public void stopUsingItem() {
        if (!this.level().isClientSide) {
            boolean flag = this.isUsingItem();

            this.setLivingEntityFlag(1, false);
            if (flag) {
                this.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
            }
        }

        this.useItem = ItemStack.EMPTY;
        // Paper start - lag compensate eating
        this.useItemRemaining = this.totalEatTimeTicks = 0;
        this.eatStartTime = -1L;
        // Paper end - lag compensate eating
    }

    public boolean isBlocking() {
        return this.getItemBlockingWith() != null;
    }

    @Nullable
    public ItemStack getItemBlockingWith() {
        if (this.isUsingItem() && !this.useItem.isEmpty()) {
            Item item = this.useItem.getItem();

            return item.getUseAnimation(this.useItem) != ItemUseAnimation.BLOCK ? null : (item.getUseDuration(this.useItem, this) - this.useItemRemaining < getShieldBlockingDelay() ? null : this.useItem); // Paper - Make shield blocking delay configurable
        } else {
            return null;
        }
    }

    // Paper start - Make shield blocking delay configurable
    public HitResult getRayTrace(int maxDistance, ClipContext.Fluid fluidCollisionOption) {
        if (maxDistance < 1 || maxDistance > 120) {
            throw new IllegalArgumentException("maxDistance must be between 1-120");
        }

        Vec3 start = new Vec3(getX(), getY() + getEyeHeight(), getZ());
        org.bukkit.util.Vector dir = getBukkitEntity().getLocation().getDirection().multiply(maxDistance);
        Vec3 end = new Vec3(start.x + dir.getX(), start.y + dir.getY(), start.z + dir.getZ());
        ClipContext raytrace = new ClipContext(start, end, ClipContext.Block.OUTLINE, fluidCollisionOption, this);

        return this.level().clip(raytrace);
    }

    public @Nullable net.minecraft.world.phys.EntityHitResult getTargetEntity(int maxDistance) {
        if (maxDistance < 1 || maxDistance > 120) {
            throw new IllegalArgumentException("maxDistance must be between 1-120");
        }

        Vec3 start = this.getEyePosition(1.0F);
        Vec3 direction = this.getLookAngle();
        Vec3 end = start.add(direction.x * maxDistance, direction.y * maxDistance, direction.z * maxDistance);

        List<Entity> entityList = this.level().getEntities(this, getBoundingBox().expandTowards(direction.x * maxDistance, direction.y * maxDistance, direction.z * maxDistance).inflate(1.0D, 1.0D, 1.0D), EntitySelector.NO_SPECTATORS.and(Entity::isPickable));

        double distance = 0.0D;
        net.minecraft.world.phys.EntityHitResult result = null;

        for (Entity entity : entityList) {
            final double inflationAmount = (double) entity.getPickRadius();
            AABB aabb = entity.getBoundingBox().inflate(inflationAmount, inflationAmount, inflationAmount);
            Optional<Vec3> rayTraceResult = aabb.clip(start, end);

            if (rayTraceResult.isPresent()) {
                Vec3 rayTrace = rayTraceResult.get();
                double distanceTo = start.distanceToSqr(rayTrace);
                if (distanceTo < distance || distance == 0.0D) {
                    result = new net.minecraft.world.phys.EntityHitResult(entity, rayTrace);
                    distance = distanceTo;
                }
            }
        }

        return result;
    }

    public int shieldBlockingDelay = this.level().paperConfig().misc.shieldBlockingDelay;

    public int getShieldBlockingDelay() {
        return shieldBlockingDelay;
    }

    public void setShieldBlockingDelay(int shieldBlockingDelay) {
        this.shieldBlockingDelay = shieldBlockingDelay;
    }
    // Paper end - Make shield blocking delay configurable

    public boolean isSuppressingSlidingDownLadder() {
        return this.isShiftKeyDown();
    }

    public boolean isFallFlying() {
        return this.getSharedFlag(7);
    }

    @Override
    public boolean isVisuallySwimming() {
        return super.isVisuallySwimming() || !this.isFallFlying() && this.hasPose(Pose.FALL_FLYING);
    }

    public int getFallFlyingTicks() {
        return this.fallFlyTicks;
    }

    public boolean randomTeleport(double x, double y, double z, boolean particleEffects) {
        // CraftBukkit start
        return this.randomTeleport(x, y, z, particleEffects, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN).orElse(false);
    }

    public Optional<Boolean> randomTeleport(double d0, double d1, double d2, boolean flag, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause) {
        // CraftBukkit end
        double d3 = this.getX();
        double d4 = this.getY();
        double d5 = this.getZ();
        double d6 = d1;
        boolean flag1 = false;
        BlockPos blockposition = BlockPos.containing(d0, d1, d2);
        Level world = this.level();

        if (ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor((ServerLevel)world, blockposition) && world.hasChunkAt(blockposition)) { // Folia - region threading
            boolean flag2 = false;

            while (!flag2 && blockposition.getY() > world.getMinY()) {
                BlockPos blockposition1 = blockposition.below();
                BlockState iblockdata = world.getBlockState(blockposition1);

                if (iblockdata.blocksMotion()) {
                    flag2 = true;
                } else {
                    --d6;
                    blockposition = blockposition1;
                }
            }

            if (flag2) {
                // CraftBukkit start - Teleport event
                // this.teleportTo(d0, d6, d2);

                // first set position, to check if the place to teleport is valid
                this.setPos(d0, d6, d2);
                if (world.noCollision((Entity) this) && !world.containsAnyLiquid(this.getBoundingBox())) {
                    flag1 = true;
                }
                // now revert and call event if the teleport place is valid
                this.setPos(d3, d4, d5);

                if (flag1) {
                    if (!(this instanceof ServerPlayer)) {
                        EntityTeleportEvent teleport = new EntityTeleportEvent(this.getBukkitEntity(), new Location(this.level().getWorld(), d3, d4, d5), new Location(this.level().getWorld(), d0, d6, d2));
                        this.level().getCraftServer().getPluginManager().callEvent(teleport);
                        if (!teleport.isCancelled() && teleport.getTo() != null) { // Paper
                            Location to = teleport.getTo();
                            this.teleportTo(to.getX(), to.getY(), to.getZ());
                        } else {
                            return Optional.empty();
                        }
                    } else {
                        // player teleport event is called in the underlining code
                        if (!((ServerPlayer) this).connection.teleport(d0, d6, d2, this.getYRot(), this.getXRot(), cause)) {
                            return Optional.empty();
                        }
                    }
                }
                // CraftBukkit end
            }
        }

        if (!flag1) {
            // this.enderTeleportTo(d3, d4, d5); // CraftBukkit - already set the location back
            return Optional.of(false); // CraftBukkit
        } else {
            if (flag) {
                world.broadcastEntityEvent(this, (byte) 46);
            }

            if (this instanceof PathfinderMob) {
                PathfinderMob entitycreature = (PathfinderMob) this;

                entitycreature.getNavigation().stop();
            }

            return Optional.of(true); // CraftBukkit
        }
    }

    public boolean isAffectedByPotions() {
        return !this.isDeadOrDying();
    }

    public boolean attackable() {
        return true;
    }

    public void setRecordPlayingNearby(BlockPos songPosition, boolean playing) {}

    public boolean canPickUpLoot() {
        return false;
    }

    @Override
    public final EntityDimensions getDimensions(Pose pose) {
        return pose == Pose.SLEEPING ? LivingEntity.SLEEPING_DIMENSIONS : this.getDefaultDimensions(pose).scale(this.getScale());
    }

    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return this.getType().getDimensions().scale(this.getAgeScale());
    }

    public ImmutableList<Pose> getDismountPoses() {
        return ImmutableList.of(Pose.STANDING);
    }

    public AABB getLocalBoundsForPose(Pose pose) {
        EntityDimensions entitysize = this.getDimensions(pose);

        return new AABB((double) (-entitysize.width() / 2.0F), 0.0D, (double) (-entitysize.width() / 2.0F), (double) (entitysize.width() / 2.0F), (double) entitysize.height(), (double) (entitysize.width() / 2.0F));
    }

    protected boolean wouldNotSuffocateAtTargetPose(Pose pose) {
        AABB axisalignedbb = this.getDimensions(pose).makeBoundingBox(this.position());

        return this.level().noBlockCollision(this, axisalignedbb);
    }

    @Override
    public boolean canUsePortal(boolean allowVehicles) {
        return super.canUsePortal(allowVehicles) && !this.isSleeping();
    }

    public Optional<BlockPos> getSleepingPos() {
        return (Optional) this.entityData.get(LivingEntity.SLEEPING_POS_ID);
    }

    public void setSleepingPos(BlockPos pos) {
        this.entityData.set(LivingEntity.SLEEPING_POS_ID, Optional.of(pos));
    }

    public void clearSleepingPos() {
        this.entityData.set(LivingEntity.SLEEPING_POS_ID, Optional.empty());
    }

    public boolean isSleeping() {
        return this.getSleepingPos().isPresent();
    }

    public void startSleeping(BlockPos pos) {
        if (this.isPassenger()) {
            this.stopRiding();
        }

        BlockState iblockdata = this.level().getBlockState(pos);

        if (iblockdata.getBlock() instanceof BedBlock) {
            this.level().setBlock(pos, (BlockState) iblockdata.setValue(BedBlock.OCCUPIED, true), 3);
        }

        this.setPose(Pose.SLEEPING);
        this.setPosToBed(pos);
        this.setSleepingPos(pos);
        this.setDeltaMovement(Vec3.ZERO);
        this.hasImpulse = true;
    }

    private void setPosToBed(BlockPos pos) {
        this.setPos((double) pos.getX() + 0.5D, (double) pos.getY() + 0.6875D, (double) pos.getZ() + 0.5D);
    }

    private boolean checkBedExists() {
        return (Boolean) this.getSleepingPos().map((blockposition) -> {
            return this.level().getBlockState(blockposition).getBlock() instanceof BedBlock;
        }).orElse(false);
    }

    public void stopSleeping() {
        Optional<BlockPos> optional = this.getSleepingPos(); // CraftBukkit - decompile error
        Level world = this.level();

        java.util.Objects.requireNonNull(world);
        optional.filter(world::hasChunkAt).ifPresent((blockposition) -> {
            BlockState iblockdata = this.level().getBlockState(blockposition);

            if (iblockdata.getBlock() instanceof BedBlock) {
                Direction enumdirection = (Direction) iblockdata.getValue(BedBlock.FACING);

                this.level().setBlock(blockposition, (BlockState) iblockdata.setValue(BedBlock.OCCUPIED, false), 3);
                Vec3 vec3d = (Vec3) BedBlock.findStandUpPosition(this.getType(), this.level(), blockposition, enumdirection, this.getYRot()).orElseGet(() -> {
                    BlockPos blockposition1 = blockposition.above();

                    return new Vec3((double) blockposition1.getX() + 0.5D, (double) blockposition1.getY() + 0.1D, (double) blockposition1.getZ() + 0.5D);
                });
                Vec3 vec3d1 = Vec3.atBottomCenterOf(blockposition).subtract(vec3d).normalize();
                float f = (float) Mth.wrapDegrees(Mth.atan2(vec3d1.z, vec3d1.x) * 57.2957763671875D - 90.0D);

                this.setPos(vec3d.x, vec3d.y, vec3d.z);
                this.setYRot(f);
                this.setXRot(0.0F);
            }

        });
        // Folia start - separate out
        this.stopSleepingRaw();
    }
    public void stopSleepingRaw() {
        // Folia end - separate out
        Vec3 vec3d = this.position();

        this.setPose(Pose.STANDING);
        this.setPos(vec3d.x, vec3d.y, vec3d.z);
        this.clearSleepingPos();
    }

    @Nullable
    public Direction getBedOrientation() {
        BlockPos blockposition = (BlockPos) this.getSleepingPos().orElse(null); // CraftBukkit - decompile error

        return blockposition != null ? BedBlock.getBedOrientation(this.level(), blockposition) : null;
    }

    @Override
    public boolean isInWall() {
        return !this.isSleeping() && super.isInWall();
    }

    public ItemStack getProjectile(ItemStack stack) {
        return ItemStack.EMPTY;
    }

    public static byte entityEventForEquipmentBreak(EquipmentSlot slot) {
        byte b0;

        switch (slot) {
            case MAINHAND:
                b0 = 47;
                break;
            case OFFHAND:
                b0 = 48;
                break;
            case HEAD:
                b0 = 49;
                break;
            case CHEST:
                b0 = 50;
                break;
            case FEET:
                b0 = 52;
                break;
            case LEGS:
                b0 = 51;
                break;
            case BODY:
                b0 = 65;
                break;
            default:
                throw new MatchException((String) null, (Throwable) null);
        }

        return b0;
    }

    public void onEquippedItemBroken(Item item, EquipmentSlot slot) {
        this.level().broadcastEntityEvent(this, LivingEntity.entityEventForEquipmentBreak(slot));
        this.stopLocationBasedEffects(this.getItemBySlot(slot), slot, this.attributes);
    }

    private void stopLocationBasedEffects(ItemStack removedEquipment, EquipmentSlot slot, AttributeMap container) {
        removedEquipment.forEachModifier(slot, (holder, attributemodifier) -> {
            AttributeInstance attributemodifiable = container.getInstance(holder);

            if (attributemodifiable != null) {
                attributemodifiable.removeModifier(attributemodifier);
            }

        });
        EnchantmentHelper.stopLocationBasedEffects(removedEquipment, this, slot);
    }

    public static EquipmentSlot getSlotForHand(InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
    }

    public final boolean canEquipWithDispenser(ItemStack stack) {
        if (this.isAlive() && !this.isSpectator()) {
            Equippable equippable = (Equippable) stack.get(DataComponents.EQUIPPABLE);

            if (equippable != null && equippable.dispensable()) {
                EquipmentSlot enumitemslot = equippable.slot();

                return this.canUseSlot(enumitemslot) && equippable.canBeEquippedBy(this.getType()) ? this.getItemBySlot(enumitemslot).isEmpty() && this.canDispenserEquipIntoSlot(enumitemslot) : false;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    protected boolean canDispenserEquipIntoSlot(EquipmentSlot slot) {
        return true;
    }

    public final EquipmentSlot getEquipmentSlotForItem(ItemStack stack) {
        Equippable equippable = (Equippable) stack.get(DataComponents.EQUIPPABLE);

        return equippable != null && this.canUseSlot(equippable.slot()) ? equippable.slot() : EquipmentSlot.MAINHAND;
    }

    public final boolean isEquippableInSlot(ItemStack stack, EquipmentSlot slot) {
        Equippable equippable = (Equippable) stack.get(DataComponents.EQUIPPABLE);

        return equippable == null ? slot == EquipmentSlot.MAINHAND && this.canUseSlot(EquipmentSlot.MAINHAND) : slot == equippable.slot() && this.canUseSlot(equippable.slot()) && equippable.canBeEquippedBy(this.getType());
    }

    private static SlotAccess createEquipmentSlotAccess(LivingEntity entity, EquipmentSlot slot) {
        return slot != EquipmentSlot.HEAD && slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND ? SlotAccess.forEquipmentSlot(entity, slot, (itemstack) -> {
            return itemstack.isEmpty() || entity.getEquipmentSlotForItem(itemstack) == slot;
        }) : SlotAccess.forEquipmentSlot(entity, slot);
    }

    @Nullable
    private static EquipmentSlot getEquipmentSlot(int slotId) {
        return slotId == 100 + EquipmentSlot.HEAD.getIndex() ? EquipmentSlot.HEAD : (slotId == 100 + EquipmentSlot.CHEST.getIndex() ? EquipmentSlot.CHEST : (slotId == 100 + EquipmentSlot.LEGS.getIndex() ? EquipmentSlot.LEGS : (slotId == 100 + EquipmentSlot.FEET.getIndex() ? EquipmentSlot.FEET : (slotId == 98 ? EquipmentSlot.MAINHAND : (slotId == 99 ? EquipmentSlot.OFFHAND : (slotId == 105 ? EquipmentSlot.BODY : null))))));
    }

    @Override
    public SlotAccess getSlot(int mappedIndex) {
        EquipmentSlot enumitemslot = LivingEntity.getEquipmentSlot(mappedIndex);

        return enumitemslot != null ? LivingEntity.createEquipmentSlotAccess(this, enumitemslot) : super.getSlot(mappedIndex);
    }

    @Override
    public boolean canFreeze() {
        if (this.isSpectator()) {
            return false;
        } else {
            boolean flag = !this.getItemBySlot(EquipmentSlot.HEAD).is(ItemTags.FREEZE_IMMUNE_WEARABLES) && !this.getItemBySlot(EquipmentSlot.CHEST).is(ItemTags.FREEZE_IMMUNE_WEARABLES) && !this.getItemBySlot(EquipmentSlot.LEGS).is(ItemTags.FREEZE_IMMUNE_WEARABLES) && !this.getItemBySlot(EquipmentSlot.FEET).is(ItemTags.FREEZE_IMMUNE_WEARABLES) && !this.getItemBySlot(EquipmentSlot.BODY).is(ItemTags.FREEZE_IMMUNE_WEARABLES);

            return flag && super.canFreeze();
        }
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return !this.level().isClientSide() && this.hasEffect(MobEffects.GLOWING) || super.isCurrentlyGlowing();
    }

    @Override
    public float getVisualRotationYInDegrees() {
        return this.yBodyRot;
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        double d0 = packet.getX();
        double d1 = packet.getY();
        double d2 = packet.getZ();
        float f = packet.getYRot();
        float f1 = packet.getXRot();

        this.syncPacketPositionCodec(d0, d1, d2);
        this.yBodyRot = packet.getYHeadRot();
        this.yHeadRot = packet.getYHeadRot();
        this.yBodyRotO = this.yBodyRot;
        this.yHeadRotO = this.yHeadRot;
        this.setId(packet.getId());
        this.setUUID(packet.getUUID());
        this.absMoveTo(d0, d1, d2, f, f1);
        this.setDeltaMovement(packet.getXa(), packet.getYa(), packet.getZa());
    }

    public boolean canDisableShield() {
        return this.getWeaponItem().getItem() instanceof AxeItem;
    }

    @Override
    public float maxUpStep() {
        float f = (float) this.getAttributeValue(Attributes.STEP_HEIGHT);

        return this.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player ? Math.max(f, 1.0F) : f;
    }

    @Override
    public Vec3 getPassengerRidingPosition(Entity passenger) {
        return this.position().add(this.getPassengerAttachmentPoint(passenger, this.getDimensions(this.getPose()), this.getScale() * this.getAgeScale()));
    }

    protected void lerpHeadRotationStep(int headTrackingIncrements, double serverHeadYaw) {
        this.yHeadRot = (float) Mth.rotLerp(1.0D / (double) headTrackingIncrements, (double) this.yHeadRot, serverHeadYaw);
    }

    @Override
    public void igniteForTicks(int ticks) {
        super.igniteForTicks(Mth.ceil((double) ticks * this.getAttributeValue(Attributes.BURNING_TIME)));
    }

    public boolean hasInfiniteMaterials() {
        return false;
    }

    public boolean isInvulnerableTo(ServerLevel world, DamageSource source) {
        return this.isInvulnerableToBase(source) || EnchantmentHelper.isImmuneToDamage(world, this, source);
    }

    public static boolean canGlideUsing(ItemStack stack, EquipmentSlot slot) {
        if (!stack.has(DataComponents.GLIDER)) {
            return false;
        } else {
            Equippable equippable = (Equippable) stack.get(DataComponents.EQUIPPABLE);

            return equippable != null && slot == equippable.slot() && !stack.nextDamageWillBreak();
        }
    }

    @VisibleForTesting
    public int getLastHurtByPlayerTime() {
        return this.lastHurtByPlayerTime;
    }

    public static record Fallsounds(SoundEvent small, SoundEvent big) {

    }
}
