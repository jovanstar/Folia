package net.minecraft.server.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.annotation.Nullable;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.TickablePacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundServerLinksPacket;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.common.custom.BrandPayload;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.network.protocol.configuration.ServerboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.ServerLinks;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.JoinWorldTask;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import net.minecraft.server.packs.repository.KnownPack;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.flag.FeatureFlags;
import org.slf4j.Logger;

// CraftBukkit start
import org.bukkit.craftbukkit.CraftServerLinks;
import org.bukkit.event.player.PlayerLinksSendEvent;
// CraftBukkit end

public class ServerConfigurationPacketListenerImpl extends ServerCommonPacketListenerImpl implements ServerConfigurationPacketListener, TickablePacketListener {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Component DISCONNECT_REASON_INVALID_DATA = Component.translatable("multiplayer.disconnect.invalid_player_data");
    private final GameProfile gameProfile;
    private final Queue<ConfigurationTask> configurationTasks = new ConcurrentLinkedQueue();
    @Nullable
    private ConfigurationTask currentTask;
    private ClientInformation clientInformation;
    @Nullable
    private SynchronizeRegistriesTask synchronizeRegistriesTask;
    public boolean switchToMain = false; // Folia - region threading - rewrite login process

    // CraftBukkit start
    public ServerConfigurationPacketListenerImpl(MinecraftServer minecraftserver, Connection networkmanager, CommonListenerCookie commonlistenercookie, ServerPlayer player) {
        super(minecraftserver, networkmanager, commonlistenercookie, player);
        // CraftBukkit end
        this.gameProfile = commonlistenercookie.gameProfile();
        this.clientInformation = commonlistenercookie.clientInformation();
    }

    @Override
    protected GameProfile playerProfile() {
        return this.gameProfile;
    }

    @Override
    public void onDisconnect(DisconnectionDetails info) {
        // Paper start - Debugging
        if (net.minecraft.server.MinecraftServer.getServer().isDebugging()) {
            ServerConfigurationPacketListenerImpl.LOGGER.info("{} lost connection: {}, while in configuration phase {}", this.gameProfile, info.reason().getString(), currentTask != null ? currentTask.type().id() : "null");
        } else // Paper end
        ServerConfigurationPacketListenerImpl.LOGGER.info("{} lost connection: {}", this.gameProfile, info.reason().getString());
        super.onDisconnect(info);
    }

    @Override
    public boolean isAcceptingMessages() {
        return this.connection.isConnected();
    }

    public void startConfiguration() {
        this.send(new ClientboundCustomPayloadPacket(new BrandPayload(this.server.getServerModName())));
        ServerLinks serverlinks = this.server.serverLinks();
        // CraftBukkit start
        CraftServerLinks wrapper = new CraftServerLinks(serverlinks);
        PlayerLinksSendEvent event = new PlayerLinksSendEvent(this.player.getBukkitEntity(), wrapper);
        this.player.getBukkitEntity().getServer().getPluginManager().callEvent(event);
        serverlinks = wrapper.getServerLinks();
        // CraftBukkit end

        if (!serverlinks.isEmpty()) {
            this.send(new ClientboundServerLinksPacket(serverlinks.untrust()));
        }

        LayeredRegistryAccess<RegistryLayer> layeredregistryaccess = this.server.registries();
        List<KnownPack> list = this.server.getResourceManager().listPacks().flatMap((iresourcepack) -> {
            return iresourcepack.location().knownPackInfo().stream();
        }).toList();

        this.send(new ClientboundUpdateEnabledFeaturesPacket(FeatureFlags.REGISTRY.toNames(this.server.getWorldData().enabledFeatures())));
        this.synchronizeRegistriesTask = new SynchronizeRegistriesTask(list, layeredregistryaccess);
        this.configurationTasks.add(this.synchronizeRegistriesTask);
        this.addOptionalTasks();
        this.configurationTasks.add(new JoinWorldTask());
        this.startNextTask();
    }

    public void returnToWorld() {
        this.configurationTasks.add(new JoinWorldTask());
        this.startNextTask();
    }

    private void addOptionalTasks() {
        this.server.getServerResourcePack().ifPresent((minecraftserver_serverresourcepackinfo) -> {
            this.configurationTasks.add(new ServerResourcePackConfigurationTask(minecraftserver_serverresourcepackinfo));
        });
    }

    @Override
    public void handleClientInformation(ServerboundClientInformationPacket packet) {
        this.clientInformation = packet.information();
        this.connection.channel.attr(io.papermc.paper.adventure.PaperAdventure.LOCALE_ATTRIBUTE).set(net.kyori.adventure.translation.Translator.parseLocale(packet.information().language())); // Paper
    }

