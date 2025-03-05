package net.minecraft.server;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import net.minecraft.util.Unit;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;

public class ReloadableServerResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CompletableFuture<Unit> DATA_RELOAD_INITIAL_TASK = CompletableFuture.completedFuture(Unit.INSTANCE);
    private final ReloadableServerRegistries.Holder fullRegistryHolder;
    public Commands commands;
    private final RecipeManager recipes;
    private final ServerAdvancementManager advancements;
    private final ServerFunctionLibrary functionLibrary;
    private final List<Registry.PendingTags<?>> postponedTags;

    private ReloadableServerResources(
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries,
        HolderLookup.Provider registries,
        FeatureFlagSet enabledFeatures,
        Commands.CommandSelection environment,
        List<Registry.PendingTags<?>> pendingTagLoads,
        int functionPermissionLevel
    ) {
        this.fullRegistryHolder = new ReloadableServerRegistries.Holder(dynamicRegistries.compositeAccess());
        this.postponedTags = pendingTagLoads;
        this.recipes = new RecipeManager(registries);
        this.commands = new Commands(environment, CommandBuildContext.simple(registries, enabledFeatures));
        io.papermc.paper.command.brigadier.PaperCommands.INSTANCE.setDispatcher(this.commands, CommandBuildContext.simple(registries, enabledFeatures)); // Paper - Brigadier Command API
        this.advancements = new ServerAdvancementManager(registries);
        this.functionLibrary = new ServerFunctionLibrary(functionPermissionLevel, this.commands.getDispatcher());
    }

    public ServerFunctionLibrary getFunctionLibrary() {
        return this.functionLibrary;
    }

    public ReloadableServerRegistries.Holder fullRegistries() {
        return this.fullRegistryHolder;
    }

    public RecipeManager getRecipeManager() {
        return this.recipes;
    }

    public Commands getCommands() {
        return this.commands;
    }

    public ServerAdvancementManager getAdvancements() {
        return this.advancements;
    }

    public List<PreparableReloadListener> listeners() {
        return List.of(this.recipes, this.functionLibrary, this.advancements);
    }

    public static CompletableFuture<ReloadableServerResources> loadResources(
        ResourceManager resourceManager,
        LayeredRegistryAccess<RegistryLayer> dynamicRegistries,
        List<Registry.PendingTags<?>> pendingTagLoads,
        FeatureFlagSet enabledFeatures,
        Commands.CommandSelection environment,
        int functionPermissionLevel,
        Executor prepareExecutor,
        Executor applyExecutor
    ) {
        return ReloadableServerRegistries.reload(dynamicRegistries, pendingTagLoads, resourceManager, prepareExecutor)
            .thenCompose(
                reloadResult -> {
                    ReloadableServerResources reloadableServerResources = new ReloadableServerResources(
                        reloadResult.layers(), reloadResult.lookupWithUpdatedTags(), enabledFeatures, environment, pendingTagLoads, functionPermissionLevel
                    );
                    // Paper start - call commands event for bootstraps
                    //noinspection ConstantValue
                    io.papermc.paper.plugin.lifecycle.event.LifecycleEventRunner.INSTANCE.callReloadableRegistrarEvent(
                        io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS,
                        io.papermc.paper.command.brigadier.PaperCommands.INSTANCE,
                        io.papermc.paper.plugin.bootstrap.BootstrapContext.class,
                        MinecraftServer.getServer() == null ? io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.INITIAL : io.papermc.paper.plugin.lifecycle.event.registrar.ReloadableRegistrarEvent.Cause.RELOAD);
                    // Paper end - call commands event
                    return SimpleReloadInstance.create(
                            resourceManager,
                            reloadableServerResources.listeners(),
                            prepareExecutor,
                            applyExecutor,
                            DATA_RELOAD_INITIAL_TASK,
                            LOGGER.isDebugEnabled()
                        )
                        .done()
                        .thenApply(void_ -> reloadableServerResources);
                }
            );
    }

    public void updateStaticRegistryTags() {
        this.postponedTags.forEach(Registry.PendingTags::apply);
    }
}
