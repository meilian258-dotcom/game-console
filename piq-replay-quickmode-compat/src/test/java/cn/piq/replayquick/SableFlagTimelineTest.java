package cn.piq.replayquick;

import com.replaymod.replaystudio.protocol.*;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State;
import com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion;
import java.io.*;
import java.util.*;
import static cn.piq.replayquick.SableFlagTimeline.Flag.*;

public final class SableFlagTimelineTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    interface Checked {void run()throws Exception;}
    static void reject(Checked body)throws Exception{try{body.run();throw new AssertionError("Expected IOException");}catch(IOException expected){checks++;}}
    public static void main(String[] args){try{run();System.exit(0);}catch(Throwable failure){failure.printStackTrace();System.exit(1);}}
    static void run()throws Exception {
        var b=new SableFlagTimeline.Builder();b.worldReset(0);b.spawn(1,10);b.observe(2,10,FALSE);b.observe(3,10,FALSE);
        b.observe(5,10,TRUE);b.observe(5,10,FALSE);b.observe(7,10,UNKNOWN);b.observe(8,10,TRUE);b.despawn(9,10);b.spawn(10,10);b.observe(11,10,FALSE);
        b.worldReset(12);b.observe(12,10,TRUE);b.spawn(12,10);b.observe(12,10,TRUE);
        var index=b.build();check(index.flagAt(10,1)==UNKNOWN);check(index.flagAt(10,2)==FALSE);check(index.flagAt(10,5)==FALSE);
        check(index.flagAt(10,7)==UNKNOWN);check(index.flagAt(10,8)==TRUE);check(index.flagAt(10,9)==UNKNOWN);check(index.flagAt(10,10)==UNKNOWN);
        check(index.flagAt(10,11)==FALSE);check(index.flagAt(10,12)==TRUE);check(index.flagAt(10,3)==FALSE);check(index.flagAt(11,30)==UNKNOWN);
        b.worldReset(13);check(b.build().flagAt(10,13)==UNKNOWN);check(index.flagAt(10,13)==TRUE); // Built snapshot immutable.
        b.spawn(14,11);b.observe(14,11,FALSE);b.worldReset(14);check(b.build().flagAt(11,14)==UNKNOWN);
        b.spawn(14,11);b.observe(14,11,FALSE);check(b.build().flagAt(11,14)==FALSE);
        b.spawn(15,12,true);check(b.build().isPlayerAt(12,15));check(b.build().flagAt(12,15)==UNKNOWN);
        b.observe(16,12,FALSE);check(b.build().isPlayerAt(12,16));b.despawn(17,12);check(!b.build().isPlayerAt(12,17));
        b.spawn(18,12,false);check(!b.build().isPlayerAt(12,18));check(b.build().isPlayerAt(12,16));
        reject(()->b.observe(13,11,FALSE));reject(()->new SableFlagTimeline.Builder().spawn(-1,2));
        var limited=new SableFlagTimeline.Builder(1,2,1);limited.spawn(0,1);limited.observe(1,1,FALSE);reject(()->limited.observe(2,1,TRUE));
        reject(()->limited.spawn(2,2));limited.worldReset(3);reject(()->limited.worldReset(4));
        for(byte[] malformed:List.of(new byte[]{0},new byte[7],new byte[]{0,0,0,0,1,0,0,1},new byte[]{0,0,0,0,-1,-1,-1,-1}))
            reject(()->RecordedSableIndex.validateFraming(new ByteArrayInputStream(malformed)));
        RecordedSableIndex.validateFraming(new ByteArrayInputStream(new byte[8]));checks++;
        Thread.currentThread().interrupt();try{reject(()->RecordedSableIndex.validateFraming(new ByteArrayInputStream(new byte[0])));}finally{Thread.interrupted();}
        var registry=PacketTypeRegistry.get(ProtocolVersion.getProtocol(767),State.PLAY);var parsed=new SableFlagTimeline.Builder();
        byte[] spawn=new byte[17];spawn[0]=7;
        apply(parsed,registry,PacketType.SpawnObject,0,spawn);check(parsed.build().flagAt(7,0)==UNKNOWN);
        byte[] pos=new byte[9];pos[0]=7;pos[8]=1;apply(parsed,registry,PacketType.EntityPosition,1,pos);check(parsed.build().flagAt(7,1)==TRUE);
        apply(parsed,registry,PacketType.EntityRotation,2,new byte[]{7});check(parsed.build().flagAt(7,2)==TRUE);
        apply(parsed,registry,PacketType.EntityPosition,3,Arrays.copyOf(pos,8));check(parsed.build().flagAt(7,3)==UNKNOWN);
        pos[8]=0;apply(parsed,registry,PacketType.EntityPosition,4,pos);check(parsed.build().flagAt(7,4)==FALSE);
        apply(parsed,registry,PacketType.DestroyEntities,5,new byte[]{1,7});check(parsed.build().flagAt(7,5)==UNKNOWN);
        apply(parsed,registry,PacketType.EntityPosition,6,pos);check(parsed.build().flagAt(7,6)==UNKNOWN);
        apply(parsed,registry,PacketType.SpawnObject,7,spawn);check(parsed.build().flagAt(7,7)==UNKNOWN);
        byte[] teleport=new byte[29];teleport[0]=7;teleport[28]=1;apply(parsed,registry,PacketType.EntityTeleport,8,teleport);check(parsed.build().flagAt(7,8)==TRUE);
        reject(()->apply(parsed,registry,PacketType.Respawn,9,new byte[0]));
        var joinPacket=RecordedDimensionTest.join(RecordedDimensionTest.OVERWORLD,false);
        try{RecordedSableIndex.consume(parsed,9,joinPacket);}finally{joinPacket.release();}
        check(parsed.build().flagAt(7,9)==UNKNOWN);
        reject(()->apply(parsed,registry,PacketType.EntityPosition,10,new byte[]{7}));
        pos[8]=2;reject(()->apply(parsed,registry,PacketType.EntityPosition,10,pos));
        reject(()->apply(parsed,registry,PacketType.DestroyEntities,10,new byte[]{1}));
        var identities=new HashSet<String>();identities.add(new UUID(0,0).toString());var classified=new SableFlagTimeline.Builder();
        var spawnPacket=new Packet(registry,PacketType.SpawnObject,com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(spawn));
        try{RecordedSableIndex.consume(classified,0,spawnPacket,identities);}finally{spawnPacket.release();}
        check(classified.build().isPlayerAt(7,0));check(classified.build().flagAt(7,0)==UNKNOWN);
        byte[] removed=new byte[17];removed[0]=1;
        var removePacket=new Packet(registry,PacketType.PlayerListEntryRemove,com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(removed));
        try{RecordedSableIndex.consume(classified,1,removePacket,identities);}finally{removePacket.release();}
        check(identities.isEmpty());check(classified.build().isPlayerAt(7,1)); // Existing entity retains spawn proof.
        var respawnPacket=new Packet(registry,PacketType.SpawnObject,com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(spawn));
        try{RecordedSableIndex.consume(classified,2,respawnPacket,identities);}finally{respawnPacket.release();}
        check(!classified.build().isPlayerAt(7,2));
        System.out.println("SableFlagTimelineTest checks="+checks+" PASS");
    }
    static void apply(SableFlagTimeline.Builder builder,PacketTypeRegistry registry,PacketType type,int time,byte[] bytes)throws Exception {
        var packet=new Packet(registry,type,com.github.steveice10.netty.buffer.Unpooled.wrappedBuffer(bytes));int reader=packet.getBuf().readerIndex();
        try{RecordedSableIndex.consume(builder,time,packet);check(packet.getBuf().readerIndex()==reader);}finally{check(packet.release()||bytes.length==0);}
    }
}
