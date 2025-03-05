package net.minecraft.world.entity.vehicle;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
// CraftBukkit start
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
// CraftBukkit end

public class MinecartTNT extends AbstractMinecart {

    private static final byte EVENT_PRIME = 10;
    private static final String TAG_EXPLOSION_POWER = "explosion_power";
    private static final String TAG_EXPLOSION_SPEED_FACTOR = "explosion_speed_factor";
    private static final String TAG_FUSE = "fuse";
    private static final float DEFAULT_EXPLOSION_POWER_BASE = 4.0F;
    private static final float DEFAULT_EXPLOSION_SPEED_FACTOR = 1.0F;
    public int fuse = -1;
    public float explosionPowerBase = 4.0F;
    public float explosionSpeedFactor = 1.0F;
    public boolean isIncendiary = false; // CraftBukkit - add field

    public MinecartTNT(EntityType<? extends MinecartTNT> type, Level world) {
        super(type, world);
    }

    @Override
    public BlockState getDefaultDisplayBlockState() {
        return Blocks.TNT.defaultBlockState();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.fuse > 0) {
            // Paper start - Configurable TNT height nerf
            if (this.level().paperConfig().fixes.tntEntityHeightNerf.test(v -> this.getY() > v)) {
                this.discard(EntityRemoveEvent.Cause.OUT_OF_WORLD);
                return;
            }
            // Paper end - Configurable TNT height nerf
            --this.fuse;
            this.level().addParticle(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.5D, this.getZ(), 0.0D, 0.0D, 0.0D);
        } else if (this.fuse == 0) {
            this.explode(this.getDeltaMovement().horizontalDistanceSqr());
        }

        if (this.horizontalCollision) {
            double d0 = this.getDeltaMovement().horizontalDistanceSqr();

            if (d0 >= 0.009999999776482582D) {
                this.explode(d0);
            }
        }

    }

    @Override
    public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        Entity entity = source.getDirectEntity();

        if (entity instanceof Projectile iprojectile) {
            if (iprojectile.isOnFire()) {
                DamageSource damagesource1 = this.damageSources().explosion(this, source.getEntity());

                this.explode(damagesource1, iprojectile.getDeltaMovement().lengthSqr());
            }
        }

        return super.hurtServer(world, source, amount);
    }

    @Override
    public void destroy(ServerLevel world, DamageSource damageSource) {
        double d0 = this.getDeltaMovement().horizontalDistanceSqr();

        if (!MinecartTNT.damageSourceIgnitesTnt(damageSource) && d0 < 0.009999999776482582D) {
            this.destroy(world, this.getDropItem());
        } else {
            if (this.fuse < 0) {
                this.primeFuse();
                this.fuse = this.random.nextInt(20) + this.random.nextInt(20);
            }

        }
    }

    @Override
    protected Item getDropItem() {
        return Items.TNT_MINECART;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(Items.TNT_MINECART);
    }

    public void explode(double power) {
        this.explode((DamageSource) null, power);
    }

    protected void explode(@Nullable DamageSource damageSource, double power) {
        Level world = this.level();

        if (world instanceof ServerLevel worldserver) {
            double d1 = Math.min(Math.sqrt(power), 5.0D);

            // CraftBukkit start
            ExplosionPrimeEvent event = new ExplosionPrimeEvent(this.getBukkitEntity(), (float) ((double) this.explosionPowerBase + (double) this.explosionSpeedFactor * this.random.nextDouble() * 1.5D * d1), this.isIncendiary);
            worldserver.getCraftServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                this.fuse = -1;
                return;
            }
            worldserver.explode(this, damageSource, (ExplosionDamageCalculator) null, this.getX(), this.getY(), this.getZ(), event.getRadius(), event.getFire(), Level.ExplosionInteraction.TNT);
            // CraftBukkit end
            this.discard(EntityRemoveEvent.Cause.EXPLODE); // CraftBukkit - add Bukkit remove cause
        }

    }

    @Override
    public boolean causeFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        if (fallDistance >= 3.0F) {
            float f2 = fallDistance / 10.0F;

            this.explode((double) (f2 * f2));
        }

        return super.causeFallDamage(fallDistance, damageMultiplier, damageSource);
    }

    @Override
    public void activateMinecart(int x, int y, int z, boolean powered) {
        if (powered && this.fuse < 0) {
            this.primeFuse();
        }

    }

    @Override
    public void handleEntityEvent(byte status) {
        if (status == 10) {
            this.primeFuse();
        } else {
            super.handleEntityEvent(status);
        }

    }

    public void primeFuse() {
        this.fuse = 80;
        if (!this.level().isClientSide) {
            this.level().broadcastEntityEvent(this, (byte) 10);
            if (!this.isSilent()) {
                this.level().playSound((Player) null, this.getX(), this.getY(), this.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }

    }

    public int getFuse() {
        return this.fuse;
    }

    public boolean isPrimed() {
        return this.fuse > -1;
    }

    @Override
    public float getBlockExplosionResistance(Explosion explosion, BlockGetter world, BlockPos pos, BlockState blockState, FluidState fluidState, float max) {
        return this.isPrimed() && (blockState.is(BlockTags.RAILS) || world.getBlockState(pos.above()).is(BlockTags.RAILS)) ? 0.0F : super.getBlockExplosionResistance(explosion, world, pos, blockState, fluidState, max);
    }

    @Override
    public boolean shouldBlockExplode(Explosion explosion, BlockGetter world, BlockPos pos, BlockState state, float explosionPower) {
        return this.isPrimed() && (state.is(BlockTags.RAILS) || world.getBlockState(pos.above()).is(BlockTags.RAILS)) ? false : super.shouldBlockExplode(explosion, world, pos, state, explosionPower);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag nbt) {
        super.readAdditionalSaveData(nbt);
        if (nbt.contains("fuse", 99)) {
            this.fuse = nbt.getInt("fuse");
        }

        if (nbt.contains("explosion_power", 99)) {
            this.explosionPowerBase = Mth.clamp(nbt.getFloat("explosion_power"), 0.0F, 128.0F);
        }

        if (nbt.contains("explosion_speed_factor", 99)) {
            this.explosionSpeedFactor = Mth.clamp(nbt.getFloat("explosion_speed_factor"), 0.0F, 128.0F);
        }

    }

    @Override
    protected void addAdditionalSaveData(CompoundTag nbt) {
        super.addAdditionalSaveData(nbt);
        nbt.putInt("fuse", this.fuse);
        if (this.explosionPowerBase != 4.0F) {
            nbt.putFloat("explosion_power", this.explosionPowerBase);
        }

        if (this.explosionSpeedFactor != 1.0F) {
            nbt.putFloat("explosion_speed_factor", this.explosionSpeedFactor);
        }

    }

    @Override
    boolean shouldSourceDestroy(DamageSource source) {
        return MinecartTNT.damageSourceIgnitesTnt(source);
    }

    private static boolean damageSourceIgnitesTnt(DamageSource source) {
        return source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_EXPLOSION);
    }
}
