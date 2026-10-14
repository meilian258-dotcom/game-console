package cn.piq.replayquick;

import com.replaymod.replaystudio.PacketData;
import com.replaymod.replaystudio.protocol.*;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.*;
import java.util.*;

/** Exact protocol-767 side-index reader. No archive/cache mutation or Minecraft class access. */
final class RecordedSableIndex {
    static final int MAX_PACKET_BYTES=16*1024*1024, MAX_PACKETS=5_000_000;
    static final long MAX_STREAM_BYTES=2L*1024*1024*1024;
    private RecordedSableIndex(){}
    static SableFlagTimeline read(ReplayFile file,PacketTypeRegistry registry)throws IOException {
        validateMetadata(file.getMetaData(),registry);
        var raw=file.get("recording.tmcpr");
        if(!raw.isPresent())throw new IOException("Missing original packet stream");
        // ReplayInputStream allocates the declared packet length before returning PacketData.
        // Validate all lengths first with constant-memory skipping, before invoking that parser.
        try(var input=raw.get()){validateFraming(input);}
        var builder=new SableFlagTimeline.Builder();var state=new ScanState(new HashSet<>()); int packets=0, previous=-1;
        try(var stream=file.getPacketData(registry)) {
            PacketData data;
            while((data=stream.readPacket())!=null) {
                Packet packet=data.getPacket();
                try {
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sable index cancelled");
                    if(++packets>MAX_PACKETS)throw new IOException("Sable index packet limit");
                    long timestamp=data.getTime();
                    if(timestamp<previous || timestamp<0 || timestamp>SableFlagTimeline.MAX_TIME)throw new IOException("Invalid original packet time");
                    previous=(int)timestamp; consume(builder,previous,packet,state);
                } finally {packet.release();}
            }
        }
        return builder.build();
    }
    static void validateMetadata(com.replaymod.replaystudio.replay.ReplayMetaData metadata,PacketTypeRegistry registry)throws IOException {
        if(metadata==null || registry.getVersion().getVersion()!=767
                || !Integer.valueOf(767).equals(metadata.getRawProtocolVersion())
                || metadata.getProtocolVersion().getVersion()!=767
                || !"1.21.1".equals(metadata.getMcVersion()))
            throw new IOException("Sable quick index requires consistent, explicit Minecraft 1.21.1 / protocol 767 metadata");
    }
    static final class ScanState {
        final Set<String> players;
        String dimension;
        ScanState(Set<String> players){this.players=players;}
    }
    static void validateFraming(InputStream input)throws IOException {
        var in=new DataInputStream(new BufferedInputStream(input));long bytes=0;int packets=0,previous=-1;
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sable index cancelled");
            int first=in.read();if(first<0)return;
            int time=(first<<24)|(in.readUnsignedByte()<<16)|(in.readUnsignedByte()<<8)|in.readUnsignedByte();
            int size=in.readInt();
            if(time<previous || time<0 || time>SableFlagTimeline.MAX_TIME || size<0 || size>MAX_PACKET_BYTES)
                throw new IOException("Invalid or oversized recorded packet frame");
            if(++packets>MAX_PACKETS || (bytes+=8L+size)>MAX_STREAM_BYTES)throw new IOException("Recorded packet stream exceeds index limit");
            previous=time;in.skipNBytes(size);
        }
    }
    static void consume(SableFlagTimeline.Builder builder,int time,Packet packet)throws IOException {
        consume(builder,time,packet,new HashSet<>());
    }
    static void consume(SableFlagTimeline.Builder builder,int time,Packet packet,Set<String> players)throws IOException {
        consume(builder,time,packet,new ScanState(players));
    }
    static void consume(SableFlagTimeline.Builder builder,int time,Packet packet,ScanState state)throws IOException {
        PacketType type=packet.getType();
        Set<String> players=state.players;
        var buf=packet.getBuf();
        if(buf.readableBytes()>MAX_PACKET_BYTES)throw new IOException("Oversized packet from recording reader");
        if(type==PacketType.PlayerListEntry || type==PacketType.PlayerListEntryRemove){
            RecordedPlayerIdentities.consume(packet,players);
            return;
        }
        if(type==PacketType.JoinGame || type==PacketType.Respawn){
            String dimension=RecordedPlayerIdentities.dimension(packet);
            if(type==PacketType.Respawn && state.dimension==null)throw new IOException("Respawn before a proven recorded dimension");
            if(type==PacketType.JoinGame || !dimension.equals(state.dimension))builder.worldReset(time);
            state.dimension=dimension;return;
        }
        if(type==PacketType.SpawnObject || type==PacketType.SpawnMob || type==PacketType.SpawnPlayer || type==PacketType.SpawnPainting) {
            var cursor=new Cursor(packet);int id=cursor.varint();
            if(cursor.remaining()<16)throw new EOFException("Truncated spawn identity");
            // The set is temporary build state only. The published timeline contains no UUIDs.
            String uuid=new UUID(buf.getLong(cursor.position),buf.getLong(cursor.position+8)).toString();
            boolean player=(type==PacketType.SpawnObject||type==PacketType.SpawnPlayer)&&players.contains(uuid);
            builder.spawn(time,id,player);return;
        }
        if(type==PacketType.DestroyEntities || type==PacketType.DestroyEntity) {
            var cursor=new Cursor(packet);int count=type==PacketType.DestroyEntity?1:cursor.varint();
            if(count<0 || count>SableFlagTimeline.MAX_ENTITIES)throw new IOException("Invalid recorded destroy count");
            for(int i=0;i<count;i++)builder.despawn(time,cursor.varint());
            if(cursor.remaining()!=0)throw new IOException("Unknown recorded destroy extension");return;
        }
        int body=type==PacketType.EntityTeleport?27:type==PacketType.EntityPosition?7:type==PacketType.EntityPositionRotation?9:-1;
        if(body<0)return; // Rotation-only packets do not carry Sable's state: retain the last proven state.
        var cursor=new Cursor(packet);int id=cursor.varint();
        int remaining=cursor.remaining();
        if(remaining!=body && remaining!=body+1)throw new IOException("Unknown recorded Sable movement layout");
        var flag=SableFlagTimeline.Flag.UNKNOWN;
        if(remaining==body+1){int value=buf.getUnsignedByte(cursor.position+body);if(value>1)throw new IOException("Invalid recorded Sable boolean");flag=value==0?SableFlagTimeline.Flag.FALSE:SableFlagTimeline.Flag.TRUE;}
        builder.observe(time,id,flag);
    }
    private static final class Cursor {
        final com.github.steveice10.netty.buffer.ByteBuf buf;int position;
        Cursor(Packet packet){buf=packet.getBuf();position=buf.readerIndex();}
        int remaining(){return buf.writerIndex()-position;}
        int varint()throws IOException {
            int result=0;
            for(int i=0;i<5;i++){
                if(remaining()==0)throw new EOFException("Truncated recorded entity ID");
                int next=buf.getUnsignedByte(position++);
                if(i==4 && (next&240)!=0)throw new IOException("Oversized recorded varint");
                result|=(next&127)<<(7*i);if((next&128)==0){if(result<0)throw new IOException("Negative recorded ID/count");return result;}
            }
            throw new IOException("Oversized recorded varint");
        }
    }
}
