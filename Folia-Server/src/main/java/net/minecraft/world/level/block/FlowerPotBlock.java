package net.minecraft.world.level.block;

import com.google.common.collect.Maps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class FlowerPotBlock extends Block {
    public static final MapCodec<FlowerPotBlock> CODEC = RecordCodecBuilder.mapCodec(
        instance -> instance.group(BuiltInRegistries.BLOCK.byNameCodec().fieldOf("potted").forGetter(block -> block.potted), propertiesCodec())
                .apply(instance, FlowerPotBlock::new)
    );
    private static final Map<Block, Block> POTTED_BY_CONTENT = Maps.newHashMap();
    public static final float AABB_SIZE = 3.0F;
    protected static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 6.0, 11.0);
    private final Block potted;

    @Override
    public MapCodec<FlowerPotBlock> codec() {
        return CODEC;
    }

    public FlowerPotBlock(Block content, BlockBehaviour.Properties settings) {
        super(settings);
        this.potted = content;
        POTTED_BY_CONTENT.put(content, this);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockState blockState = (stack.getItem() instanceof BlockItem blockItem ? POTTED_BY_CONTENT.getOrDefault(blockItem.getBlock(), Blocks.AIR) : Blocks.AIR)
            .defaultBlockState();
        if (blockState.isAir()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else if (!this.isEmpty()) {
            return InteractionResult.CONSUME;
        } else {
            // Paper start - Add PlayerFlowerPotManipulateEvent
            org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(world, pos);
            org.bukkit.inventory.ItemStack placedStack = org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy(stack);

            io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent event = new io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent((org.bukkit.entity.Player) player.getBukkitEntity(), block, placedStack, true);
            if (!event.callEvent()) {
                // Update client
                player.containerMenu.sendAllDataToRemote();

                return InteractionResult.CONSUME;
            }
            // Paper end - Add PlayerFlowerPotManipulateEvent
            world.setBlock(pos, blockState, 3);
            world.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            player.awardStat(Stats.POT_FLOWER);
            stack.consume(1, player);
            return InteractionResult.SUCCESS;
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player, BlockHitResult hit) {
        if (this.isEmpty()) {
            return InteractionResult.CONSUME;
        } else {
            ItemStack itemStack = new ItemStack(this.potted);
            // Paper start - Add PlayerFlowerPotManipulateEvent
            org.bukkit.block.Block block = org.bukkit.craftbukkit.block.CraftBlock.at(world, pos);
            org.bukkit.inventory.ItemStack pottedStack = new org.bukkit.inventory.ItemStack(org.bukkit.craftbukkit.block.CraftBlockType.minecraftToBukkit(this.potted));

            io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent event = new io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent((org.bukkit.entity.Player) player.getBukkitEntity(), block, pottedStack, false);
            if (!event.callEvent()) {
                // Update client
                player.containerMenu.sendAllDataToRemote();

                return InteractionResult.PASS;
            }
            // Paper end - Add PlayerFlowerPotManipulateEvent
            if (!player.addItem(itemStack)) {
                player.drop(itemStack, false);
            }

            world.setBlock(pos, Blocks.FLOWER_POT.defaultBlockState(), 3);
            world.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
            return InteractionResult.SUCCESS;
        }
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader world, BlockPos pos, BlockState state, boolean includeData) {
        return this.isEmpty() ? super.getCloneItemStack(world, pos, state, includeData) : new ItemStack(this.potted);
    }

    private boolean isEmpty() {
        return this.potted == Blocks.AIR;
    }

    @Override
    protected BlockState updateShape(
        BlockState state,
        LevelReader world,
        ScheduledTickAccess tickView,
        BlockPos pos,
        Direction direction,
        BlockPos neighborPos,
        BlockState neighborState,
        RandomSource random
    ) {
        return direction == Direction.DOWN && !state.canSurvive(world, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(state, world, tickView, pos, direction, neighborPos, neighborState, random);
    }

    public Block getPotted() {
        return this.potted;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return state.is(Blocks.POTTED_OPEN_EYEBLOSSOM) || state.is(Blocks.POTTED_CLOSED_EYEBLOSSOM);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        if (this.isRandomlyTicking(state) && world.dimensionType().natural()) {
            boolean bl = this.potted == Blocks.OPEN_EYEBLOSSOM;
            boolean bl2 = CreakingHeartBlock.isNaturalNight(world);
            if (bl != bl2) {
                world.setBlock(pos, this.opposite(state), 3);
                EyeblossomBlock.Type type = EyeblossomBlock.Type.fromBoolean(bl).transform();
                type.spawnTransformParticle(world, pos, random);
                world.playSound(null, pos, type.longSwitchSound(), SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }

        super.randomTick(state, world, pos, random);
    }

    public BlockState opposite(BlockState state) {
        if (state.is(Blocks.POTTED_OPEN_EYEBLOSSOM)) {
            return Blocks.POTTED_CLOSED_EYEBLOSSOM.defaultBlockState();
        } else {
            return state.is(Blocks.POTTED_CLOSED_EYEBLOSSOM) ? Blocks.POTTED_OPEN_EYEBLOSSOM.defaultBlockState() : state;
        }
    }
}
