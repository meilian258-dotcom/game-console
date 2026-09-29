package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;

/** Actual registered outer payload codecs, not a parallel serializer or live multiplayer claim. */
public final class Sync32WireProbe {
    private static int checks,wires,maxBytes;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void denied(Runnable task){boolean rejected=false;try{task.run();}catch(RuntimeException expected){rejected=true;}check(rejected,"invalid payload rejected");}
    private static byte[] encode(CustomPacketPayload p,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
        if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(p));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(p));
        byte[] raw=new byte[b.readableBytes()];b.readBytes(raw);maxBytes=Math.max(maxBytes,raw.length);return raw;
    }finally{b.release();}}
    private static CustomPacketPayload decode(byte[] raw,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(raw),RegistryAccess.EMPTY);try{
        var p=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"all outer bytes consumed");return p;
    }finally{b.release();}}
    private static void wire(CustomPacketPayload p,boolean up){byte[] raw=encode(p,up);check(Arrays.equals(raw,encode(decode(raw,up),up)),"wire roundtrip");wires++;
        for(int n:new int[]{0,1,Math.min(30,raw.length-1),raw.length-1})denied(()->decode(Arrays.copyOf(raw,n),up));}
    public static void main(String[] args)throws Exception{
        var output=System.out;Path jar=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(CabinetGameNetwork.class,CabinetGameManifest.class,CabinetGameStore.class,CabinetSharedGameService.class,CabinetSyncNetwork.class,CabinetSyncCore.class,CabinetSyncState.class,CabinetSyncGate.class,CabinetMediaSender.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final jar owns "+type.getSimpleName());
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent());CabinetGameNetwork.register(new RegisterPayloadHandlersEvent());
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);var registrations=(Map<?,?>)((Map<?,?>)field.get(null)).get(ConnectionProtocol.PLAY);
        for(var pair:Map.of(CabinetGameNetwork.Command.TYPE.id(),"cabinet-game-1",CabinetGameNetwork.Reply.TYPE.id(),"cabinet-game-1",CabinetSyncNetwork.Mode.TYPE.id(),"cabinet-sync-1",CabinetSyncNetwork.Restore.TYPE.id(),"cabinet-sync-1",CabinetRoomNetwork.Assignment.TYPE.id(),"cabinet-room-4").entrySet()){
            var value=(PayloadRegistration<?>)registrations.get(pair.getKey());check(value!=null&&value.version().equals(pair.getValue())&&!value.optional(),"mandatory matching protocol");}
        check(NetworkRegistry.getCodec(CabinetGameNetwork.Reply.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot mint game replies");
        check(NetworkRegistry.getCodec(CabinetSyncNetwork.Setting.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot mint mode authority");
        UUID tx=UUID.randomUUID(),lease=UUID.randomUUID(),room=UUID.randomUUID();ResourceLocation id=ResourceLocation.parse("piq_sfc_home:sfc");String sha="a".repeat(64);
        var manifest=new CabinetGameManifest(id.toString(),List.of(new CabinetGameManifest.Entry("test.sfc",sha,32768)));
        wire(new CabinetGameNetwork.Command(tx,0,0,lease,id,manifest,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,1,1,lease,id,null,0,0,new byte[24576]),true);
        wire(new CabinetGameNetwork.Command(tx,2,2,lease,id,null,0,24576,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,3,3,lease,id,null,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Command(tx,4,4,lease,id,null,0,0,new byte[0]),true);
        wire(new CabinetGameNetwork.Reply(tx,0,true,manifest,0,0,new byte[0],"校验通过"),false);
        wire(new CabinetGameNetwork.Reply(tx,2,true,null,0,0,new byte[24576],""),false);
        wire(new CabinetGameNetwork.Reply(tx,3,false,null,0,0,new byte[0],"权限已改变"),false);
        var target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),UUID.randomUUID(),true);
        for(int mode=-1;mode<2;mode++)wire(new CabinetSyncNetwork.Mode(target,id,mode),true);
        wire(new CabinetSyncNetwork.Setting(target,id,1,true,false,"游戏运行中"),false);
        wire(new CabinetSyncNetwork.Hello(room,lease,1,sha,manifest.contentId(),"test:v1",60000,sha),true);
        wire(new CabinetSyncNetwork.Frames(room,lease,1,List.of(new CabinetSyncTimeline.Step(1,1,2,4,8))),false);
        var part=new CabinetSyncNetwork.StatePart(room,lease,1,tx,300,350,32768,0,sha,false,new byte[24576]);
        wire(new CabinetSyncNetwork.Upload(part),true);wire(new CabinetSyncNetwork.Restore(part),false);
        denied(()->new CabinetGameNetwork.Command(tx,1,1,lease,id,null,0,0,new byte[24577]));
        denied(()->new CabinetGameNetwork.Command(tx,1,2,lease,id,null,0,0,new byte[1]));
        denied(()->new CabinetGameNetwork.Command(tx,20001,2,lease,id,null,0,0,new byte[0]));
        denied(()->new CabinetGameManifest(id.toString(),List.of(new CabinetGameManifest.Entry("run.dll",sha,1))));
        denied(()->new CabinetGameManifest.Entry("../secret.sfc",sha,1));
        denied(()->new CabinetSyncNetwork.Mode(target,id,2));
        check(maxBytes<32768,"every tested packet fits conservative 32KiB window");
        output.println(new com.google.gson.Gson().toJson(Map.of("ok",true,"assertions",checks,"outer_roundtrips",wires,"max_outer_bytes",maxBytes,"production_origin","final-jar-only","live_network_tested",false)));
    }
}
