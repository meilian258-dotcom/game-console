package cn.piq.replayquick;

import com.github.steveice10.netty.buffer.ByteBuf;
import com.replaymod.replaystudio.protocol.Packet;
import com.replaymod.replaystudio.protocol.PacketType;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Protocol 767 UUID-only reader. Never allocates from an untrusted wire length. */
final class RecordedPlayerIdentities {
    static final int MAX_PROPERTIES = 1024, MAX_NBT_DEPTH = 64, MAX_NBT_NODES = 65_536;
    private RecordedPlayerIdentities() {}

    /** Fully bounded 767 Join/Respawn layout, with no registry, level or Minecraft bootstrap. */
    static String dimension(Packet packet) throws IOException {
        boolean join = packet.getType() == PacketType.JoinGame;
        if ((!join && packet.getType() != PacketType.Respawn)
                || packet.getProtocolVersion().getVersion() != 767
                || packet.getBuf().readableBytes() > RecordedSableIndex.MAX_PACKET_BYTES) throw invalid();
        Cursor in = new Cursor(packet.getBuf());
        if (join) {
            in.skip(4); in.bool(); // player entity ID, hardcore
            int dimensions = in.count(SableFlagTimeline.MAX_WORLD_RESETS);
            for (int i = 0; i < dimensions; i++) in.resourceName();
            in.count(Integer.MAX_VALUE); in.count(Integer.MAX_VALUE); in.count(Integer.MAX_VALUE);
            in.bool(); in.bool(); in.bool(); // reduced debug, death screen, limited crafting
        }
        in.count(Integer.MAX_VALUE); // dimension type registry ID (not the dimension resource key)
        String dimension = in.resourceName();
        in.skip(8 + 2); // seed, current/previous game mode
        in.bool(); in.bool(); // debug, flat
        if (in.bool()) { in.resourceName(); in.skip(8); } // optional last-death GlobalPos
        in.count(Integer.MAX_VALUE); // portal cooldown
        if (join) in.bool(); // enforce secure chat
        else if ((in.u8() & ~3) != 0) throw invalid(); // keep attributes/data bits
        if (in.remaining() != 0) throw new IOException("Unknown recorded dimension packet extension");
        return dimension;
    }

    static void consume(Packet packet, Set<String> players) throws IOException {
        boolean remove = packet.getType() == PacketType.PlayerListEntryRemove;
        if ((!remove && packet.getType() != PacketType.PlayerListEntry)
                || packet.getProtocolVersion().getVersion() != 767) throw invalid();
        if (packet.getBuf().readableBytes() > RecordedSableIndex.MAX_PACKET_BYTES
                || players.size() > SableFlagTimeline.MAX_ENTITIES) throw invalid();
        Cursor in = new Cursor(packet.getBuf());
        // 1.21.1 has exactly six actions, encoded as a one-byte fixed bitset.
        int actions = remove ? 0 : in.u8();
        if ((actions & ~63) != 0) throw invalid();
        int count = in.count(SableFlagTimeline.MAX_ENTITIES);
        if (count > in.remaining() / 16) throw new EOFException("Truncated recorded player identities");
        // Atomic publication: a later truncated/unknown action must not partially alter identities.
        Set<String> next = new HashSet<>(players);
        for (int i = 0; i < count; i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Sable index cancelled");
            String uuid = in.uuid();
            if (remove) { next.remove(uuid); continue; }
            if ((actions & 1) != 0) { // ADD_PLAYER
                in.utf(16 * 3);
                int properties = in.count(MAX_PROPERTIES);
                for (int p = 0; p < properties; p++) {
                    in.utf(32767 * 3); in.utf(32767 * 3);
                    if (in.bool()) in.utf(32767 * 3);
                }
                if (!next.contains(uuid) && next.size() >= SableFlagTimeline.MAX_ENTITIES) throw invalid();
                next.add(uuid);
            }
            if ((actions & 2) != 0 && in.bool()) { // INITIALIZE_CHAT: session UUID + key data
                in.skip(16 + 8); // session UUID, expiry instant (epoch milliseconds)
                in.skip(in.count(512)); // FriendlyByteBuf.readPublicKey maximum
                in.skip(in.count(4096)); // ProfilePublicKey.Data signature maximum
            }
            if ((actions & 4) != 0) in.varint(); // UPDATE_GAME_MODE
            if ((actions & 8) != 0) in.bool(); // UPDATE_LISTED
            if ((actions & 16) != 0) in.varint(); // UPDATE_LATENCY (signed int)
            if ((actions & 32) != 0 && in.bool()) in.nbt(in.u8(), 0); // nameless network NBT
        }
        if (in.remaining() != 0) throw new IOException("Unknown recorded player-list extension");
        players.clear(); players.addAll(next);
    }

