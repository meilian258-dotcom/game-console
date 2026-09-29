// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.netplay.RollbackTimeline.Input;
import java.io.*;
import java.util.*;

/** A distinct, versioned JNI rollback protocol inside the existing authorized relay. */
public final class JniNetplayCodec {
    public static final int MAGIC = 0x504a4e31, VERSION = 1, SEED_LIMIT = 4 * 1024 * 1024;
    public static final int PART = 16000;
    private JniNetplayCodec() {}
    public sealed interface Message permits Hello, Seed, Part, End, Ready, Commands, Pad, Digest {}
    public record Hello(NetplaySaveState.Identity identity) implements Message { public Hello { Objects.requireNonNull(identity); } }
    public record Seed(int bytes, long frame, int port) implements Message {
        public Seed { if (bytes < 5 || bytes > SEED_LIMIT || frame < 0 || frame > Long.MAX_VALUE-1024 || port < -1 || port > 1) throw bad(); }
    }
    public record Part(int offset, byte[] data) implements Message {
        public Part {
            if (offset < 0 || offset > SEED_LIMIT || data == null || data.length < 1 || data.length > PART || (long)offset+data.length > SEED_LIMIT) throw bad();
            data = data.clone();
        }
        @Override public byte[] data() { return data.clone(); }
    }
    public record End() implements Message {}
    public record Ready(long frame) implements Message { public Ready { JniNetplayCodec.frame(frame); } }
    /** confirmed is the first frame still subject to correction; next is the first unexecuted frame. */
    public record Commands(long confirmed, long next, List<Input> inputs) implements Message {
        public Commands {
            frame(confirmed); frame(next); inputs = List.copyOf(inputs);
            if (confirmed > next || next-confirmed > 12 || inputs.isEmpty() || inputs.size() > 32
                    || inputs.getLast().frame() != next-1) throw bad();
            long n=inputs.getFirst().frame();
            for (Input input : inputs) if (input.frame()!=n++ || input.frame()<confirmed && input.known()!=3) throw bad();
        }
    }
    public record Pad(long frame, int mask) implements Message {
        public Pad { JniNetplayCodec.frame(frame); if ((mask & ~65535) != 0) throw bad(); }
    }
    public record Digest(long frame, String sha) implements Message {
        public Digest { JniNetplayCodec.frame(frame); if (sha == null || !sha.matches("[a-f0-9]{64}")) throw bad(); }
    }
    public static byte[] encode(Message message) {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(MAGIC);out.writeByte(VERSION);
            switch(message) {
                case Hello m -> {out.writeByte(1);hash(out,m.identity().profile());hash(out,m.identity().content());}
                case Seed m -> {out.writeByte(2);out.writeInt(m.bytes());out.writeLong(m.frame());out.writeByte(m.port());}
                case Part m -> {out.writeByte(3);out.writeInt(m.offset());out.writeShort(m.data.length);out.write(m.data);}
                case End m -> out.writeByte(4);
                case Ready m -> {out.writeByte(5);out.writeLong(m.frame());}
                case Commands m -> {
                    out.writeByte(6);out.writeLong(m.confirmed());out.writeLong(m.next());out.writeByte(m.inputs().size());
                    for(var i:m.inputs()){out.writeLong(i.frame());out.writeShort(i.p1());out.writeShort(i.p2());out.writeByte(i.known());}
                }
                case Pad m -> {out.writeByte(7);out.writeLong(m.frame());out.writeShort(m.mask());}
                case Digest m -> {out.writeByte(8);out.writeLong(m.frame());hash(out,m.sha());}
            }
            byte[] result=bytes.toByteArray();if(result.length>NetplayChunk.LIMIT)throw bad();return result;
        } catch(IOException impossible) {throw new UncheckedIOException(impossible);}
    }
    public static Message decode(byte[] bytes) {
        if(bytes==null||bytes.length<6||bytes.length>NetplayChunk.LIMIT)throw bad();
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=MAGIC||in.readUnsignedByte()!=VERSION)throw new IllegalArgumentException("JNI Netplay 协议不匹配；不能混接 RetroArch 房间");
            Message result=switch(in.readUnsignedByte()) {
                case 1 -> new Hello(new NetplaySaveState.Identity(hash(in),hash(in)));
                case 2 -> new Seed(in.readInt(),in.readLong(),in.readByte());
                case 3 -> {
                    int offset=in.readInt(),size=in.readUnsignedShort();if(size<1||size>PART||size!=in.available())throw bad();
                    yield new Part(offset,in.readNBytes(size));
                }
                case 4 -> new End();
                case 5 -> new Ready(in.readLong());
                case 6 -> {
                    long confirmed=in.readLong(),next=in.readLong();int count=in.readUnsignedByte();
                    if(count<1||count>32||in.available()!=13*count)throw bad();
                    var inputs=new ArrayList<Input>(count);
                    for(int n=0;n<count;n++)inputs.add(new Input(in.readLong(),in.readUnsignedShort(),in.readUnsignedShort(),in.readUnsignedByte()));
                    yield new Commands(confirmed,next,inputs);
                }
                case 7 -> new Pad(in.readLong(),in.readUnsignedShort());
                case 8 -> new Digest(in.readLong(),hash(in));
                default -> throw bad();
            };
            if(in.available()!=0)throw bad();return result;
        } catch(IOException invalid) {throw new IllegalArgumentException("JNI Netplay 数据截断",invalid);}
    }
    private static void hash(DataOutputStream out,String hash)throws IOException {out.write(HexFormat.of().parseHex(hash));}
    private static String hash(DataInputStream in)throws IOException {byte[] bytes=in.readNBytes(32);if(bytes.length!=32)throw new EOFException();return HexFormat.of().formatHex(bytes);}
    private static void frame(long frame) {if(frame<0||frame>Long.MAX_VALUE-1024)throw bad();}
    private static IllegalArgumentException bad() {return new IllegalArgumentException("JNI Netplay 消息边界异常");}
}