    @Override
    public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
        super.handleResourcePackResponse(packet);
        if (packet.action().isTerminal()) {
            this.finishCurrentTask(ServerResourcePackConfigurationTask.TYPE);
        }

    }

    @Override
    public void handleSelectKnownPacks(ServerboundSelectKnownPacks packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, (BlockableEventLoop) this.server);
        if (this.synchronizeRegistriesTask == null) {
            throw new IllegalStateException("Unexpected response from client: received pack selection, but no negotiation ongoing");
        } else {
            this.synchronizeRegistriesTask.handleResponse(packet.knownPacks(), this::send);
            this.finishCurrentTask(SynchronizeRegistriesTask.TYPE);
        }
    }

    @Override
    public void handleConfigurationFinished(ServerboundFinishConfigurationPacket packet) {
        PacketUtils.ensureRunningOnSameThread(packet, this, (BlockableEventLoop) this.server);
        this.finishCurrentTask(JoinWorldTask.TYPE);
        this.connection.setupOutboundProtocol(GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(this.server.registryAccess())));

        try {
            PlayerList playerlist = this.server.getPlayerList();

            if (playerlist.getPlayer(this.gameProfile.getId()) != null) {
                this.disconnect(PlayerList.DUPLICATE_LOGIN_DISCONNECT_MESSAGE);
                return;
            }

            Component ichatbasecomponent = null; // CraftBukkit - login checks already completed

            if (ichatbasecomponent != null) {
                this.disconnect(ichatbasecomponent);
                return;
            }

            ServerPlayer entityplayer = playerlist.getPlayerForLogin(this.gameProfile, this.clientInformation, this.player); // CraftBukkit

            // Folia start - region threading - rewrite login process
            io.papermc.paper.threadedregions.RegionizedServer.ensureGlobalTickThread("Cannot handle player login off global tick thread");
            CommonListenerCookie clientData = this.createCookie(this.clientInformation);
            org.apache.commons.lang3.mutable.MutableObject<net.minecraft.nbt.CompoundTag> data = new org.apache.commons.lang3.mutable.MutableObject<>();
            org.apache.commons.lang3.mutable.MutableObject<String> lastKnownName = new org.apache.commons.lang3.mutable.MutableObject<>();
            ca.spottedleaf.concurrentutil.completable.CallbackCompletable<org.bukkit.Location> toComplete = new ca.spottedleaf.concurrentutil.completable.CallbackCompletable<>();
            // note: need to call addWaiter before completion to ensure the callback is invoked synchronously
            // the loadSpawnForNewPlayer function always completes the completable once the chunks were loaded,
            // on the load callback for those chunks (so on the same region)
            // this guarantees the chunk cannot unload under our feet
            toComplete.addWaiter((org.bukkit.Location loc, Throwable t) -> {
                int chunkX = net.minecraft.util.Mth.floor(loc.getX()) >> 4;
                int chunkZ = net.minecraft.util.Mth.floor(loc.getZ()) >> 4;

                net.minecraft.server.level.ServerLevel world = ((org.bukkit.craftbukkit.CraftWorld)loc.getWorld()).getHandle();
                // we just need to hold the chunks at loaded until the next tick
                // so we do not need to care about unique IDs for the ticket
                world.getChunkSource().addTicketAtLevel(
                    net.minecraft.server.level.TicketType.LOGIN,
                    new net.minecraft.world.level.ChunkPos(chunkX, chunkZ),
                    ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager.FULL_LOADED_TICKET_LEVEL,
                    net.minecraft.util.Unit.INSTANCE
                );

                io.papermc.paper.threadedregions.RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(
                    world, chunkX, chunkZ,
                    () -> {
                        // once switchToMain is set, the current ticking region now owns the connection and is responsible
                        // for cleaning it up
                        playerlist.placeNewPlayer(
                            ServerConfigurationPacketListenerImpl.this.connection,
                            entityplayer,
                            clientData,
                            java.util.Optional.ofNullable(data.getValue()),
                            lastKnownName.getValue(),
                            loc
                        );
                    },
                    ca.spottedleaf.concurrentutil.util.Priority.HIGHER
                );
            });
            this.switchToMain = true;
            try {
                // now the connection responsibility is transferred on the region
                playerlist.loadSpawnForNewPlayer(this.connection, entityplayer, clientData, data, lastKnownName, toComplete);
            } catch (final Throwable throwable) {
                // assume toComplete will not be invoked
                // ensure global tick thread owns the connection again, to properly disconnect it
                this.switchToMain = false;
                throw new RuntimeException(throwable);
            }
            // Folia end - region threading - rewrite login process
        } catch (Exception exception) {
            ServerConfigurationPacketListenerImpl.LOGGER.error("Couldn't place player in world", exception);
            // Paper start - Debugging
            if (MinecraftServer.getServer().isDebugging()) {
                exception.printStackTrace();
            }
            // Paper end - Debugging
            this.connection.send(new ClientboundDisconnectPacket(ServerConfigurationPacketListenerImpl.DISCONNECT_REASON_INVALID_DATA));
            this.connection.disconnect(ServerConfigurationPacketListenerImpl.DISCONNECT_REASON_INVALID_DATA);
        }

    }

    @Override
    public void tick() {
        this.keepConnectionAlive();
    }

    private void startNextTask() {
        if (this.currentTask != null) {
            throw new IllegalStateException("Task " + this.currentTask.type().id() + " has not finished yet");
        } else if (this.isAcceptingMessages()) {
            ConfigurationTask configurationtask = (ConfigurationTask) this.configurationTasks.poll();

            if (configurationtask != null) {
                this.currentTask = configurationtask;
                configurationtask.start(this::send);
            }

        }
    }

    private void finishCurrentTask(ConfigurationTask.Type key) {
        ConfigurationTask.Type configurationtask_a1 = this.currentTask != null ? this.currentTask.type() : null;

        if (!key.equals(configurationtask_a1)) {
            String s = String.valueOf(configurationtask_a1);

            throw new IllegalStateException("Unexpected request for task finish, current task: " + s + ", requested: " + String.valueOf(key));
        } else {
            this.currentTask = null;
            this.startNextTask();
        }
    }
}
