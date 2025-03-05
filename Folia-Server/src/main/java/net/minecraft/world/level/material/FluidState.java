package net.minecraft.world.level.material;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class FluidState extends StateHolder<Fluid, FluidState> implements ca.spottedleaf.moonrise.patches.fluid.FluidFluidState { // Paper - fluid method optimisations
    public static final Codec<FluidState> CODEC = codec(BuiltInRegistries.FLUID.byNameCodec(), Fluid::defaultFluidState).stable();
    public static final int AMOUNT_MAX = 9;
    public static final int AMOUNT_FULL = 8;
    protected final boolean isEmpty; // Paper - Perf: moved from isEmpty()

    // Paper start - fluid method optimisations
    private int amount;
    //private boolean isEmpty;
    private boolean isSource;
    private float ownHeight;
    private boolean isRandomlyTicking;
    private BlockState legacyBlock;

    @Override
    public final void moonrise$initCaches() {
        this.amount = this.getType().getAmount((FluidState)(Object)this);
        //this.isEmpty = this.getType().isEmpty();
        this.isSource = this.getType().isSource((FluidState)(Object)this);
        this.ownHeight = this.getType().getOwnHeight((FluidState)(Object)this);
        this.isRandomlyTicking = this.getType().isRandomlyTicking();
    }
    // Paper end - fluid method optimisations

    public FluidState(Fluid fluid, Reference2ObjectArrayMap<Property<?>, Comparable<?>> propertyMap, MapCodec<FluidState> codec) {
        super(fluid, propertyMap, codec);
        this.isEmpty = fluid.isEmpty(); // Paper - Perf: moved from isEmpty()
    }

    public Fluid getType() {
        return this.owner;
    }

    public boolean isSource() {
        return this.isSource; // Paper - fluid method optimisations
    }

    public boolean isSourceOfType(Fluid fluid) {
        return this.isSource && this.owner == fluid;  // Paper - fluid method optimisations
    }

    public boolean isEmpty() {
        return this.isEmpty; // Paper - Perf: moved into constructor
    }

    public float getHeight(BlockGetter world, BlockPos pos) {
        return this.getType().getHeight(this, world, pos);
    }

    public float getOwnHeight() {
        return this.ownHeight; // Paper - fluid method optimisations
    }

    public int getAmount() {
        return this.amount; // Paper - fluid method optimisations
    }

    public boolean shouldRenderBackwardUpFace(BlockGetter world, BlockPos pos) {
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                BlockPos blockPos = pos.offset(i, 0, j);
                FluidState fluidState = world.getFluidState(blockPos);
                if (!fluidState.getType().isSame(this.getType()) && !world.getBlockState(blockPos).isSolidRender()) {
                    return true;
                }
            }
        }

        return false;
    }

    public void tick(ServerLevel world, BlockPos pos, BlockState state) {
        this.getType().tick(world, pos, state, this);
    }

    public void animateTick(Level world, BlockPos pos, RandomSource random) {
        this.getType().animateTick(world, pos, this, random);
    }

    public boolean isRandomlyTicking() {
        return this.isRandomlyTicking; // Paper - fluid method optimisations
    }

    public void randomTick(ServerLevel world, BlockPos pos, RandomSource random) {
        this.getType().randomTick(world, pos, this, random);
    }

    public Vec3 getFlow(BlockGetter world, BlockPos pos) {
        return this.getType().getFlow(world, pos, this);
    }

    public BlockState createLegacyBlock() {
        // Paper start - fluid method optimisations
        if (this.legacyBlock != null) {
            return this.legacyBlock;
        }
        return this.legacyBlock = this.getType().createLegacyBlock((FluidState)(Object)this);
        // Paper end - fluid method optimisations
    }

    @Nullable
    public ParticleOptions getDripParticle() {
        return this.getType().getDripParticle();
    }

    public boolean is(TagKey<Fluid> tag) {
        return this.getType().builtInRegistryHolder().is(tag);
    }

    public boolean is(HolderSet<Fluid> fluids) {
        return fluids.contains(this.getType().builtInRegistryHolder());
    }

    public boolean is(Fluid fluid) {
        return this.getType() == fluid;
    }

    public float getExplosionResistance() {
        return this.getType().getExplosionResistance();
    }

    public boolean canBeReplacedWith(BlockGetter world, BlockPos pos, Fluid fluid, Direction direction) {
        return this.getType().canBeReplacedWith(this, world, pos, fluid, direction);
    }

    public VoxelShape getShape(BlockGetter world, BlockPos pos) {
        return this.getType().getShape(this, world, pos);
    }

    public Holder<Fluid> holder() {
        return this.owner.builtInRegistryHolder();
    }

    public Stream<TagKey<Fluid>> getTags() {
        return this.owner.builtInRegistryHolder().tags();
    }
}
