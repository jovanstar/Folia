package net.minecraft.core.cauldron;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
// CraftBukkit start
import org.bukkit.event.block.CauldronLevelChangeEvent;
// CraftBukkit end

public interface CauldronInteraction {

    Map<String, CauldronInteraction.InteractionMap> INTERACTIONS = new Object2ObjectArrayMap();
    // CraftBukkit start - decompile errors
    Codec<CauldronInteraction.InteractionMap> CODEC = Codec.stringResolver(CauldronInteraction.InteractionMap::name, CauldronInteraction.INTERACTIONS::get);
    CauldronInteraction.InteractionMap EMPTY = CauldronInteraction.newInteractionMap("empty");
    CauldronInteraction.InteractionMap WATER = CauldronInteraction.newInteractionMap("water");
    CauldronInteraction.InteractionMap LAVA = CauldronInteraction.newInteractionMap("lava");
    CauldronInteraction.InteractionMap POWDER_SNOW = CauldronInteraction.newInteractionMap("powder_snow");
    // CraftBukkit end

    static CauldronInteraction.InteractionMap newInteractionMap(String name) {
        Object2ObjectOpenHashMap<Item, CauldronInteraction> object2objectopenhashmap = new Object2ObjectOpenHashMap();

        object2objectopenhashmap.defaultReturnValue((iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        });
        CauldronInteraction.InteractionMap cauldroninteraction_a = new CauldronInteraction.InteractionMap(name, object2objectopenhashmap);

        CauldronInteraction.INTERACTIONS.put(name, cauldroninteraction_a);
        return cauldroninteraction_a;
    }

    InteractionResult interact(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection); // Paper - add hitDirection

