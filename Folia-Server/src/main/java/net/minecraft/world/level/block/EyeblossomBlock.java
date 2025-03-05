package net.minecraft.world.level.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

public class EyeblossomBlock extends FlowerBlock {
    public static final MapCodec<EyeblossomBlock> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(Codec.BOOL.fieldOf("open").forGetter(block -> block.type.open), propertiesCodec()).apply(instance, EyeblossomBlock::new)
    );
    private static final int EYEBLOSSOM_XZ_RANGE = 3;
    private static final int EYEBLOSSOM_Y_RANGE = 2;
    private final EyeblossomBlock.Type type;

    @Override
    public MapCodec<? extends EyeblossomBlock> codec() {
        return CODEC;
    }

    public EyeblossomBlock(EyeblossomBlock.Type state, BlockBehaviour.Properties settings) {
        super(state.effect, state.effectDuration, settings);
        this.type = state;
    }

    public EyeblossomBlock(boolean open, BlockBehaviour.Properties settings) {
        super(EyeblossomBlock.Type.fromBoolean(open).effect, EyeblossomBlock.Type.fromBoolean(open).effectDuration, settings);
        this.type = EyeblossomBlock.Type.fromBoolean(open);
    }

    @Override
    public void animateTick(BlockState state, Level world, BlockPos pos, RandomSource random) {
        if (this.type.emitSounds() && random.nextInt(700) == 0) {
            BlockState blockState = world.getBlockState(pos.below());
            if (blockState.is(Blocks.PALE_MOSS_BLOCK)) {
                world.playLocalSound(
                    (double)pos.getX(), (double)pos.getY(), (double)pos.getZ(), SoundEvents.EYEBLOSSOM_IDLE, SoundSource.BLOCKS, 1.0F, 1.0F, false
                );
            }
        }
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (this.tryChangingState(state, world, pos, random)) {
            world.playSound(null, pos, this.type.transform().longSwitchSound, SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        super.randomTick(state, world, pos, random);
    }

    @Override
    protected void tick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (this.tryChangingState(state, world, pos, random)) {
            world.playSound(null, pos, this.type.transform().shortSwitchSound, SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        super.tick(state, world, pos, random);
    }

    private boolean tryChangingState(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (!world.dimensionType().natural()) {
            return false;
        } else if (world.isDay() != this.type.open) {
            return false;
        } else {
            EyeblossomBlock.Type type = this.type.transform();
            world.setBlock(pos, type.state(), 3);
            world.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(state));
            type.spawnTransformParticle(world, pos, random);
            BlockPos.betweenClosed(pos.offset(-3, -2, -3), pos.offset(3, 2, 3)).forEach(otherPos -> {
                BlockState blockState2 = world.getBlockState(otherPos);
                if (blockState2 == state) {
                    double d = Math.sqrt(pos.distSqr(otherPos));
                    int i = random.nextIntBetweenInclusive((int)(d * 5.0), (int)(d * 10.0));
                    world.scheduleTick(otherPos, state.getBlock(), i);
                }
            });
            return true;
        }
    }

    @Override
    protected void entityInside(BlockState state, Level world, BlockPos pos, Entity entity) {
        if (!new io.papermc.paper.event.entity.EntityInsideBlockEvent(entity.getBukkitEntity(), org.bukkit.craftbukkit.block.CraftBlock.at(world, pos)).callEvent()) { return; } // Paper - Add EntityInsideBlockEvent
        if (!world.isClientSide()
            && world.getDifficulty() != Difficulty.PEACEFUL
            && entity instanceof Bee bee
            && Bee.attractsBees(state)
            && !bee.hasEffect(MobEffects.POISON)) {
            bee.addEffect(this.getBeeInteractionEffect());
        }
    }

    @Override
    public MobEffectInstance getBeeInteractionEffect() {
        return new MobEffectInstance(MobEffects.POISON, 25);
    }

    public static enum Type {
        OPEN(true, MobEffects.BLINDNESS, 11.0F, SoundEvents.EYEBLOSSOM_OPEN_LONG, SoundEvents.EYEBLOSSOM_OPEN, 16545810),
        CLOSED(false, MobEffects.CONFUSION, 7.0F, SoundEvents.EYEBLOSSOM_CLOSE_LONG, SoundEvents.EYEBLOSSOM_CLOSE, 6250335);

        final boolean open;
        final Holder<MobEffect> effect;
        final float effectDuration;
        final SoundEvent longSwitchSound;
        final SoundEvent shortSwitchSound;
        private final int particleColor;

        private Type(
            final boolean open,
            final Holder<MobEffect> stewEffect,
            final float effectLengthInSeconds,
            final SoundEvent longSound,
            final SoundEvent sound,
            final int particleColor
        ) {
            this.open = open;
            this.effect = stewEffect;
            this.effectDuration = effectLengthInSeconds;
            this.longSwitchSound = longSound;
            this.shortSwitchSound = sound;
            this.particleColor = particleColor;
        }

        public Block block() {
            return this.open ? Blocks.OPEN_EYEBLOSSOM : Blocks.CLOSED_EYEBLOSSOM;
        }

        public BlockState state() {
            return this.block().defaultBlockState();
        }

        public EyeblossomBlock.Type transform() {
            return fromBoolean(!this.open);
        }

        public boolean emitSounds() {
            return this.open;
        }

        public static EyeblossomBlock.Type fromBoolean(boolean open) {
            return open ? OPEN : CLOSED;
        }

        public void spawnTransformParticle(ServerLevel world, BlockPos pos, RandomSource random) {
            Vec3 vec3 = pos.getCenter();
            double d = 0.5 + random.nextDouble();
            Vec3 vec32 = new Vec3(random.nextDouble() - 0.5, random.nextDouble() + 1.0, random.nextDouble() - 0.5);
            Vec3 vec33 = vec3.add(vec32.scale(d));
            TrailParticleOption trailParticleOption = new TrailParticleOption(vec33, this.particleColor, (int)(20.0 * d));
            world.sendParticles(trailParticleOption, vec3.x, vec3.y, vec3.z, 1, 0.0, 0.0, 0.0, 0.0);
        }

        public SoundEvent longSwitchSound() {
            return this.longSwitchSound;
        }
    }
}
