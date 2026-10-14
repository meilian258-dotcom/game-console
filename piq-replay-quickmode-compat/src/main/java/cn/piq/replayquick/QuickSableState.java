package cn.piq.replayquick;

import cn.piq.replayquick.mixin.QuickModeIndicatorAccessor;
import com.replaymod.extras.QuickMode;
import com.replaymod.extras.ReplayModExtras;
import com.replaymod.replay.QuickReplaySender;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import com.replaymod.replaystudio.protocol.PacketTypeRegistry;
import com.replaymod.replaystudio.rar.RandomAccessReplay;
import com.replaymod.replaystudio.replay.ReplayFile;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.replaymod.core.versions.MCVer.getPacketTypeRegistry;

/** Only adapts recorded players; this is not a Sable vehicle/sub-world replay implementation. */
public final class QuickSableState {
    private static final Logger LOG = LoggerFactory.getLogger("PIQ-ReplayQuickMode");
    private final QuickReplaySender owner;
    private final ReplayFile file;
    @FunctionalInterface interface PositionProof { boolean outsideSubLevels(ChannelPipeline pipeline, double x, double y, double z); }
    private final PositionProof positionProof;
    private volatile SableFlagTimeline flags;
    private volatile int teleportId = -1;
    private boolean returningToNormal;
    private boolean reportedRepair;
    private boolean reportedWorldSpace;

    public QuickSableState(QuickReplaySender owner, ReplayFile file) {
        this(owner, file, SablePositionSpace::outsideSubLevels);
    }

    // Package-private seam for isolated behavior tests. The production constructor always uses the real world.
    QuickSableState(QuickReplaySender owner, ReplayFile file, PositionProof positionProof) {
        this.owner = owner;
        this.file = file;
        this.positionProof = java.util.Objects.requireNonNull(positionProof);
    }

    /** Called on the existing quick-index worker, including when RAR's cache is already present. */
    public void load() throws IOException {
        if (!SableWireContract.supported()) return;
        PacketTypeRegistry registry = getPacketTypeRegistry(State.PLAY);
        if (registry.getVersion().getOriginalVersion() != 767
                || file.getMetaData().getProtocolVersion().getOriginalVersion() != 767) {
            throw new IOException("快速回放位置适配仅核验 Minecraft 1.21.1 录像，请使用普通模式。");
        }
        SableFlagTimeline loaded = SableFlagTimeline.read(file, registry);
        teleportId = registry.getId(PacketType.EntityTeleport);
        flags = loaded;
        LOG.info("Recorded Sable player position flags indexed for quick replay; recording/cache not rewritten");
    }

    public void seek(RandomAccessReplay replay, int target) throws IOException {
        if (flags == null) { replay.seek(target); return; }
        if (returningToNormal) return;
        try {
            QuickSableScope.seek(this, target, () -> replay.seek(target));
        } catch (MissingFlag failure) {
            // Do not fabricate a flag or keep displaying a partially reconstructed world.
            // The outer sender commits targetTime before Replay's deferred task executes.
            returningToNormal = true;
            owner.setReplaySpeed(0);
            var mod = ReplayModReplay.instance;
            var handler = mod.getReplayHandler();
            LOG.warn("Quick player position cannot be reconstructed safely; returning to paused normal replay (reason={}, targetMs={})",
                    failure.reason, target);
            mod.getCore().runLater(() -> {
                try {
                    if (mod.getReplayHandler() != handler || handler == null || !handler.isQuickMode()
                            || handler.getReplaySender() != owner) return;
                    owner.setSyncModeAndWait();
                    handler.setQuickMode(false);
                    handler.getReplaySender().setReplaySpeed(0);
                    handler.getReplaySender().setAsyncMode(true);
                    var extras = ReplayModExtras.instance;
                    if (extras != null) extras.get(QuickMode.class).ifPresent(quick ->
                            ((QuickModeIndicatorAccessor) (Object) quick).piq$updateIndicator(handler.getOverlay(), false));
                    mod.getCore().printWarningToChat("此处的玩家位置仍无法安全恢复（" + failure.reason + "），已返回普通回放并暂停。");
                } finally { returningToNormal = false; }
            });
        }
    }

    public static ChannelPipeline dispatch(ChannelPipeline pipeline, Object value, Packet original) {
        var scope = QuickSableScope.forPacket(original);
        if (scope == null || !(value instanceof ByteBuf bytes)
                || !(pipeline.channel() instanceof EmbeddedChannel)) return pipeline.fireChannelRead(value);
        QuickSableState state = scope.state();
        var mod = ReplayModReplay.instance;
        var handler = mod == null ? null : mod.getReplayHandler();
        if (state.flags == null || handler == null || !handler.isQuickMode()
                || handler.getReplaySender() != state.owner) return pipeline.fireChannelRead(value);
        try {
            if (state.complete(bytes, scope.targetTime(), pipeline) && !state.reportedRepair) {
                state.reportedRepair = true;
                LOG.info("Completed a generated quick-replay player teleport with a recorded flag or verified world-space equivalence");
            }
        } catch (MissingFlag failure) {
            bytes.release(); // This newly allocated dispatch buffer has not entered Netty yet.
            throw failure;
        }
        return pipeline.fireChannelRead(value);
    }

    private boolean complete(ByteBuf bytes, int target) {
        return complete(bytes, target, null); // Isolated tests use an explicit package-private proof fixture.
    }

    private boolean complete(ByteBuf bytes, int target, ChannelPipeline pipeline) {
        ByteBuf cursor = bytes.duplicate();
        int packetId = readVarInt(cursor);
        if (packetId != teleportId) return false;
        int entity = readVarInt(cursor);
        if (!flags.isPlayerAt(entity, target)) return false;
        int remaining = cursor.readableBytes();
        if (remaining == 28) return false; // Already extended: never overwrite its contents.
        if (remaining != 27) throw new MissingFlag("unsupported-layout");
        var flag = flags.flagAt(entity, target);
        if (flag == SableFlagTimeline.Flag.UNKNOWN) {
            int at = cursor.readerIndex();
            double x = cursor.getDouble(at), y = cursor.getDouble(at + 8), z = cursor.getDouble(at + 16);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !positionProof.outsideSubLevels(pipeline, x, y, z))
                throw new MissingFlag("unproven-position-space");
            // This does not infer a historical flag or retain a previous entity's flag.
            // The reviewed Sable handler takes identical paths for either flag outside its actual plot space.
            flag = SableFlagTimeline.Flag.FALSE;
            if (!reportedWorldSpace) {
                reportedWorldSpace = true;
                LOG.info("Completed an unmarked quick player position outside the verified active Sable plot space; recorded index remains unknown");
            }
        }
        bytes.writeBoolean(flag == SableFlagTimeline.Flag.TRUE);
        return true;
    }

    private static int readVarInt(ByteBuf cursor) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!cursor.isReadable()) throw new MissingFlag("invalid-varint");
            int b = cursor.readUnsignedByte();
            if (shift == 28 && (b & 0xf0) != 0) throw new MissingFlag("invalid-varint");
            result |= (b & 127) << shift;
            if ((b & 128) == 0) return result;
        }
        throw new MissingFlag("invalid-varint");
    }

    private static final class MissingFlag extends RuntimeException {
        private final String reason;
        private MissingFlag(String reason) { super("Unproven quick player position: " + reason, null, false, false); this.reason = reason; }
    }
}