    static void bootStrap() {
        Map<Item, CauldronInteraction> map = CauldronInteraction.EMPTY.map();

        CauldronInteraction.addDefaultInteractions(map);
        map.put(Items.POTION, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            PotionContents potioncontents = (PotionContents) itemstack.get(DataComponents.POTION_CONTENTS);

            if (potioncontents != null && potioncontents.is(Potions.WATER)) {
                if (!world.isClientSide) {
                    // CraftBukkit start
                    if (!LayeredCauldronBlock.changeLevel(iblockdata, world, blockposition, Blocks.WATER_CAULDRON.defaultBlockState(), entityhuman, CauldronLevelChangeEvent.ChangeReason.BOTTLE_EMPTY, false)) { // Paper - Call CauldronLevelChangeEvent
                        return InteractionResult.SUCCESS;
                    }
                    // CraftBukkit end
                    Item item = itemstack.getItem();

                    entityhuman.setItemInHand(enumhand, ItemUtils.createFilledResult(itemstack, entityhuman, new ItemStack(Items.GLASS_BOTTLE)));
                    entityhuman.awardStat(Stats.USE_CAULDRON);
                    entityhuman.awardStat(Stats.ITEM_USED.get(item));
                    // world.setBlockAndUpdate(blockposition, Blocks.WATER_CAULDRON.defaultBlockState()); // CraftBukkit
                    world.playSound((Player) null, blockposition, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                    world.gameEvent((Entity) null, (Holder) GameEvent.FLUID_PLACE, blockposition);
                }

                return InteractionResult.SUCCESS;
            } else {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            }
        });
        Map<Item, CauldronInteraction> map1 = CauldronInteraction.WATER.map();

        CauldronInteraction.addDefaultInteractions(map1);
        map1.put(Items.BUCKET, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            return CauldronInteraction.fillBucket(iblockdata, world, blockposition, entityhuman, enumhand, itemstack, new ItemStack(Items.WATER_BUCKET), (iblockdata1) -> {
                return (Integer) iblockdata1.getValue(LayeredCauldronBlock.LEVEL) == 3;
            }, SoundEvents.BUCKET_FILL, hitDirection); // Paper - add hitDirection
        });
        map1.put(Items.GLASS_BOTTLE, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            if (!world.isClientSide) {
                // CraftBukkit start
                if (!LayeredCauldronBlock.lowerFillLevel(iblockdata, world, blockposition, entityhuman, CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL)) {
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                Item item = itemstack.getItem();

                entityhuman.setItemInHand(enumhand, ItemUtils.createFilledResult(itemstack, entityhuman, PotionContents.createItemStack(Items.POTION, Potions.WATER)));
                entityhuman.awardStat(Stats.USE_CAULDRON);
                entityhuman.awardStat(Stats.ITEM_USED.get(item));
                // LayeredCauldronBlock.lowerFillLevel(iblockdata, world, blockposition); // CraftBukkit
                world.playSound((Player) null, blockposition, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
                world.gameEvent((Entity) null, (Holder) GameEvent.FLUID_PICKUP, blockposition);
            }

            return InteractionResult.SUCCESS;
        });
        map1.put(Items.POTION, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            if ((Integer) iblockdata.getValue(LayeredCauldronBlock.LEVEL) == 3) {
                return InteractionResult.TRY_WITH_EMPTY_HAND;
            } else {
                PotionContents potioncontents = (PotionContents) itemstack.get(DataComponents.POTION_CONTENTS);

                if (potioncontents != null && potioncontents.is(Potions.WATER)) {
                    if (!world.isClientSide) {
                        // CraftBukkit start
                        if (!LayeredCauldronBlock.changeLevel(iblockdata, world, blockposition, iblockdata.cycle(LayeredCauldronBlock.LEVEL), entityhuman, CauldronLevelChangeEvent.ChangeReason.BOTTLE_EMPTY, false)) { // Paper - Call CauldronLevelChangeEvent
                            return InteractionResult.SUCCESS;
                        }
                        // CraftBukkit end
                        entityhuman.setItemInHand(enumhand, ItemUtils.createFilledResult(itemstack, entityhuman, new ItemStack(Items.GLASS_BOTTLE)));
                        entityhuman.awardStat(Stats.USE_CAULDRON);
                        entityhuman.awardStat(Stats.ITEM_USED.get(itemstack.getItem()));
                        // world.setBlockAndUpdate(blockposition, (IBlockData) iblockdata.cycle(LayeredCauldronBlock.LEVEL)); // CraftBukkit
                        world.playSound((Player) null, blockposition, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                        world.gameEvent((Entity) null, (Holder) GameEvent.FLUID_PLACE, blockposition);
                    }

                    return InteractionResult.SUCCESS;
                } else {
                    return InteractionResult.TRY_WITH_EMPTY_HAND;
                }
            }
        });
        map1.put(Items.LEATHER_BOOTS, CauldronInteraction::dyedItemIteration);
        map1.put(Items.LEATHER_LEGGINGS, CauldronInteraction::dyedItemIteration);
        map1.put(Items.LEATHER_CHESTPLATE, CauldronInteraction::dyedItemIteration);
        map1.put(Items.LEATHER_HELMET, CauldronInteraction::dyedItemIteration);
        map1.put(Items.LEATHER_HORSE_ARMOR, CauldronInteraction::dyedItemIteration);
        map1.put(Items.WOLF_ARMOR, CauldronInteraction::dyedItemIteration);
        map1.put(Items.WHITE_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.GRAY_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.BLACK_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.BLUE_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.BROWN_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.CYAN_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.GREEN_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.LIGHT_BLUE_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.LIGHT_GRAY_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.LIME_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.MAGENTA_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.ORANGE_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.PINK_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.PURPLE_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.RED_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.YELLOW_BANNER, CauldronInteraction::bannerInteraction);
        map1.put(Items.WHITE_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.GRAY_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.BLACK_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.BLUE_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.BROWN_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.CYAN_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.GREEN_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.LIGHT_BLUE_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.LIGHT_GRAY_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.LIME_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.MAGENTA_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.ORANGE_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.PINK_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.PURPLE_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.RED_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        map1.put(Items.YELLOW_SHULKER_BOX, CauldronInteraction::shulkerBoxInteraction);
        Map<Item, CauldronInteraction> map2 = CauldronInteraction.LAVA.map();

        map2.put(Items.BUCKET, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            return CauldronInteraction.fillBucket(iblockdata, world, blockposition, entityhuman, enumhand, itemstack, new ItemStack(Items.LAVA_BUCKET), (iblockdata1) -> {
                return true;
            }, SoundEvents.BUCKET_FILL_LAVA, hitDirection); // Paper - add hitDirection
        });
        CauldronInteraction.addDefaultInteractions(map2);
        Map<Item, CauldronInteraction> map3 = CauldronInteraction.POWDER_SNOW.map();

        map3.put(Items.BUCKET, (iblockdata, world, blockposition, entityhuman, enumhand, itemstack, hitDirection) -> { // Paper - add hitDirection
            return CauldronInteraction.fillBucket(iblockdata, world, blockposition, entityhuman, enumhand, itemstack, new ItemStack(Items.POWDER_SNOW_BUCKET), (iblockdata1) -> {
                return (Integer) iblockdata1.getValue(LayeredCauldronBlock.LEVEL) == 3;
            }, SoundEvents.BUCKET_FILL_POWDER_SNOW, hitDirection); // Paper - add hitDirection
        });
        CauldronInteraction.addDefaultInteractions(map3);
    }

