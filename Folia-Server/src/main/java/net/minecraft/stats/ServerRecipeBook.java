package net.minecraft.stats;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.ResourceLocationException;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookSettingsPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.slf4j.Logger;

import org.bukkit.craftbukkit.event.CraftEventFactory; // CraftBukkit

public class ServerRecipeBook extends RecipeBook {

    public static final String RECIPE_BOOK_TAG = "recipeBook";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ServerRecipeBook.DisplayResolver displayResolver;
    @VisibleForTesting
    public final Set<ResourceKey<Recipe<?>>> known = Sets.newIdentityHashSet();
    @VisibleForTesting
    protected final Set<ResourceKey<Recipe<?>>> highlight = Sets.newIdentityHashSet();

    public ServerRecipeBook(ServerRecipeBook.DisplayResolver collector) {
        this.displayResolver = collector;
    }

    public void add(ResourceKey<Recipe<?>> recipeKey) {
        this.known.add(recipeKey);
    }

    public boolean contains(ResourceKey<Recipe<?>> recipeKey) {
        return this.known.contains(recipeKey);
    }

    public void remove(ResourceKey<Recipe<?>> recipeKey) {
        this.known.remove(recipeKey);
        this.highlight.remove(recipeKey);
    }

    public void removeHighlight(ResourceKey<Recipe<?>> recipeKey) {
        this.highlight.remove(recipeKey);
    }

    private void addHighlight(ResourceKey<Recipe<?>> recipeKey) {
        this.highlight.add(recipeKey);
    }

    public int addRecipes(Collection<RecipeHolder<?>> recipes, ServerPlayer player) {
        List<ClientboundRecipeBookAddPacket.Entry> list = new ArrayList();
        Iterator iterator = recipes.iterator();

        while (iterator.hasNext()) {
            RecipeHolder<?> recipeholder = (RecipeHolder) iterator.next();
            ResourceKey<Recipe<?>> resourcekey = recipeholder.id();

            if (!this.known.contains(resourcekey) && !recipeholder.value().isSpecial() && CraftEventFactory.handlePlayerRecipeListUpdateEvent(player, resourcekey.location())) { // CraftBukkit
                this.add(resourcekey);
                this.addHighlight(resourcekey);
                this.displayResolver.displaysForRecipe(resourcekey, (recipedisplayentry) -> {
                    list.add(new ClientboundRecipeBookAddPacket.Entry(recipedisplayentry, recipeholder.value().showNotification(), true));
                });
                CriteriaTriggers.RECIPE_UNLOCKED.trigger(player, recipeholder);
            }
        }

        if (!list.isEmpty() && player.connection != null) { // SPIGOT-4478 during PlayerLoginEvent
            player.connection.send(new ClientboundRecipeBookAddPacket(list, false));
        }

        return list.size();
    }

    public int removeRecipes(Collection<RecipeHolder<?>> recipes, ServerPlayer player) {
        List<RecipeDisplayId> list = Lists.newArrayList();
        Iterator iterator = recipes.iterator();

        while (iterator.hasNext()) {
            RecipeHolder<?> recipeholder = (RecipeHolder) iterator.next();
            ResourceKey<Recipe<?>> resourcekey = recipeholder.id();

            if (this.known.contains(resourcekey)) {
                this.remove(resourcekey);
                this.displayResolver.displaysForRecipe(resourcekey, (recipedisplayentry) -> {
                    list.add(recipedisplayentry.id());
                });
            }
        }

        if (!list.isEmpty() && player.connection != null) { // SPIGOT-4478 during PlayerLoginEvent
            player.connection.send(new ClientboundRecipeBookRemovePacket(list));
        }

        return list.size();
    }

    public CompoundTag toNbt() {
        CompoundTag nbttagcompound = new CompoundTag();

        this.getBookSettings().write(nbttagcompound);
        ListTag nbttaglist = new ListTag();
        Iterator iterator = this.known.iterator();

        while (iterator.hasNext()) {
            ResourceKey<Recipe<?>> resourcekey = (ResourceKey) iterator.next();

            nbttaglist.add(StringTag.valueOf(resourcekey.location().toString()));
        }

        nbttagcompound.put("recipes", nbttaglist);
        ListTag nbttaglist1 = new ListTag();
        Iterator iterator1 = this.highlight.iterator();

        while (iterator1.hasNext()) {
            ResourceKey<Recipe<?>> resourcekey1 = (ResourceKey) iterator1.next();

            nbttaglist1.add(StringTag.valueOf(resourcekey1.location().toString()));
        }

        nbttagcompound.put("toBeDisplayed", nbttaglist1);
        return nbttagcompound;
    }

    public void fromNbt(CompoundTag nbt, Predicate<ResourceKey<Recipe<?>>> validPredicate) {
        this.setBookSettings(RecipeBookSettings.read(nbt));
        ListTag nbttaglist = nbt.getList("recipes", 8);

        this.loadRecipes(nbttaglist, this::add, validPredicate);
        ListTag nbttaglist1 = nbt.getList("toBeDisplayed", 8);

        this.loadRecipes(nbttaglist1, this::addHighlight, validPredicate);
    }

    private void loadRecipes(ListTag list, Consumer<ResourceKey<Recipe<?>>> handler, Predicate<ResourceKey<Recipe<?>>> validPredicate) {
        for (int i = 0; i < list.size(); ++i) {
            String s = list.getString(i);

            try {
                ResourceKey<Recipe<?>> resourcekey = ResourceKey.create(Registries.RECIPE, ResourceLocation.parse(s));

                if (!validPredicate.test(resourcekey)) {
                    ServerRecipeBook.LOGGER.error("Tried to load unrecognized recipe: {} removed now.", resourcekey);
                } else {
                    handler.accept(resourcekey);
                }
            } catch (ResourceLocationException resourcekeyinvalidexception) {
                ServerRecipeBook.LOGGER.error("Tried to load improperly formatted recipe: {} removed now.", s);
            }
        }

    }

    public void sendInitialRecipeBook(ServerPlayer player) {
        player.connection.send(new ClientboundRecipeBookSettingsPacket(this.getBookSettings()));
        List<ClientboundRecipeBookAddPacket.Entry> list = new ArrayList(this.known.size());
        Iterator iterator = this.known.iterator();

        while (iterator.hasNext()) {
            ResourceKey<Recipe<?>> resourcekey = (ResourceKey) iterator.next();

            this.displayResolver.displaysForRecipe(resourcekey, (recipedisplayentry) -> {
                list.add(new ClientboundRecipeBookAddPacket.Entry(recipedisplayentry, false, this.highlight.contains(resourcekey)));
            });
        }

        player.connection.send(new ClientboundRecipeBookAddPacket(list, true));
    }

    public void copyOverData(ServerRecipeBook recipeBook) {
        this.known.clear();
        this.highlight.clear();
        this.bookSettings.replaceFrom(recipeBook.bookSettings);
        this.known.addAll(recipeBook.known);
        this.highlight.addAll(recipeBook.highlight);
    }

    @FunctionalInterface
    public interface DisplayResolver {

        void displaysForRecipe(ResourceKey<Recipe<?>> recipeKey, Consumer<RecipeDisplayEntry> adder);
    }
}
