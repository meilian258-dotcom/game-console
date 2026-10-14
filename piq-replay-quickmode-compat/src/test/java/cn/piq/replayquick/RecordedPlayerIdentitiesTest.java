package cn.piq.replayquick;

import com.github.steveice10.netty.buffer.Unpooled;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import com.replaymod.replaystudio.protocol.*;
import com.replaymod.replaystudio.protocol.packets.PacketPlayerListEntry;
import java.io.*;
import java.util.*;

/** Executes the production reader; valid fixtures also go through the real ReplayStudio reader. */
public final class RecordedPlayerIdentitiesTest {
    private static final PacketTypeRegistry REGISTRY = PacketTypeRegistry.get(ProtocolVersion.getProtocol(767), State.PLAY);
    private static final UUID ID = new UUID(3, 7), OTHER = new UUID(4, 8);
    private static int checks;
    @FunctionalInterface interface Body { void write(DataOutputStream out) throws Exception; }
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        try { run(); System.exit(0); } catch (Throwable error) { error.printStackTrace(); System.exit(1); }
    }
    private static void run() throws Exception {
        for (int mask = 0; mask < 64; mask++) {
            int actions = mask;
            byte[] bytes = bytes(out -> {
                out.writeByte(actions); varint(out, 2);
                entry(out, ID, actions, true); entry(out, OTHER, actions, false);
            });
            Set<String> actual = new HashSet<>();
            Packet packet = packet(PacketType.PlayerListEntry, bytes);
            try {
                var decoded = PacketPlayerListEntry.read(packet);
                check(decoded.size() == 2 && decoded.getFirst().getUuid().equals(ID));
                apply(packet, actual);
                check(actual.equals((actions & 1) != 0 ? Set.of(ID.toString(), OTHER.toString()) : Set.of()));
                // Also consume the actual upstream writer's result, including all six action combinations.
                for (Packet rewritten : PacketPlayerListEntry.write(REGISTRY, PacketPlayerListEntry.getActions(packet), decoded)) {
                    try { Set<String> again = new HashSet<>(); apply(rewritten, again); check(again.equals(actual)); }
                    finally { rewritten.release(); }
                }
            } finally { packet.release(); }
        }
        byte[] removed = bytes(out -> { varint(out, 1); uuid(out, ID); });
        var players = new HashSet<>(Set.of(ID.toString(), OTHER.toString()));
        consume(PacketType.PlayerListEntryRemove, removed, players);
        check(players.equals(Set.of(OTHER.toString())));
        reject(PacketType.PlayerListEntryRemove, bytes(out -> varint(out, Integer.MAX_VALUE)));
        reject(PacketType.PlayerListEntryRemove, bytes(out -> varint(out, SableFlagTimeline.MAX_ENTITIES + 1)));
        reject(PacketType.PlayerListEntryRemove, new byte[]{-1, -1, -1, -1, 15});
        reject(PacketType.PlayerListEntry, new byte[]{64, 0});
        reject(PacketType.PlayerListEntry, new byte[]{0, 0, 1});
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(1); varint(out, 1); uuid(out, ID); utf(out, "x"); varint(out, Integer.MAX_VALUE); }));
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(1); varint(out, 1); uuid(out, ID); varint(out, Integer.MAX_VALUE); }));
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(2); varint(out, 1); uuid(out, ID); out.writeBoolean(true); uuid(out, OTHER); out.writeLong(0); varint(out, 513); }));
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(2); varint(out, 1); uuid(out, ID); out.writeBoolean(true); uuid(out, OTHER); out.writeLong(0); varint(out, 0); varint(out, 4097); }));
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(8); varint(out, 1); uuid(out, ID); out.writeByte(2); }));
        // Tiny inputs advertising huge NBT arrays/lists never allocate their advertised length.
        reject(PacketType.PlayerListEntry, display(out -> { out.writeByte(11); out.writeInt(Integer.MAX_VALUE); }));
        reject(PacketType.PlayerListEntry, display(out -> { out.writeByte(9); out.writeByte(0); out.writeInt(Integer.MAX_VALUE); }));
        reject(PacketType.PlayerListEntry, display(out -> { out.writeByte(9); out.writeByte(1); out.writeInt(-1); }));
        reject(PacketType.PlayerListEntry, display(out -> { out.writeByte(10); for (int i = 0; i < 66; i++) { out.writeByte(10); out.writeUTF("child"); } for (int i = 0; i < 67; i++) out.writeByte(0); }));
        // First complete entry must not be committed if the second entry is truncated.
        reject(PacketType.PlayerListEntry, bytes(out -> { out.writeByte(1); varint(out, 2); entry(out, ID, 1, false); uuid(out, OTHER); }));
        byte[] valid = bytes(out -> { out.writeByte(63); varint(out, 1); entry(out, ID, 63, true); });
        for (int end = 0; end < valid.length; end++) reject(PacketType.PlayerListEntry, Arrays.copyOf(valid, end));
        var full = new HashSet<String>();
        for (int i = 0; i < SableFlagTimeline.MAX_ENTITIES; i++) full.add(new UUID(0, i).toString());
        try { consume(PacketType.PlayerListEntry, valid, full); throw new AssertionError("identity cap"); }
        catch (IOException expected) { checks++; check(full.size() == SableFlagTimeline.MAX_ENTITIES); }
        Thread.currentThread().interrupt();
        try { reject(PacketType.PlayerListEntry, valid); } finally { Thread.interrupted(); }
        System.out.println("RecordedPlayerIdentitiesTest checks=" + checks + " PASS (64 real ReplayStudio action combinations, bounds, atomicity, no game)");
    }
    private static void entry(DataOutputStream out, UUID id, int actions, boolean optional) throws Exception {
        uuid(out, id);
        if ((actions & 1) != 0) {
            utf(out, "fixture"); varint(out, 1); utf(out, "textures"); utf(out, "value");
            out.writeBoolean(optional); if (optional) utf(out, "signature");
        }
        if ((actions & 2) != 0) {
            out.writeBoolean(optional);
            if (optional) { uuid(out, OTHER); out.writeLong(1234); varint(out, 3); out.write(new byte[]{1, 2, 3}); varint(out, 2); out.write(new byte[]{4, 5}); }
        }
        if ((actions & 4) != 0) varint(out, 1);
        if ((actions & 8) != 0) out.writeBoolean(optional);
        if ((actions & 16) != 0) varint(out, -1);
        if ((actions & 32) != 0) {
            out.writeBoolean(optional);
            if (optional) { // Anonymous compound containing a string and nested list.
                out.writeByte(10); out.writeByte(8); out.writeUTF("text"); out.writeUTF("fixture");
                out.writeByte(9); out.writeUTF("extra"); out.writeByte(8); out.writeInt(1); out.writeUTF("test"); out.writeByte(0);
            }
        }
    }
    static byte[] display(Body nbt) throws Exception { return bytes(out -> { out.writeByte(32); varint(out, 1); uuid(out, ID); out.writeBoolean(true); nbt.write(out); }); }
    static byte[] bytes(Body body) throws Exception { var bytes = new ByteArrayOutputStream(); body.write(new DataOutputStream(bytes)); return bytes.toByteArray(); }
    static void uuid(DataOutputStream out, UUID value) throws IOException { out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits()); }
    static void utf(DataOutputStream out, String value) throws IOException { byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); varint(out, bytes.length); out.write(bytes); }
    static void varint(DataOutputStream out, int value) throws IOException { while ((value & ~127) != 0) { out.writeByte((value & 127) | 128); value >>>= 7; } out.writeByte(value); }
    static Packet packet(PacketType type, byte[] bytes) { return new Packet(REGISTRY, type, Unpooled.wrappedBuffer(bytes)); }
    static void apply(Packet packet, Set<String> players) throws IOException {
        int position = packet.getBuf().readerIndex(), count = packet.getBuf().refCnt();
        try { RecordedPlayerIdentities.consume(packet, players); }
        finally { check(packet.getBuf().readerIndex() == position); check(packet.getBuf().refCnt() == count); }
    }
    static void consume(PacketType type, byte[] bytes, Set<String> players) throws IOException { Packet p = packet(type, bytes); try { apply(p, players); } finally { p.release(); } }
    static void reject(PacketType type, byte[] bytes) throws Exception {
        var players = new HashSet<>(Set.of(OTHER.toString()));
        try { consume(type, bytes, players); throw new AssertionError("Expected IOException"); }
        catch (IOException expected) { checks++; check(players.equals(Set.of(OTHER.toString()))); }
    }
}
