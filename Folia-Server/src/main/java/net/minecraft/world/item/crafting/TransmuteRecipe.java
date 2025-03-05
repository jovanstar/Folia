package net.minecraft.world.item.crafting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftItemType;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftTransmuteRecipe;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class TransmuteRecipe implements CraftingRecipe {

    final String group;
    final CraftingBookCategory category;
    final Ingredient input;
    final Ingredient material;
    final Holder<Item> result;
    @Nullable
    private PlacementInfo placementInfo;

    public TransmuteRecipe(String group, CraftingBookCategory category, Ingredient input, Ingredient material, Holder<Item> result) {
        this.group = group;
        this.category = category;
        this.input = input;
        this.material = material;
        this.result = result;
    }

    public boolean matches(CraftingInput input, Level world) {
        if (input.ingredientCount() != 2) {
            return false;
        } else {
            boolean flag = false;
            boolean flag1 = false;

            for (int i = 0; i < input.size(); ++i) {
                ItemStack itemstack = input.getItem(i);

                if (!itemstack.isEmpty()) {
                    if (!flag && this.input.test(itemstack) && itemstack.getItem() != this.result.value()) {
                        flag = true;
                    } else {
                        if (flag1 || !this.material.test(itemstack)) {
                            return false;
                        }

                        flag1 = true;
                    }
                }
            }

            return flag && flag1;
        }
    }

    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack itemstack = ItemStack.EMPTY;

        for (int i = 0; i < input.size(); ++i) {
            ItemStack itemstack1 = input.getItem(i);

            if (!itemstack1.isEmpty() && this.input.test(itemstack1) && itemstack1.getItem() != this.result.value()) {
                itemstack = itemstack1;
            }
        }

        return itemstack.transmuteCopy((ItemLike) this.result.value(), 1);
    }

    @Override
    public List<RecipeDisplay> display() {
        return List.of(new ShapelessCraftingRecipeDisplay(List.of(this.input.display(), this.material.display()), new SlotDisplay.ItemSlotDisplay(this.result), new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)));
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe(NamespacedKey id) {
        return new CraftTransmuteRecipe(id, CraftItemType.minecraftToBukkit(this.result.value()), CraftRecipe.toBukkit(this.input), CraftRecipe.toBukkit(this.material));
    }
    // CraftBukkit end

    @Override
    public RecipeSerializer<TransmuteRecipe> getSerializer() {
        return RecipeSerializer.TRANSMUTE;
    }

    @Override
    public String group() {
        return this.group;
    }

    @Override
    public PlacementInfo placementInfo() {
        if (this.placementInfo == null) {
            this.placementInfo = PlacementInfo.create(List.of(this.input, this.material));
        }

        return this.placementInfo;
    }

    @Override
    public CraftingBookCategory category() {
        return this.category;
    }

    public static class Serializer implements RecipeSerializer<TransmuteRecipe> {

        private static final MapCodec<TransmuteRecipe> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(Codec.STRING.optionalFieldOf("group", "").forGetter((transmuterecipe) -> {
                return transmuterecipe.group;
            }), CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter((transmuterecipe) -> {
                return transmuterecipe.category;
            }), Ingredient.CODEC.fieldOf("input").forGetter((transmuterecipe) -> {
                return transmuterecipe.input;
            }), Ingredient.CODEC.fieldOf("material").forGetter((transmuterecipe) -> {
                return transmuterecipe.material;
            }), Item.CODEC.fieldOf("result").forGetter((transmuterecipe) -> {
                return transmuterecipe.result;
            })).apply(instance, TransmuteRecipe::new);
        });
        public static final StreamCodec<RegistryFriendlyByteBuf, TransmuteRecipe> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, (transmuterecipe) -> {
            return transmuterecipe.group;
        }, CraftingBookCategory.STREAM_CODEC, (transmuterecipe) -> {
            return transmuterecipe.category;
        }, Ingredient.CONTENTS_STREAM_CODEC, (transmuterecipe) -> {
            return transmuterecipe.input;
        }, Ingredient.CONTENTS_STREAM_CODEC, (transmuterecipe) -> {
            return transmuterecipe.material;
        }, ByteBufCodecs.holderRegistry(Registries.ITEM), (transmuterecipe) -> {
            return transmuterecipe.result;
        }, TransmuteRecipe::new);

        public Serializer() {}

        @Override
        public MapCodec<TransmuteRecipe> codec() {
            return TransmuteRecipe.Serializer.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, TransmuteRecipe> streamCodec() {
            return TransmuteRecipe.Serializer.STREAM_CODEC;
        }
    }
}
