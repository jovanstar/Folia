package net.minecraft.network.protocol;

import com.mojang.logging.LogUtils;
import javax.annotation.Nullable;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.network.PacketListener;
import org.slf4j.Logger;

// CraftBukkit start
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
// CraftBukkit end
import net.minecraft.util.thread.BlockableEventLoop;

public class PacketUtils {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Paper start - detailed watchdog information
    public static final java.util.concurrent.ConcurrentLinkedDeque<PacketListener> packetProcessing = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final java.util.concurrent.atomic.AtomicLong totalMainThreadPacketsProcessed = new java.util.concurrent.atomic.AtomicLong();

    public static long getTotalProcessedPackets() {
        return totalMainThreadPacketsProcessed.get();
    }

    public static java.util.List<PacketListener> getCurrentPacketProcessors() {
        java.util.List<PacketListener> ret = new java.util.ArrayList<>(4);
        for (PacketListener listener : packetProcessing) {
            ret.add(listener);
        }

        return ret;
    }
    // Paper end - detailed watchdog information

    public PacketUtils() {}

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, ServerLevel world) throws RunningOnDifferentThreadException {
        PacketUtils.ensureRunningOnSameThread(packet, listener, (BlockableEventLoop) world.getServer());
    }

    public static <T extends PacketListener> void ensureRunningOnSameThread(Packet<T> packet, T listener, BlockableEventLoop<?> engine) throws RunningOnDifferentThreadException {
        if (!engine.isSameThread()) {
            Runnable run = () -> { // Folia - region threading
                packetProcessing.push(listener); // Paper - detailed watchdog information
                try { // Paper - detailed watchdog information
                if (listener instanceof ServerCommonPacketListenerImpl serverCommonPacketListener && serverCommonPacketListener.processedDisconnect) return; // CraftBukkit - Don't handle sync packets for kicked players
                if (listener.shouldHandleMessage(packet)) {
                    try {
                        final ca.spottedleaf.leafprofiler.RegionizedProfiler.Handle profiler = io.papermc.paper.threadedregions.TickRegionScheduler.getProfiler(); // Folia - profiler
                        final int packetTimerId = profiler.getOrCreateTimerAndStart(() -> "Packet Handler: ".concat(io.papermc.paper.util.ObfHelper.INSTANCE.deobfClassName(packet.getClass().getName()))); try { // Folia - profiler
                        packet.handle(listener);
                        } finally { profiler.stopTimer(packetTimerId); } // Folia - profiler
                    } catch (Exception exception) {
                        if (exception instanceof ReportedException) {
                            ReportedException reportedexception = (ReportedException) exception;

                            if (reportedexception.getCause() instanceof OutOfMemoryError) {
                                throw PacketUtils.makeReportedException(exception, packet, listener);
                            }
                        }

                        listener.onPacketError(packet, exception);
                    }
                } else {
                    PacketUtils.LOGGER.debug("Ignoring packet due to disconnection: {}", packet);
                }
                // Paper start - detailed watchdog information
                } finally {
                    totalMainThreadPacketsProcessed.getAndIncrement();
                    packetProcessing.pop();
                }
                // Paper end - detailed watchdog information

            }; // Folia start - region threading
            // ignore retired state, if removed then we don't want the packet to be handled
            if (listener instanceof net.minecraft.server.network.ServerGamePacketListenerImpl gamePacketListener) {
                gamePacketListener.player.getBukkitEntity().taskScheduler.schedule(
                    (net.minecraft.server.level.ServerPlayer player) -> {
                        run.run();
                    },
                    null, 1L
                );
            } else if (listener instanceof net.minecraft.server.network.ServerConfigurationPacketListenerImpl configurationPacketListener) {
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(run);
            } else if (listener instanceof net.minecraft.server.network.ServerLoginPacketListenerImpl loginPacketListener) {
                io.papermc.paper.threadedregions.RegionizedServer.getInstance().addTask(run);
            } else {
                throw new UnsupportedOperationException("Unknown listener: " + listener);
            }
            // Folia end - region threading
            throw RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD;
        }
    }

    public static <T extends PacketListener> ReportedException makeReportedException(Exception exception, Packet<T> packet, T listener) {
        if (exception instanceof ReportedException reportedexception) {
            PacketUtils.fillCrashReport(reportedexception.getReport(), listener, packet);
            return reportedexception;
        } else {
            CrashReport crashreport = CrashReport.forThrowable(exception, "Main thread packet handler");

            PacketUtils.fillCrashReport(crashreport, listener, packet);
            return new ReportedException(crashreport);
        }
    }

    public static <T extends PacketListener> void fillCrashReport(CrashReport report, T listener, @Nullable Packet<T> packet) {
        if (packet != null) {
            CrashReportCategory crashreportsystemdetails = report.addCategory("Incoming Packet");

            crashreportsystemdetails.setDetail("Type", () -> {
                return packet.type().toString();
            });
            crashreportsystemdetails.setDetail("Is Terminal", () -> {
                return Boolean.toString(packet.isTerminal());
            });
            crashreportsystemdetails.setDetail("Is Skippable", () -> {
                return Boolean.toString(packet.isSkippable());
            });
        }

        listener.fillCrashReport(report);
    }
}