    static void addDefaultInteractions(Map<Item, CauldronInteraction> behavior) {
        behavior.put(Items.LAVA_BUCKET, CauldronInteraction::fillLavaInteraction);
        behavior.put(Items.WATER_BUCKET, CauldronInteraction::fillWaterInteraction);
        behavior.put(Items.POWDER_SNOW_BUCKET, CauldronInteraction::fillPowderSnowInteraction);
    }

    static InteractionResult fillBucket(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, ItemStack output, Predicate<BlockState> fullPredicate, SoundEvent soundEvent) {
        // Paper start - add hitDirection
        return fillBucket(state, world, pos, player, hand, stack, output, fullPredicate, soundEvent, null); // Paper - add hitDirection
    }
    static InteractionResult fillBucket(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, ItemStack output, Predicate<BlockState> fullPredicate, SoundEvent soundEvent, @javax.annotation.Nullable net.minecraft.core.Direction hitDirection) {
        // Paper end - add hitDirection
        if (!fullPredicate.test(state)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else {
            if (!world.isClientSide) {
                // Paper start - fire PlayerBucketFillEvent
                if (hitDirection != null) {
                    org.bukkit.event.player.PlayerBucketEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerBucketFillEvent((net.minecraft.server.level.ServerLevel) world, player, pos, pos, hitDirection, stack, output.getItem(), hand);
                    if (event.isCancelled()) {
                        return InteractionResult.PASS;
                    }
                    output = event.getItemStack() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
                }
                // Paper end - fire PlayerBucketFillEvent
                // CraftBukkit start
                if (!LayeredCauldronBlock.changeLevel(state, world, pos, Blocks.CAULDRON.defaultBlockState(), player, CauldronLevelChangeEvent.ChangeReason.BUCKET_FILL, false)) { // Paper - Call CauldronLevelChangeEvent
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                Item item = stack.getItem();

                player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, output));
                player.awardStat(Stats.USE_CAULDRON);
                player.awardStat(Stats.ITEM_USED.get(item));
                // world.setBlockAndUpdate(blockposition, Blocks.CAULDRON.defaultBlockState()); // CraftBukkit
                world.playSound((Player) null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
                world.gameEvent((Entity) null, (Holder) GameEvent.FLUID_PICKUP, pos);
            }

            return InteractionResult.SUCCESS;
        }
    }

    static InteractionResult emptyBucket(Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, BlockState state, SoundEvent soundEvent) {
        // Paper start - add hitDirection
        return emptyBucket(world, pos, player, hand, stack, state, soundEvent, null);
    }
    static InteractionResult emptyBucket(Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, BlockState state, SoundEvent soundEvent, @javax.annotation.Nullable net.minecraft.core.Direction hitDirection) {
        // Paper end - add hitDirection
        if (!world.isClientSide) {
            // Paper start - fire PlayerBucketEmptyEvent
            ItemStack output = new ItemStack(Items.BUCKET);
            if (hitDirection != null) {
                org.bukkit.event.player.PlayerBucketEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callPlayerBucketEmptyEvent((net.minecraft.server.level.ServerLevel) world, player, pos, pos, hitDirection, stack, hand);
                if (event.isCancelled()) {
                    return InteractionResult.PASS;
                }
                output = event.getItemStack() != null ? org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(event.getItemStack()) : ItemStack.EMPTY;
            }
            // Paper end - fire PlayerBucketEmptyEvent
            // CraftBukkit start
            if (!LayeredCauldronBlock.changeLevel(state, world, pos, state, player, CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY, false)) { // Paper - Call CauldronLevelChangeEvent
                return InteractionResult.SUCCESS;
            }
            // CraftBukkit end
            Item item = stack.getItem();

            player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, output)); // Paper
            player.awardStat(Stats.FILL_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(item));
            // world.setBlockAndUpdate(blockposition, iblockdata); // CraftBukkit
            world.playSound((Player) null, pos, soundEvent, SoundSource.BLOCKS, 1.0F, 1.0F);
            world.gameEvent((Entity) null, (Holder) GameEvent.FLUID_PLACE, pos);
        }

