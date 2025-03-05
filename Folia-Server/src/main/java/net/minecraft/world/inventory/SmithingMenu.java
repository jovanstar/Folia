package net.minecraft.world.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipePropertySet;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.craftbukkit.inventory.CraftInventoryView; // CraftBukkit

public class SmithingMenu extends ItemCombinerMenu {

    public static final int TEMPLATE_SLOT = 0;
    public static final int BASE_SLOT = 1;
    public static final int ADDITIONAL_SLOT = 2;
    public static final int RESULT_SLOT = 3;
    public static final int TEMPLATE_SLOT_X_PLACEMENT = 8;
    public static final int BASE_SLOT_X_PLACEMENT = 26;
    public static final int ADDITIONAL_SLOT_X_PLACEMENT = 44;
    private static final int RESULT_SLOT_X_PLACEMENT = 98;
    public static final int SLOT_Y_PLACEMENT = 48;
    private final Level level;
    private final RecipePropertySet baseItemTest;
    private final RecipePropertySet templateItemTest;
    private final RecipePropertySet additionItemTest;
    private final DataSlot hasRecipeError;
    // CraftBukkit start
    private CraftInventoryView bukkitEntity;
    // CraftBukkit end

    public SmithingMenu(int syncId, Inventory playerInventory) {
        this(syncId, playerInventory, ContainerLevelAccess.NULL);
    }

    public SmithingMenu(int syncId, Inventory playerInventory, ContainerLevelAccess context) {
        this(syncId, playerInventory, context, playerInventory.player.level());
    }

    private SmithingMenu(int syncId, Inventory playerInventory, ContainerLevelAccess context, Level world) {
        super(MenuType.SMITHING, syncId, playerInventory, context, SmithingMenu.createInputSlotDefinitions(world.recipeAccess()));
        this.hasRecipeError = DataSlot.standalone();
        this.level = world;
        this.baseItemTest = world.recipeAccess().propertySet(RecipePropertySet.SMITHING_BASE);
        this.templateItemTest = world.recipeAccess().propertySet(RecipePropertySet.SMITHING_TEMPLATE);
        this.additionItemTest = world.recipeAccess().propertySet(RecipePropertySet.SMITHING_ADDITION);
        this.addDataSlot(this.hasRecipeError).set(0);
    }

    private static ItemCombinerMenuSlotDefinition createInputSlotDefinitions(RecipeAccess recipeManager) {
        RecipePropertySet recipepropertyset = recipeManager.propertySet(RecipePropertySet.SMITHING_BASE);
        RecipePropertySet recipepropertyset1 = recipeManager.propertySet(RecipePropertySet.SMITHING_TEMPLATE);
        RecipePropertySet recipepropertyset2 = recipeManager.propertySet(RecipePropertySet.SMITHING_ADDITION);
        ItemCombinerMenuSlotDefinition.Builder itemcombinermenuslotdefinition_a = ItemCombinerMenuSlotDefinition.create();

        Objects.requireNonNull(recipepropertyset1);
        itemcombinermenuslotdefinition_a = itemcombinermenuslotdefinition_a.withSlot(0, 8, 48, recipepropertyset1::test);
        Objects.requireNonNull(recipepropertyset);
        itemcombinermenuslotdefinition_a = itemcombinermenuslotdefinition_a.withSlot(1, 26, 48, recipepropertyset::test);
        Objects.requireNonNull(recipepropertyset2);
        return itemcombinermenuslotdefinition_a.withSlot(2, 44, 48, recipepropertyset2::test).withResultSlot(3, 98, 48).build();
    }

    @Override
    protected boolean isValidBlock(BlockState state) {
        return state.is(Blocks.SMITHING_TABLE);
    }

    @Override
    protected void onTake(Player player, ItemStack stack) {
        stack.onCraftedBy(player.level(), player, stack.getCount());
        this.resultSlots.awardUsedRecipes(player, this.getRelevantItems());
        this.shrinkStackInSlot(0);
        this.shrinkStackInSlot(1);
        this.shrinkStackInSlot(2);
        this.access.execute((world, blockposition) -> {
            world.levelEvent(1044, blockposition, 0);
        });
    }

    private List<ItemStack> getRelevantItems() {
        return List.of(this.inputSlots.getItem(0), this.inputSlots.getItem(1), this.inputSlots.getItem(2));
    }

    private SmithingRecipeInput createRecipeInput() {
        return new SmithingRecipeInput(this.inputSlots.getItem(0), this.inputSlots.getItem(1), this.inputSlots.getItem(2));
    }

    private void shrinkStackInSlot(int slot) {
        ItemStack itemstack = this.inputSlots.getItem(slot);

        if (!itemstack.isEmpty()) {
            itemstack.shrink(1);
            this.inputSlots.setItem(slot, itemstack);
        }

    }

    @Override
    public void slotsChanged(Container inventory) {
        super.slotsChanged(inventory);
        if (this.level instanceof ServerLevel) {
            boolean flag = this.getSlot(0).hasItem() && this.getSlot(1).hasItem() && this.getSlot(2).hasItem() && !this.getSlot(this.getResultSlot()).hasItem();

            this.hasRecipeError.set(flag ? 1 : 0);
        }

        org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareResultEvent(this, RESULT_SLOT); // Paper - Add PrepareResultEvent
    }

    @Override
    public void createResult() {
        SmithingRecipeInput smithingrecipeinput = this.createRecipeInput();
        Level world = this.level;
        Optional<RecipeHolder<SmithingRecipe>> optional; // CraftBukkit - decompile error

        if (world instanceof ServerLevel worldserver) {
            optional = worldserver.recipeAccess().getRecipeFor(RecipeType.SMITHING, smithingrecipeinput, worldserver);
        } else {
            optional = Optional.empty();
        }

        optional.ifPresentOrElse((recipeholder) -> {
            ItemStack itemstack = ((SmithingRecipe) recipeholder.value()).assemble(smithingrecipeinput, this.level.registryAccess());

            this.resultSlots.setRecipeUsed(recipeholder);
            // CraftBukkit start
            org.bukkit.craftbukkit.event.CraftEventFactory.callPrepareSmithingEvent(this.getBukkitView(), itemstack);
            // CraftBukkit end
        }, () -> {
            this.resultSlots.setRecipeUsed((RecipeHolder) null);
            this.resultSlots.setItem(0, ItemStack.EMPTY);
        });
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != this.resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean canMoveIntoInputSlots(ItemStack stack) {
        return this.templateItemTest.test(stack) && !this.getSlot(0).hasItem() ? true : (this.baseItemTest.test(stack) && !this.getSlot(1).hasItem() ? true : this.additionItemTest.test(stack) && !this.getSlot(2).hasItem());
    }

    public boolean hasRecipeError() {
        return this.hasRecipeError.get() > 0;
    }

    // CraftBukkit start
    @Override
    public CraftInventoryView getBukkitView() {
        if (this.bukkitEntity != null) {
            return this.bukkitEntity;
        }

        org.bukkit.craftbukkit.inventory.CraftInventory inventory = new org.bukkit.craftbukkit.inventory.CraftInventorySmithing(
                this.access.getLocation(), this.inputSlots, this.resultSlots);
        this.bukkitEntity = new CraftInventoryView(this.player.getBukkitEntity(), inventory, this);
        return this.bukkitEntity;
    }
    // CraftBukkit end
}
