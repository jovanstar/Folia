package net.minecraft.world.item.crafting;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.equipment.trim.ArmorTrim;
import net.minecraft.world.item.equipment.trim.TrimMaterial;
import net.minecraft.world.item.equipment.trim.TrimMaterials;
import net.minecraft.world.item.equipment.trim.TrimPattern;
import net.minecraft.world.item.equipment.trim.TrimPatterns;

// CraftBukkit start
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.inventory.CraftRecipe;
import org.bukkit.craftbukkit.inventory.CraftSmithingTrimRecipe;
import org.bukkit.inventory.Recipe;
// CraftBukkit end

public class SmithingTrimRecipe implements SmithingRecipe {

    final Optional<Ingredient> template;
    final Optional<Ingredient> base;
    final Optional<Ingredient> addition;
    @Nullable
    private PlacementInfo placementInfo;
    final boolean copyDataComponents; // Paper - Option to prevent data components copy

    public SmithingTrimRecipe(Optional<Ingredient> template, Optional<Ingredient> base, Optional<Ingredient> addition) {
        // Paper start - Option to prevent data components copy
        this(template, base, addition, true);
    }
    public SmithingTrimRecipe(Optional<Ingredient> template, Optional<Ingredient> base, Optional<Ingredient> addition, boolean copyDataComponents) {
        this.copyDataComponents = copyDataComponents;
        // Paper end - Option to prevent data components copy
        this.template = template;
        this.base = base;
        this.addition = addition;
    }

    public ItemStack assemble(SmithingRecipeInput input, HolderLookup.Provider registries) {
        return SmithingTrimRecipe.applyTrim(registries, input.base(), input.addition(), input.template(), this.copyDataComponents);
    }

    public static ItemStack applyTrim(HolderLookup.Provider registries, ItemStack base, ItemStack addition, ItemStack template) {
        return applyTrim(registries, base, addition, template, true);
    }
    public static ItemStack applyTrim(HolderLookup.Provider registries, ItemStack base, ItemStack addition, ItemStack template, boolean copyDataComponents) {
        Optional<Holder.Reference<TrimMaterial>> optional = TrimMaterials.getFromIngredient(registries, addition);
        Optional<Holder.Reference<TrimPattern>> optional1 = TrimPatterns.getFromTemplate(registries, template);

        if (optional.isPresent() && optional1.isPresent()) {
            ArmorTrim armortrim = (ArmorTrim) base.get(DataComponents.TRIM);

            if (armortrim != null && armortrim.hasPatternAndMaterial((Holder) optional1.get(), (Holder) optional.get())) {
                return ItemStack.EMPTY;
            } else {
                ItemStack itemstack3 = copyDataComponents ? base.copyWithCount(1) : new ItemStack(base.getItem(), 1); // Paper - Option to prevent data components copy

                itemstack3.set(DataComponents.TRIM, new ArmorTrim((Holder) optional.get(), (Holder) optional1.get()));
                return itemstack3;
            }
        } else {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public Optional<Ingredient> templateIngredient() {
        return this.template;
    }

    @Override
    public Optional<Ingredient> baseIngredient() {
        return this.base;
    }

    @Override
    public Optional<Ingredient> additionIngredient() {
        return this.addition;
    }

    @Override
    public RecipeSerializer<SmithingTrimRecipe> getSerializer() {
        return RecipeSerializer.SMITHING_TRIM;
    }

    @Override
    public PlacementInfo placementInfo() {
        if (this.placementInfo == null) {
            this.placementInfo = PlacementInfo.createFromOptionals(List.of(this.template, this.base, this.addition));
        }

        return this.placementInfo;
    }

    @Override
    public List<RecipeDisplay> display() {
        SlotDisplay slotdisplay = Ingredient.optionalIngredientToDisplay(this.base);
        SlotDisplay slotdisplay1 = Ingredient.optionalIngredientToDisplay(this.addition);
        SlotDisplay slotdisplay2 = Ingredient.optionalIngredientToDisplay(this.template);

        return List.of(new SmithingRecipeDisplay(slotdisplay2, slotdisplay, slotdisplay1, new SlotDisplay.SmithingTrimDemoSlotDisplay(slotdisplay, slotdisplay1, slotdisplay2), new SlotDisplay.ItemSlotDisplay(Items.SMITHING_TABLE)));
    }

    // CraftBukkit start
    @Override
    public Recipe toBukkitRecipe(NamespacedKey id) {
        return new CraftSmithingTrimRecipe(id, CraftRecipe.toBukkit(this.template), CraftRecipe.toBukkit(this.base), CraftRecipe.toBukkit(this.addition), this.copyDataComponents); // Paper - Option to prevent data components copy
    }
    // CraftBukkit end

    public static class Serializer implements RecipeSerializer<SmithingTrimRecipe> {

        private static final MapCodec<SmithingTrimRecipe> CODEC = RecordCodecBuilder.mapCodec((instance) -> {
            return instance.group(Ingredient.CODEC.optionalFieldOf("template").forGetter((smithingtrimrecipe) -> {
                return smithingtrimrecipe.template;
            }), Ingredient.CODEC.optionalFieldOf("base").forGetter((smithingtrimrecipe) -> {
                return smithingtrimrecipe.base;
            }), Ingredient.CODEC.optionalFieldOf("addition").forGetter((smithingtrimrecipe) -> {
                return smithingtrimrecipe.addition;
            })).apply(instance, SmithingTrimRecipe::new);
        });
        public static final StreamCodec<RegistryFriendlyByteBuf, SmithingTrimRecipe> STREAM_CODEC = StreamCodec.composite(Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, (smithingtrimrecipe) -> {
            return smithingtrimrecipe.template;
        }, Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, (smithingtrimrecipe) -> {
            return smithingtrimrecipe.base;
        }, Ingredient.OPTIONAL_CONTENTS_STREAM_CODEC, (smithingtrimrecipe) -> {
            return smithingtrimrecipe.addition;
        }, SmithingTrimRecipe::new);

        public Serializer() {}

        @Override
        public MapCodec<SmithingTrimRecipe> codec() {
            return SmithingTrimRecipe.Serializer.CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, SmithingTrimRecipe> streamCodec() {
            return SmithingTrimRecipe.Serializer.STREAM_CODEC;
        }
    }
}
