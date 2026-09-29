package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread media adapter. Implementations must not load chunks or grant input/ROM access. */
public interface WatchProvider {
    List<WatchSource> sources(MinecraftServer server);
    boolean isCurrent(MinecraftServer server, WatchSource source);
    /** True for any controller of this provider, including loading/joining sessions. */
    boolean isParticipant(MinecraftServer server, UUID player);
    default boolean canObserve(ServerPlayer player, WatchSource source) { return true; }
    /** Optional native, receive-only observation; null retains the existing media lane. */
    default WatchNetplay.Offer netplay(ServerPlayer player,WatchSource source) { return null; }
    /** Room media already has an authorized ingress and must not gain a second upload route. */
    default boolean acceptsUpload() { return true; }
    /** Trusted server implementation only; clients cannot set this property in any packet. */
    default boolean serverHosted(MinecraftServer server,WatchSource source) { return false; }
    /**
     * Number (0..2) of already-authorized remote controllers needing this host's media.
     * This does not create observation leases or input authority. Implementations must check
     * the exact live source/host generation and current recipient connection/controller lease.
     * Invalid counts or provider failures disable control delivery, never widen it.
     */
    default int controlRecipients(MinecraftServer server,WatchSource source) { return 0; }
    /**
     * Complete authenticated host upload, with shared egress budget already reserved for the
     * declared controlRecipients count. Send only to that current authorized recipient set,
     * using CabinetMediaSender's physical-connection window; never queue/retry or send to the host.
     * The count is not a new authorization mechanism. Recheck each recipient's existing authority.
     * This hook is never called for observer leases or generic room/server-hosted relay calls.
     */
    default void relayControls(MinecraftServer server,WatchSource source,List<CabinetMediaPacket> batch) {}
}
