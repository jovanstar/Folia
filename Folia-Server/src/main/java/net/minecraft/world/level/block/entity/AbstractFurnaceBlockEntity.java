package net.minecraft.world.level.block.entity;

import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2IntMap.Entry;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.Iterator;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.RecipeCraftingHolder;
import net.minecraft.world.inventory.StackedContentsCompatible;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
// CraftBukkit start
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.entity.CraftHumanEntity;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.inventory.CraftItemType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.inventory.CookingRecipe;
// CraftBukkit end

public abstract class AbstractFurnaceBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, RecipeCraftingHolder, StackedContentsCompatible {

    protected static final int SLOT_INPUT = 0;
    protected static final int SLOT_FUEL = 1;
    protected static final int SLOT_RESULT = 2;
    public static final int DATA_LIT_TIME = 0;
    private static final int[] SLOTS_FOR_UP = new int[]{0};
    private static final int[] SLOTS_FOR_DOWN = new int[]{2, 1};
    private static final int[] SLOTS_FOR_SIDES = new int[]{1};
    public static final int DATA_LIT_DURATION = 1;
    public static final int DATA_COOKING_PROGRESS = 2;
    public static final int DATA_COOKING_TOTAL_TIME = 3;
    public static final int NUM_DATA_VALUES = 4;
    public static final int BURN_TIME_STANDARD = 200;
    public static final int BURN_COOL_SPEED = 2;
    protected NonNullList<ItemStack> items;
    public int litTimeRemaining;
    int litTotalTime;
    public int cookingTimer;
    public int cookingTotalTime;
    protected final ContainerData dataAccess;
    public final Reference2IntOpenHashMap<ResourceKey<Recipe<?>>> recipesUsed;
    private final RecipeManager.CachedCheck<SingleRecipeInput, ? extends AbstractCookingRecipe> quickCheck;
    public final RecipeType<? extends AbstractCookingRecipe> recipeType; // Paper - cook speed multiplier API
    public double cookSpeedMultiplier = 1.0; // Paper - cook speed multiplier API