        return InteractionResult.SUCCESS;
    }

    private static InteractionResult fillWaterInteraction(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        return CauldronInteraction.emptyBucket(world, pos, player, hand, stack, (BlockState) Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), SoundEvents.BUCKET_EMPTY, hitDirection); // Paper - add hitDirection
    }

    private static InteractionResult fillLavaInteraction(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        return (InteractionResult) (CauldronInteraction.isUnderWater(world, pos) ? InteractionResult.CONSUME : CauldronInteraction.emptyBucket(world, pos, player, hand, stack, Blocks.LAVA_CAULDRON.defaultBlockState(), SoundEvents.BUCKET_EMPTY_LAVA, hitDirection)); // Paper - add hitDirection
    }

    private static InteractionResult fillPowderSnowInteraction(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        return (InteractionResult) (CauldronInteraction.isUnderWater(world, pos) ? InteractionResult.CONSUME : CauldronInteraction.emptyBucket(world, pos, player, hand, stack, (BlockState) Blocks.POWDER_SNOW_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3), SoundEvents.BUCKET_EMPTY_POWDER_SNOW, hitDirection)); // Paper - add hitDirection
    }

    private static InteractionResult shulkerBoxInteraction(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        Block block = Block.byItem(stack.getItem());

        if (!(block instanceof ShulkerBoxBlock)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else {
            if (!world.isClientSide) {
                // CraftBukkit start
                if (!LayeredCauldronBlock.lowerFillLevel(state, world, pos, player, CauldronLevelChangeEvent.ChangeReason.SHULKER_WASH)) {
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                ItemStack itemstack1 = stack.transmuteCopy(Blocks.SHULKER_BOX, 1);

                player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, itemstack1, false));
                player.awardStat(Stats.CLEAN_SHULKER_BOX);
                // LayeredCauldronBlock.lowerFillLevel(iblockdata, world, blockposition); // CraftBukkit
            }
            return InteractionResult.SUCCESS;
        }
    }

    private static InteractionResult bannerInteraction(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        BannerPatternLayers bannerpatternlayers = (BannerPatternLayers) stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);

        if (bannerpatternlayers.layers().isEmpty()) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else {
            if (!world.isClientSide) {
                // CraftBukkit start
                if (!LayeredCauldronBlock.lowerFillLevel(state, world, pos, player, CauldronLevelChangeEvent.ChangeReason.BANNER_WASH)) {
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                ItemStack itemstack1 = stack.copyWithCount(1);

                itemstack1.set(DataComponents.BANNER_PATTERNS, bannerpatternlayers.removeLast());
                player.setItemInHand(hand, ItemUtils.createFilledResult(stack, player, itemstack1, false));
                player.awardStat(Stats.CLEAN_BANNER);
                // LayeredCauldronBlock.lowerFillLevel(iblockdata, world, blockposition); // CraftBukkit
            }

            return InteractionResult.SUCCESS;
        }
    }

    private static InteractionResult dyedItemIteration(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, ItemStack stack, final net.minecraft.core.Direction hitDirection) { // Paper - add hitDirection
        if (!stack.is(ItemTags.DYEABLE)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else if (!stack.has(DataComponents.DYED_COLOR)) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        } else {
            if (!world.isClientSide) {
                // CraftBukkit start
                if (!LayeredCauldronBlock.lowerFillLevel(state, world, pos, player, CauldronLevelChangeEvent.ChangeReason.ARMOR_WASH)) {
                    return InteractionResult.SUCCESS;
                }
                // CraftBukkit end
                stack.remove(DataComponents.DYED_COLOR);
                player.awardStat(Stats.CLEAN_ARMOR);
                // LayeredCauldronBlock.lowerFillLevel(iblockdata, world, blockposition); // CraftBukkit
            }

            return InteractionResult.SUCCESS;
        }
    }

    private static boolean isUnderWater(Level world, BlockPos pos) {
        FluidState fluid = world.getFluidState(pos.above());

        return fluid.is(FluidTags.WATER);
    }

    // CraftBukkit start - decompile errors
    /*
    static {
        Function function = CauldronInteraction.a::name;
        Map map = CauldronInteraction.INTERACTIONS;

        Objects.requireNonNull(map);
        CODEC = Codec.stringResolver(function, map::get);
        EMPTY = newInteractionMap("empty");
        WATER = newInteractionMap("water");
        LAVA = newInteractionMap("lava");
        POWDER_SNOW = newInteractionMap("powder_snow");
    }
     */
    // CraftBukkit end

    public static record InteractionMap(String name, Map<Item, CauldronInteraction> map) {

    }
}
