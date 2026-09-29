import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.cabinet.*;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Registered outer-codec checks against the delivered classes, without a Minecraft world or socket. */
public final class DeviceDebugWire47Probe {
    static int assertions,roundtrips,maxBytes;
    static void check(boolean pass,String message){assertions++;if(!pass)throw new AssertionError(message);}
    static void denied(Runnable test){boolean rejected=false;try{test.run();}catch(RuntimeException e){rejected=true;}check(rejected,"invalid request or truncated wire must fail");}
    static byte[] encode(CustomPacketPayload value,boolean upstream){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{if(upstream)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(value));
            else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(value));
            byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);maxBytes=Math.max(maxBytes,bytes.length);return bytes;
        }finally{b.release();}
    }
    static CustomPacketPayload decode(byte[] bytes,boolean upstream){
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);
        try{var result=upstream?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();
            check(!b.isReadable(),"all appended revision/provenance fields consumed");return result;
        }finally{b.release();}
    }
    static void roundtrip(CustomPacketPayload value,boolean upstream){
        byte[] bytes=encode(value,upstream);check(value.equals(decode(bytes,upstream)),"all fields roundtrip "+value.type().id());roundtrips++;
        for(int length=0;length<bytes.length;length++){byte[] partial=Arrays.copyOf(bytes,length);denied(()->decode(partial,upstream));}
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;var jar=Path.of(args[0]).toRealPath();
        for(var type:List.of(HomeSyncNetwork.class,HomeSyncNetwork.Request.class,HomeSyncNetwork.Setting.class,DeviceDebugPolicy.class,
                CabinetNetwork.class,CabinetNetwork.Menu.class,CabinetSyncNetwork.class,CabinetSyncNetwork.Mode.class,CabinetSyncNetwork.Setting.class))
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final JAR production origin "+type);
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var event=new RegisterPayloadHandlersEvent();HomeSyncNetwork.register(event);CabinetNetwork.register(event);
        for(var type:List.of(HomeSyncNetwork.Request.TYPE,CabinetSyncNetwork.Mode.TYPE)){
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"registered serverbound settings request");
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"client cannot receive authority request");
        }
        for(var type:List.of(HomeSyncNetwork.Setting.TYPE,CabinetSyncNetwork.Setting.TYPE,CabinetNetwork.Menu.TYPE)){
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"registered clientbound setting");
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot mint menu grants");
        }
        UUID token=UUID.randomUUID(),hardware=UUID.randomUUID();var dimension=ResourceLocation.parse("minecraft:overworld");var pos=new BlockPos(-12,64,33);
        var target=new CabinetTarget(dimension,pos,hardware,true);var backend=CabinetBackends.NES;
        for(int revision:new int[]{0,1,170,Integer.MAX_VALUE}){
            roundtrip(new HomeSyncNetwork.Request(token,revision,-1,-1,-1),true);
            for(int mode=0;mode<=2;mode++)roundtrip(new HomeSyncNetwork.Request(token,revision,mode,-1,-1),true);
            for(int value=0;value<=1;value++){
                roundtrip(new HomeSyncNetwork.Request(token,revision,-1,value,-1),true);
                roundtrip(new HomeSyncNetwork.Request(token,revision,-1,-1,value),true);
            }
        }
        denied(()->new HomeSyncNetwork.Request(token,-1,-1,-1,-1));
        denied(()->new HomeSyncNetwork.Request(token,0,3,-1,-1));
        denied(()->new HomeSyncNetwork.Request(token,0,-1,2,-1));
        denied(()->new HomeSyncNetwork.Request(token,0,-1,-1,2));
        denied(()->new HomeSyncNetwork.Request(token,0,1,1,-1));
        denied(()->new HomeSyncNetwork.Request(token,0,-1,0,1));
        for(int bits=0;bits<64;bits++)roundtrip(new HomeSyncNetwork.Setting(token,42,dimension,pos,hardware,"SFC",1,6,(bits&1)!=0,"确认需管理员，设置不更改游戏进度",(bits&2)!=0,
                (bits&4)!=0,(bits&8)!=0,(bits&16)!=0,(bits&32)!=0),false);
        for(boolean debug:new boolean[]{false,true}){
            roundtrip(new CabinetNetwork.Menu(target,token,CabinetBackends.entries(),backend,debug),false);
            UUID source=debug?token:null;
            for(int mode=-1;mode<=2;mode++)roundtrip(new CabinetSyncNetwork.Mode(target,backend,mode,source),true);
            for(int mode=0;mode<=2;mode++)roundtrip(new CabinetSyncNetwork.Setting(target,backend,mode,true,true,"点中坐标；两台共用主机设置",true,true,"服务器运行",source),false);
        }
        check(!new CabinetNetwork.Menu(target,token,CabinetBackends.entries(),backend).debugTool(),"ordinary legacy menu default");
        check(new CabinetSyncNetwork.Mode(target,backend,-1).debugToken()==null,"ordinary legacy query has no tool provenance");
        denied(()->new CabinetNetwork.Menu(target,token,List.of(),backend,true));
        check(DeviceDebugPolicy.matchesToken(token,token),"current grant accepted");
        check(!DeviceDebugPolicy.matchesToken(token,null),"expired grant rejected");
        check(!DeviceDebugPolicy.matchesToken(token,UUID.randomUUID()),"replacement grant rejects delayed old tool request");
        output.println("{\"ok\":true,\"assertions\":"+assertions+",\"roundtrips\":"+roundtrips+",\"max_outer_bytes\":"+maxBytes+",\"registered_outer_codecs\":true,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"world_authority_executed\":false,\"socket_opened\":false}");
    }
}