    protected AbstractFurnaceBlockEntity(BlockEntityType<?> blockEntityType, BlockPos pos, BlockState state, RecipeType<? extends AbstractCookingRecipe> recipeType) {
        super(blockEntityType, pos, state);
        this.items = NonNullList.withSize(3, ItemStack.EMPTY);
        this.dataAccess = new ContainerData() {
            @Override
            public int get(int index) {
                switch (index) {
                    case 0:
                        return AbstractFurnaceBlockEntity.this.litTimeRemaining;
                    case 1:
                        return AbstractFurnaceBlockEntity.this.litTotalTime;
                    case 2:
                        return AbstractFurnaceBlockEntity.this.cookingTimer;
                    case 3:
                        return AbstractFurnaceBlockEntity.this.cookingTotalTime;
                    default:
                        return 0;
                }
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0:
                        AbstractFurnaceBlockEntity.this.litTimeRemaining = value;
                        break;
                    case 1:
                        AbstractFurnaceBlockEntity.this.litTotalTime = value;
                        break;
                    case 2:
                        AbstractFurnaceBlockEntity.this.cookingTimer = value;
                        break;
                    case 3:
                        AbstractFurnaceBlockEntity.this.cookingTotalTime = value;
                }

            }

            @Override
            public int getCount() {
                return 4;
            }
        };
        this.recipesUsed = new Reference2IntOpenHashMap();
        this.quickCheck = RecipeManager.createCheck((RecipeType<AbstractCookingRecipe>) recipeType); // CraftBukkit - decompile error // Eclipse fail
        this.recipeType = recipeType; // Paper - cook speed multiplier API
    }

    // CraftBukkit start - add fields and methods
    private int maxStack = MAX_STACK;
    public List<HumanEntity> transaction = new java.util.ArrayList<HumanEntity>();

    public List<ItemStack> getContents() {
        return this.items;
    }

    public void onOpen(CraftHumanEntity who) {
        this.transaction.add(who);
    }

    public void onClose(CraftHumanEntity who) {
        this.transaction.remove(who);
    }

    public List<HumanEntity> getViewers() {
        return this.transaction;
    }

    @Override
    public int getMaxStackSize() {
        return this.maxStack;
    }

    public void setMaxStackSize(int size) {
        this.maxStack = size;
    }
    // CraftBukkit end

    private boolean isLit() {
        return this.litTimeRemaining > 0;
    }

    @Override
    protected void loadAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.loadAdditional(nbt, registries);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        ContainerHelper.loadAllItems(nbt, this.items, registries);
        this.cookingTimer = nbt.getShort("cooking_time_spent");
        this.cookingTotalTime = nbt.getShort("cooking_total_time");
        this.litTimeRemaining = nbt.getShort("lit_time_remaining");
        this.litTotalTime = nbt.getShort("lit_total_time");
        CompoundTag nbttagcompound1 = nbt.getCompound("RecipesUsed");
        Iterator iterator = nbttagcompound1.getAllKeys().iterator();

        while (iterator.hasNext()) {
            String s = (String) iterator.next();

            // Paper start - Validate ResourceLocation
            final ResourceLocation resourceLocation = ResourceLocation.tryParse(s);
            if (resourceLocation != null) {
                this.recipesUsed.put(ResourceKey.create(Registries.RECIPE, resourceLocation), nbttagcompound1.getInt(s));
            }
        }

        // Paper start - cook speed multiplier API
        if (nbt.contains("Paper.CookSpeedMultiplier")) {
            this.cookSpeedMultiplier = nbt.getDouble("Paper.CookSpeedMultiplier");
        }
        // Paper end - cook speed multiplier API
    }

    @Override
    protected void saveAdditional(CompoundTag nbt, HolderLookup.Provider registries) {
        super.saveAdditional(nbt, registries);
        nbt.putShort("cooking_time_spent", (short) this.cookingTimer);
        nbt.putShort("cooking_total_time", (short) this.cookingTotalTime);
        nbt.putShort("lit_time_remaining", (short) this.litTimeRemaining);
        nbt.putShort("lit_total_time", (short) this.litTotalTime);
        nbt.putDouble("Paper.CookSpeedMultiplier", this.cookSpeedMultiplier); // Paper - cook speed multiplier API
        ContainerHelper.saveAllItems(nbt, this.items, registries);
        CompoundTag nbttagcompound1 = new CompoundTag();

        this.recipesUsed.forEach((resourcekey, integer) -> {
            nbttagcompound1.putInt(resourcekey.location().toString(), integer);
        });
        nbt.put("RecipesUsed", nbttagcompound1);
    }

    public static void serverTick(ServerLevel world, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity blockEntity) {
        boolean flag = blockEntity.isLit();
        boolean flag1 = false;

        if (blockEntity.isLit()) {
            --blockEntity.litTimeRemaining;
        }

        ItemStack itemstack = (ItemStack) blockEntity.items.get(1);
        ItemStack itemstack1 = (ItemStack) blockEntity.items.get(0);
        boolean flag2 = !itemstack1.isEmpty();
        boolean flag3 = !itemstack.isEmpty();

        if (!blockEntity.isLit() && (!flag3 || !flag2)) {
            if (!blockEntity.isLit() && blockEntity.cookingTimer > 0) {
                blockEntity.cookingTimer = Mth.clamp(blockEntity.cookingTimer - 2, 0, blockEntity.cookingTotalTime);
            }
        } else {
            SingleRecipeInput singlerecipeinput = new SingleRecipeInput(itemstack1);
            RecipeHolder recipeholder;

            if (flag2) {
                recipeholder = (RecipeHolder) blockEntity.quickCheck.getRecipeFor(singlerecipeinput, world).orElse(null); // CraftBukkit - decompile error
            } else {
                recipeholder = null;
            }

            int i = blockEntity.getMaxStackSize();

            if (!blockEntity.isLit() && AbstractFurnaceBlockEntity.canBurn(world.registryAccess(), recipeholder, singlerecipeinput, blockEntity.items, i)) {
                // CraftBukkit start
                CraftItemStack fuel = CraftItemStack.asCraftMirror(itemstack);

                FurnaceBurnEvent furnaceBurnEvent = new FurnaceBurnEvent(CraftBlock.at(world, pos), fuel, blockEntity.getBurnDuration(world.fuelValues(), itemstack));
                world.getCraftServer().getPluginManager().callEvent(furnaceBurnEvent);

                if (furnaceBurnEvent.isCancelled()) {
                    return;
                }

                blockEntity.litTimeRemaining = furnaceBurnEvent.getBurnTime();
                blockEntity.litTotalTime = blockEntity.litTimeRemaining;
                if (blockEntity.isLit() && furnaceBurnEvent.isBurning()) {
                    // CraftBukkit end
                    flag1 = true;
                    if (flag3 && furnaceBurnEvent.willConsumeFuel()) { // Paper - add consumeFuel to FurnaceBurnEvent
                        Item item = itemstack.getItem();

                        itemstack.shrink(1);
                        if (itemstack.isEmpty()) {
                            blockEntity.items.set(1, item.getCraftingRemainder());
                        }
                    }
                }
            }

            if (blockEntity.isLit() && AbstractFurnaceBlockEntity.canBurn(world.registryAccess(), recipeholder, singlerecipeinput, blockEntity.items, i)) {
                // CraftBukkit start
                if (recipeholder != null && blockEntity.cookingTimer == 0) {
                    CraftItemStack source = CraftItemStack.asCraftMirror(blockEntity.items.get(0));
                    CookingRecipe<?> recipe = (CookingRecipe<?>) recipeholder.toBukkitRecipe();

                    FurnaceStartSmeltEvent event = new FurnaceStartSmeltEvent(CraftBlock.at(world, pos), source, recipe, AbstractFurnaceBlockEntity.getTotalCookTime(world, blockEntity, blockEntity.recipeType, blockEntity.cookSpeedMultiplier)); // Paper - cook speed multiplier API
                    world.getCraftServer().getPluginManager().callEvent(event);

                    blockEntity.cookingTotalTime = event.getTotalCookTime();
                }
                // CraftBukkit end

                ++blockEntity.cookingTimer;
                if (blockEntity.cookingTimer >= blockEntity.cookingTotalTime) { // Paper - cook speed multiplier API
                    blockEntity.cookingTimer = 0;
                    blockEntity.cookingTotalTime = AbstractFurnaceBlockEntity.getTotalCookTime(world, blockEntity, blockEntity.recipeType, blockEntity.cookSpeedMultiplier); // Paper - cook speed multiplier API
                    if (AbstractFurnaceBlockEntity.burn(blockEntity.level, blockEntity.worldPosition, world.registryAccess(), recipeholder, singlerecipeinput, blockEntity.items, i)) { // CraftBukkit
                        blockEntity.setRecipeUsed(recipeholder);
                    }

                    flag1 = true;
                }
            } else {
                blockEntity.cookingTimer = 0;
            }
        }

        if (flag != blockEntity.isLit()) {
            flag1 = true;
            state = (BlockState) state.setValue(AbstractFurnaceBlock.LIT, blockEntity.isLit());
            world.setBlock(pos, state, 3);
        }

        if (flag1) {
            setChanged(world, pos, state);
        }

    }

    private static boolean canBurn(RegistryAccess dynamicRegistryManager, @Nullable RecipeHolder<? extends AbstractCookingRecipe> recipe, SingleRecipeInput input, NonNullList<ItemStack> inventory, int maxCount) {
        if (!((ItemStack) inventory.get(0)).isEmpty() && recipe != null) {
            ItemStack itemstack = ((AbstractCookingRecipe) recipe.value()).assemble(input, dynamicRegistryManager);

            if (itemstack.isEmpty()) {
                return false;
            } else {
                ItemStack itemstack1 = (ItemStack) inventory.get(2);

                return itemstack1.isEmpty() ? true : (!ItemStack.isSameItemSameComponents(itemstack1, itemstack) ? false : (itemstack1.getCount() < maxCount && itemstack1.getCount() < itemstack1.getMaxStackSize() ? true : itemstack1.getCount() < itemstack.getMaxStackSize()));
            }
        } else {
            return false;
        }
    }

    private static boolean burn(Level world, BlockPos blockposition, RegistryAccess iregistrycustom, @Nullable RecipeHolder<? extends AbstractCookingRecipe> recipeholder, SingleRecipeInput singlerecipeinput, NonNullList<ItemStack> nonnulllist, int i) { // CraftBukkit
        if (recipeholder != null && AbstractFurnaceBlockEntity.canBurn(iregistrycustom, recipeholder, singlerecipeinput, nonnulllist, i)) {
            ItemStack itemstack = (ItemStack) nonnulllist.get(0);
            ItemStack itemstack1 = ((AbstractCookingRecipe) recipeholder.value()).assemble(singlerecipeinput, iregistrycustom);
            ItemStack itemstack2 = (ItemStack) nonnulllist.get(2);

            // CraftBukkit start - fire FurnaceSmeltEvent
            CraftItemStack source = CraftItemStack.asCraftMirror(itemstack);
            org.bukkit.inventory.ItemStack result = CraftItemStack.asBukkitCopy(itemstack1);

            FurnaceSmeltEvent furnaceSmeltEvent = new FurnaceSmeltEvent(CraftBlock.at(world, blockposition), source, result, (org.bukkit.inventory.CookingRecipe<?>) recipeholder.toBukkitRecipe()); // Paper - Add recipe to cook events
            world.getCraftServer().getPluginManager().callEvent(furnaceSmeltEvent);

            if (furnaceSmeltEvent.isCancelled()) {
                return false;
            }

            result = furnaceSmeltEvent.getResult();
            itemstack1 = CraftItemStack.asNMSCopy(result);

            if (!itemstack1.isEmpty()) {
                if (itemstack2.isEmpty()) {
                    nonnulllist.set(2, itemstack1.copy());
                } else if (CraftItemStack.asCraftMirror(itemstack2).isSimilar(result)) {
                    itemstack2.grow(itemstack1.getCount());
                } else {
                    return false;
                }
            }

            /*
            if (itemstack2.isEmpty()) {
                nonnulllist.set(2, itemstack1.copy());
            } else if (ItemStack.isSameItemSameComponents(itemstack2, itemstack1)) {
                itemstack2.grow(1);
            }
            */
            // CraftBukkit end

            if (itemstack.is(Blocks.WET_SPONGE.asItem()) && !((ItemStack) nonnulllist.get(1)).isEmpty() && ((ItemStack) nonnulllist.get(1)).is(Items.BUCKET)) {
                nonnulllist.set(1, new ItemStack(Items.WATER_BUCKET));
            }

            itemstack.shrink(1);
            return true;
        } else {
            return false;
        }
    }

    protected int getBurnDuration(FuelValues fuelRegistry, ItemStack stack) {
        return fuelRegistry.burnDuration(stack);
    }

    public static int getTotalCookTime(@Nullable ServerLevel world, AbstractFurnaceBlockEntity furnace, RecipeType<? extends AbstractCookingRecipe> recipeType, double cookSpeedMultiplier) { // Paper - cook speed multiplier API
        SingleRecipeInput singlerecipeinput = new SingleRecipeInput(furnace.getItem(0));

        // Paper start - cook speed multiplier API
        /* Scale the recipe's cooking time to the current cookSpeedMultiplier */
        int cookTime = world != null ? furnace.quickCheck.getRecipeFor(singlerecipeinput, world).map(holder -> holder.value().cookingTime()).orElse(200) : (net.minecraft.server.MinecraftServer.getServer().getRecipeManager().getRecipeFor(recipeType, singlerecipeinput, world /* passing a null level here is safe. world is only used for map extending recipes which won't happen here */).map(holder -> holder.value().cookingTime()).orElse(200));
        return (int) Math.ceil (cookTime / cookSpeedMultiplier);
        // Paper end - cook speed multiplier API
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return side == Direction.DOWN ? AbstractFurnaceBlockEntity.SLOTS_FOR_DOWN : (side == Direction.UP ? AbstractFurnaceBlockEntity.SLOTS_FOR_UP : AbstractFurnaceBlockEntity.SLOTS_FOR_SIDES);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction dir) {
        return this.canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction dir) {
        return dir == Direction.DOWN && slot == 1 ? stack.is(Items.WATER_BUCKET) || stack.is(Items.BUCKET) : true;
    }

    @Override
    public int getContainerSize() {
        return this.items.size();
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> inventory) {
        this.items = inventory;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        ItemStack itemstack1 = (ItemStack) this.items.get(slot);
        boolean flag = !stack.isEmpty() && ItemStack.isSameItemSameComponents(itemstack1, stack);

        this.items.set(slot, stack);
        stack.limitSize(this.getMaxStackSize(stack));
        if (slot == 0 && !flag) {
            Level world = this.level;

            if (world instanceof ServerLevel) {
                ServerLevel worldserver = (ServerLevel) world;

                this.cookingTotalTime = AbstractFurnaceBlockEntity.getTotalCookTime(worldserver, this, this.recipeType, this.cookSpeedMultiplier); // Paper - cook speed multiplier API
                this.cookingTimer = 0;
                this.setChanged();
            }
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == 2) {
            return false;
        } else if (slot != 1) {
            return true;
        } else {
            ItemStack itemstack1 = (ItemStack) this.items.get(1);

            return this.level.fuelValues().isFuel(stack) || stack.is(Items.BUCKET) && !itemstack1.is(Items.BUCKET);
        }
    }

    @Override
    public void setRecipeUsed(@Nullable RecipeHolder<?> recipe) {
        if (recipe != null) {
            ResourceKey<Recipe<?>> resourcekey = recipe.id();

            this.recipesUsed.addTo(resourcekey, 1);
        }

    }

    @Nullable
    @Override
    public RecipeHolder<?> getRecipeUsed() {
        return null;
    }

    @Override
    public void awardUsedRecipes(net.minecraft.world.entity.player.Player player, List<ItemStack> ingredients) {}

    public void awardUsedRecipesAndPopExperience(ServerPlayer entityplayer, ItemStack itemstack, int amount) { // CraftBukkit
        List<RecipeHolder<?>> list = this.getRecipesToAwardAndPopExperience(entityplayer.serverLevel(), entityplayer.position(), this.worldPosition, entityplayer, itemstack, amount); // CraftBukkit

        entityplayer.awardRecipes(list);
        Iterator iterator = list.iterator();

        while (iterator.hasNext()) {
            RecipeHolder<?> recipeholder = (RecipeHolder) iterator.next();

            if (recipeholder != null) {
                entityplayer.triggerRecipeCrafted(recipeholder, this.items);
            }
        }

        this.recipesUsed.clear();
    }

    public List<RecipeHolder<?>> getRecipesToAwardAndPopExperience(ServerLevel world, Vec3 pos) {
        // CraftBukkit start
        return this.getRecipesToAwardAndPopExperience(world, pos, this.worldPosition, null, null, 0);
    }

    public List<RecipeHolder<?>> getRecipesToAwardAndPopExperience(ServerLevel worldserver, Vec3 vec3d, BlockPos blockposition, ServerPlayer entityplayer, ItemStack itemstack, int amount) {
        // CraftBukkit end
        List<RecipeHolder<?>> list = Lists.newArrayList();
        ObjectIterator objectiterator = this.recipesUsed.reference2IntEntrySet().iterator();

        while (objectiterator.hasNext()) {
            Entry<ResourceKey<Recipe<?>>> entry = (Entry) objectiterator.next();

            worldserver.recipeAccess().byKey(entry.getKey()).ifPresent((recipeholder) -> { // CraftBukkit - decompile error
                if (!(recipeholder.value() instanceof AbstractCookingRecipe)) return; // Paper - don't process non-cooking recipes
                list.add(recipeholder);
                AbstractFurnaceBlockEntity.createExperience(worldserver, vec3d, entry.getIntValue(), ((AbstractCookingRecipe) recipeholder.value()).experience(), blockposition, entityplayer, itemstack, amount); // CraftBukkit
            });
        }

        return list;
    }

    private static void createExperience(ServerLevel worldserver, Vec3 vec3d, int i, float f, BlockPos blockposition, net.minecraft.world.entity.player.Player entityhuman, ItemStack itemstack, int amount) { // CraftBukkit
        int j = Mth.floor((float) i * f);
        float f1 = Mth.frac((float) i * f);

        if (f1 != 0.0F && Math.random() < (double) f1) {
            ++j;
        }

        // CraftBukkit start - fire FurnaceExtractEvent / BlockExpEvent
        BlockExpEvent event;
        if (amount != 0) {
            event = new FurnaceExtractEvent((Player) entityhuman.getBukkitEntity(), CraftBlock.at(worldserver, blockposition), CraftItemType.minecraftToBukkit(itemstack.getItem()), amount, j);
        } else {
            event = new BlockExpEvent(CraftBlock.at(worldserver, blockposition), j);
        }
        worldserver.getCraftServer().getPluginManager().callEvent(event);
        j = event.getExpToDrop();
        // CraftBukkit end

        ExperienceOrb.award(worldserver, vec3d, j, org.bukkit.entity.ExperienceOrb.SpawnReason.FURNACE, entityhuman); // Paper
    }

    @Override
    public void fillStackedContents(StackedItemContents finder) {
        // Paper start - don't account fuel stack (fixes MC-243057)
        finder.accountStack(this.items.get(SLOT_INPUT));
        finder.accountStack(this.items.get(SLOT_RESULT));
        // Paper end

    }
}
