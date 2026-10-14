package cn.piq.replayquick;

import com.github.steveice10.netty.buffer.Unpooled;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import com.replaymod.replaystudio.protocol.*;
import com.replaymod.replaystudio.protocol.packets.*;
import com.replaymod.replaystudio.protocol.registry.DimensionType;
import com.replaymod.replaystudio.replay.ReplayMetaData;
import com.replaymod.replaystudio.util.*;
import java.io.IOException;
import java.util.*;
import static cn.piq.replayquick.SableFlagTimeline.Flag.*;

public final class RecordedDimensionTest {
    static final PacketTypeRegistry REGISTRY = PacketTypeRegistry.get(ProtocolVersion.getProtocol(767), State.PLAY);
    static final String OVERWORLD = "minecraft:overworld", NETHER = "minecraft:the_nether";
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    static void reject(Action action) throws Exception { try { action.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) { checks++; } }
    public static void main(String[] args) { try { run(); System.exit(0); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); } }
    static void run() throws Exception {
        var state = new RecordedSableIndex.ScanState(new HashSet<>());
        var builder = new SableFlagTimeline.Builder();
        apply(builder, state, 0, join(OVERWORLD, true));
        builder.spawn(1, 42, true); builder.observe(2, 42, TRUE);
        apply(builder, state, 3, respawn(OVERWORLD, true));
        check(builder.build().isPlayerAt(42, 3)); check(builder.build().flagAt(42, 3) == TRUE);
        builder.observe(4, 42, FALSE); check(builder.build().flagAt(42, 4) == FALSE);
        apply(builder, state, 5, respawn(NETHER, false));
        check(!builder.build().isPlayerAt(42, 5)); check(builder.build().flagAt(42, 5) == UNKNOWN);
        builder.observe(6, 42, TRUE); check(builder.build().flagAt(42, 6) == UNKNOWN);
        builder.spawn(7, 42, true); builder.observe(8, 42, TRUE);
        apply(builder, state, 9, join(NETHER, false)); // Join always establishes a fresh world, even same dimension.
        check(builder.build().flagAt(42, 9) == UNKNOWN);
        reject(() -> apply(new SableFlagTimeline.Builder(), new RecordedSableIndex.ScanState(new HashSet<>()), 0, respawn(OVERWORLD, false)));
        for (boolean login : new boolean[]{false, true}) {
            Packet good = login ? join(OVERWORLD, true) : respawn(OVERWORLD, true);
            try {
                check(RecordedPlayerIdentities.dimension(good).equals(OVERWORLD));
                byte[] bytes = new byte[good.getBuf().readableBytes()]; good.getBuf().getBytes(good.getBuf().readerIndex(), bytes);
                for (int end = 0; end < bytes.length; end++) {
                    byte[] truncated = Arrays.copyOf(bytes, end);
                    reject(() -> dimension(good.getType(), truncated));
                }
                reject(() -> dimension(good.getType(), Arrays.copyOf(bytes, bytes.length + 1)));
            } finally { good.release(); }
        }
        reject(() -> { Packet p = respawn("Bad Namespace:world", false); try { RecordedPlayerIdentities.dimension(p); } finally { p.release(); } });
        reject(() -> dimension(PacketType.Respawn, new byte[]{0, -1, -1, -1, -1, 7}));
        reject(() -> dimension(PacketType.JoinGame, new byte[]{0, 0, 0, 0, 0, -1, -1, -1, -1, 7}));
        var metadata = new ReplayMetaData(); metadata.setProtocolVersion(767); metadata.setMcVersion("1.21.1");
        RecordedSableIndex.validateMetadata(metadata, REGISTRY); checks++;
        metadata.setMcVersion("1.20.1"); reject(() -> RecordedSableIndex.validateMetadata(metadata, REGISTRY));
        metadata.setMcVersion("1.21.1"); metadata.setProtocolVersion(766); reject(() -> RecordedSableIndex.validateMetadata(metadata, REGISTRY));
        reject(() -> RecordedSableIndex.validateMetadata(new ReplayMetaData(), REGISTRY));
        System.out.println("RecordedDimensionTest checks=" + checks + " PASS (actual upstream Join/Respawn writer; same/cross-world, malformed, metadata)");
    }
    static Packet join(String dimension, boolean death) throws IOException {
        var join = new PacketJoinGame(); join.entityId = 1; join.dimensions = List.of(OVERWORLD, NETHER);
        join.dimension = dimension; join.dimensionType = new DimensionType(OVERWORLD); join.prevGameMode = -1;
        join.maxPlayers = 100; join.viewDistance = 12; join.simulationDistance = 8;
        if (death) join.lastDeathPosition = new IGlobalPosition(NETHER, new IPosition(1, 2, 3));
        return join.write(REGISTRY);
    }
    static Packet respawn(String dimension, boolean death) throws IOException {
        var respawn = new PacketRespawn(); respawn.dimension = dimension; respawn.dimensionType = new DimensionType(OVERWORLD);
        respawn.prevGameMode = -1; respawn.keepPlayerAttributes = true; respawn.keepPlayerDataTracker = true;
        if (death) respawn.lastDeathPosition = new IGlobalPosition(OVERWORLD, new IPosition(1, 2, 3));
        return respawn.write(REGISTRY);
    }
    static void apply(SableFlagTimeline.Builder builder, RecordedSableIndex.ScanState state, int time, Packet packet) throws IOException {
        int reader = packet.getBuf().readerIndex(), refs = packet.getBuf().refCnt();
        try { RecordedSableIndex.consume(builder, time, packet, state); }
        finally { check(packet.getBuf().readerIndex() == reader); check(packet.getBuf().refCnt() == refs); packet.release(); }
    }
    static void dimension(PacketType type, byte[] bytes) throws IOException { var p = new Packet(REGISTRY, type, Unpooled.wrappedBuffer(bytes)); try { RecordedPlayerIdentities.dimension(p); } finally { p.release(); } }
}
