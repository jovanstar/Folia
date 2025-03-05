package net.minecraft.world.item.crafting;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.HolderSetCodec;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.ItemLike;
// CraftBukkit start
import java.util.List;
import javax.annotation.Nullable;
// CraftBukkit end

public final class Ingredient implements StackedContents.IngredientInfo<io.papermc.paper.inventory.recipe.ItemOrExact>, Predicate<ItemStack> { // Paper - Improve exact choice recipe ingredients

    public static final StreamCodec<RegistryFriendlyByteBuf, Ingredient> CONTENTS_STREAM_CODEC = ByteBufCodecs.holderSet(Registries.ITEM).map(Ingredient::new, (recipeitemstack) -> {
        return recipeitemstack.values;
    });
    public static final StreamCodec<RegistryFriendlyByteBuf, Optional<Ingredient>> OPTIONAL_CONTENTS_STREAM_CODEC = ByteBufCodecs.holderSet(Registries.ITEM).map((holderset) -> {
        return holderset.size() == 0 ? Optional.empty() : Optional.of(new Ingredient(holderset));
    }, (optional) -> {
        return (HolderSet) optional.map((recipeitemstack) -> {
            return recipeitemstack.values;
        }).orElse(HolderSet.direct());
    });
    public static final Codec<HolderSet<Item>> NON_AIR_HOLDER_SET_CODEC = HolderSetCodec.create(Registries.ITEM, Item.CODEC, false);
    public static final Codec<Ingredient> CODEC = ExtraCodecs.nonEmptyHolderSet(Ingredient.NON_AIR_HOLDER_SET_CODEC).xmap(Ingredient::new, (recipeitemstack) -> {
        return recipeitemstack.values;
    });
    private final HolderSet<Item> values;
    // CraftBukkit start
    @Nullable
    private java.util.Set<ItemStack> itemStacks; // Paper - Improve exact choice recipe ingredients

    public boolean isExact() {
        return this.itemStacks != null;
    }

    public java.util.Set<ItemStack> itemStacks() { // Paper - Improve exact choice recipe ingredients
        return this.itemStacks;
    }

    public static Ingredient ofStacks(List<ItemStack> stacks) {
        Ingredient recipe = Ingredient.of(stacks.stream().map(ItemStack::getItem));
        // Paper start - Improve exact choice recipe ingredients
        recipe.itemStacks = net.minecraft.world.item.ItemStackLinkedSet.createTypeAndComponentsSet();
        recipe.itemStacks.addAll(stacks);
        recipe.itemStacks = java.util.Collections.unmodifiableSet(recipe.itemStacks);
        // Paper end - Improve exact choice recipe ingredients
        return recipe;
    }
    // CraftBukkit end

    private Ingredient(HolderSet<Item> entries) {
        entries.unwrap().ifRight((list) -> {
            if (list.isEmpty()) {
                throw new UnsupportedOperationException("Ingredients can't be empty");
            } else if (list.contains(Items.AIR.builtInRegistryHolder())) {
                throw new UnsupportedOperationException("Ingredient can't contain air");
            }
        });
        this.values = entries;
    }

    public static boolean testOptionalIngredient(Optional<Ingredient> ingredient, ItemStack stack) {
        Optional optional1 = ingredient.map((recipeitemstack) -> {
            return recipeitemstack.test(stack);
        });

        Objects.requireNonNull(stack);
        return (Boolean) optional1.orElseGet(stack::isEmpty);
    }

    /** @deprecated */
    @Deprecated
    public Stream<Holder<Item>> items() {
        return this.values.stream();
    }

    public boolean isEmpty() {
        return this.values.size() == 0;
    }

    public boolean test(ItemStack itemstack) {
        // CraftBukkit start
        if (this.isExact()) {
            return this.itemStacks.contains(itemstack); // Paper - Improve exact choice recipe ingredients (hashing FTW!)
        }
        // CraftBukkit end
        return itemstack.is(this.values);
    }

    // Paper start - Improve exact choice recipe ingredients
    @Override
    public boolean acceptsItem(final io.papermc.paper.inventory.recipe.ItemOrExact holder) {
        return switch (holder) {
            case io.papermc.paper.inventory.recipe.ItemOrExact.Item(final Holder<Item> item) ->
                !this.isExact() && this.values.contains(item);
            case io.papermc.paper.inventory.recipe.ItemOrExact.Exact(final ItemStack exact) ->
                this.isExact() && this.itemStacks.contains(exact);
        };
        // Paper end - Improve exact choice recipe ingredients
    }

    public boolean equals(Object object) {
        if (object instanceof Ingredient recipeitemstack) {
            return Objects.equals(this.values, recipeitemstack.values) && Objects.equals(this.itemStacks, recipeitemstack.itemStacks); // CraftBukkit
        } else {
            return false;
        }
    }

    public static Ingredient of(ItemLike item) {
        return new Ingredient(HolderSet.direct(item.asItem().builtInRegistryHolder()));
    }

    public static Ingredient of(ItemLike... items) {
        return Ingredient.of(Arrays.stream(items));
    }

    public static Ingredient of(Stream<? extends ItemLike> stacks) {
        return new Ingredient(HolderSet.direct(stacks.map((imaterial) -> {
            return imaterial.asItem().builtInRegistryHolder();
        }).toList()));
    }

    public static Ingredient of(HolderSet<Item> tag) {
        return new Ingredient(tag);
    }

    public SlotDisplay display() {
        // Paper start - show exact ingredients in recipe book
        if (this.isExact()) {
            return new SlotDisplay.Composite(this.itemStacks().stream().<SlotDisplay>map(SlotDisplay.ItemStackSlotDisplay::new).toList());
        }
        // Paper end - show exact ingredients in recipe book
        return (SlotDisplay) this.values.unwrap().map(SlotDisplay.TagSlotDisplay::new, (list) -> {
            return new SlotDisplay.Composite(list.stream().map(Ingredient::displayForSingleItem).toList());
        });
    }

    public static SlotDisplay optionalIngredientToDisplay(Optional<Ingredient> ingredient) {
        return (SlotDisplay) ingredient.map(Ingredient::display).orElse(SlotDisplay.Empty.INSTANCE);
    }

    private static SlotDisplay displayForSingleItem(Holder<Item> displayedItem) {
        SlotDisplay.ItemSlotDisplay slotdisplay_d = new SlotDisplay.ItemSlotDisplay(displayedItem);
        ItemStack itemstack = ((Item) displayedItem.value()).getCraftingRemainder();

        if (!itemstack.isEmpty()) {
            SlotDisplay.ItemStackSlotDisplay slotdisplay_f = new SlotDisplay.ItemStackSlotDisplay(itemstack);

            return new SlotDisplay.WithRemainder(slotdisplay_d, slotdisplay_f);
        } else {
            return slotdisplay_d;
        }
    }
}