    private static IOException invalid() { return new IOException("Invalid or oversized recorded player-list field"); }

    private static final class Cursor {
        private final ByteBuf buf;
        private int position, nodes;
        Cursor(ByteBuf buf) { this.buf = buf; position = buf.readerIndex(); }
        int remaining() { return buf.writerIndex() - position; }
        void skip(long size) throws IOException {
            if (size < 0 || size > remaining()) throw new EOFException("Truncated recorded player-list field");
            position += (int) size;
        }
        int u8() throws IOException { skip(1); return buf.getUnsignedByte(position - 1); }
        int u16() throws IOException { skip(2); return buf.getUnsignedShort(position - 2); }
        int i32() throws IOException { skip(4); return buf.getInt(position - 4); }
        boolean bool() throws IOException { int value = u8(); if (value > 1) throw invalid(); return value != 0; }
        String uuid() throws IOException {
            skip(16); return new UUID(buf.getLong(position - 16), buf.getLong(position - 8)).toString();
        }
        int varint() throws IOException {
            int result = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int value = u8();
                if (shift == 28 && (value & 240) != 0) throw invalid();
                result |= (value & 127) << shift;
                if ((value & 128) == 0) return result;
            }
            throw invalid();
        }
        int count(int maximum) throws IOException {
            int value = varint(); if (value < 0 || value > maximum) throw invalid(); return value;
        }
        void utf(int maximumBytes) throws IOException { skip(count(maximumBytes)); }
        String resourceName() throws IOException {
            int size = count(32767);
            if (size == 0) throw invalid();
            skip(size);
            int begin = position - size, colon = -1;
            for (int i = begin; i < position; i++) {
                int c = buf.getUnsignedByte(i);
                if (c == ':') { if (colon >= 0 || i == begin || i == position - 1) throw invalid(); colon = i; }
                else if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.' || c == '/')) throw invalid();
            }
            if (colon >= 0) for (int i = begin; i < colon; i++) if (buf.getUnsignedByte(i) == '/') throw invalid();
            return buf.toString(begin, size, java.nio.charset.StandardCharsets.US_ASCII);
        }
        void nbt(int type, int depth) throws IOException {
            if (depth > MAX_NBT_DEPTH || ++nodes > MAX_NBT_NODES) throw invalid();
            switch (type) {
                case 0 -> { }
                case 1 -> skip(1);
                case 2 -> skip(2);
                case 3, 5 -> skip(4);
                case 4, 6 -> skip(8);
                case 7 -> skip(i32());
                case 8 -> skip(u16());
                case 9 -> {
                    int element = u8(), count = i32();
                    if (count < 0 || count > MAX_NBT_NODES - nodes || element > 12 || element == 0 && count != 0) throw invalid();
                    for (int i = 0; i < count; i++) nbt(element, depth + 1);
                }
                case 10 -> {
                    int child;
                    while ((child = u8()) != 0) { skip(u16()); nbt(child, depth + 1); }
                }
                case 11 -> skip(4L * i32());
                case 12 -> skip(8L * i32());
                default -> throw invalid();
            }
        }
    }
}
