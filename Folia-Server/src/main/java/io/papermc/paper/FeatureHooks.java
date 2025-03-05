package io.papermc.paper;

import io.papermc.paper.command.PaperSubcommand;
import io.papermc.paper.command.subcommands.ChunkDebugCommand;
import io.papermc.paper.command.subcommands.FixLightCommand;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Registry;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.bukkit.Chunk;
import org.bukkit.World;

public final class FeatureHooks {

    public static void initChunkTaskScheduler(final boolean useParallelGen) {
        ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler.init(useParallelGen); // Paper - Chunk system
    }

    public static void registerPaperCommands(final Map<Set<String>, PaperSubcommand> commands) {
        commands.put(Set.of("fixlight"), new FixLightCommand()); // Paper - rewrite chunk system
        commands.put(Set.of("debug", "chunkinfo", "holderinfo"), new ChunkDebugCommand());  // Paper - rewrite chunk system
    }

    public static LevelChunkSection createSection(final Registry<Biome> biomeRegistry, final Level level, final ChunkPos chunkPos, final int chunkSection) {
        return new LevelChunkSection(biomeRegistry, level, chunkPos, chunkSection); // Paper - Anti-Xray - Add parameters
    }

    public static void sendChunkRefreshPackets(final List<ServerPlayer> playersInRange, final LevelChunk chunk) {
        // Paper start - Anti-Xray
        final Map<Object, ClientboundLevelChunkWithLightPacket> refreshPackets = new HashMap<>();
        for (final ServerPlayer player : playersInRange) {
            if (player.connection == null) continue;

            final Boolean shouldModify = chunk.getLevel().chunkPacketBlockController.shouldModify(player, chunk);
            player.connection.send(refreshPackets.computeIfAbsent(shouldModify, s -> { // Use connection to prevent creating firing event
                return new ClientboundLevelChunkWithLightPacket(chunk, chunk.level.getLightEngine(), null, null, (Boolean) s);
            }));
        }
        // Paper end - Anti-Xray
    }

    public static PalettedContainer<BlockState> emptyPalettedBlockContainer() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES, null); // Paper - Anti-Xray - Add preset block states
    }

    public static Set<Long> getSentChunkKeys(final ServerPlayer player) {
        return LongSets.unmodifiable(player.moonrise$getChunkLoader().getSentChunksRaw().clone());
    }

    public static Set<Chunk> getSentChunks(final ServerPlayer player) {
        final LongOpenHashSet rawChunkKeys = player.moonrise$getChunkLoader().getSentChunksRaw();
        final ObjectSet<org.bukkit.Chunk> chunks = new ObjectOpenHashSet<>(rawChunkKeys.size());
        final World world = player.serverLevel().getWorld();
        final LongIterator iter = player.moonrise$getChunkLoader().getSentChunksRaw().longIterator();
        while (iter.hasNext()) {
            chunks.add(world.getChunkAt(iter.nextLong(), false));
        }
        return ObjectSets.unmodifiable(chunks);
    }

    public static boolean isChunkSent(final ServerPlayer player, final long chunkKey) {
        return player.moonrise$getChunkLoader().getSentChunksRaw().contains(chunkKey);
    }
}
