import cn.piq.fcarcade.*;
import cn.piq.fcarcade.home.HomeSaveIntent;
import cn.piq.fcarcade.session.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
public final class HomeRuntimeWire29Probe {
    static int checks;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static void rejects(Runnable r){boolean refused=false;try{r.run();}catch(RuntimeException e){refused=true;}check(refused,"invalid/truncated outer packet refused");}
    public static void main(String[] args)throws Exception{
        var stdout=System.out;var jar=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(FcNetwork.class,ArcadeHomeInputPayload.class,ArcadeHomeSaveSlotsPayload.class,ArcadeHomeSaveActionPayload.class,HomeSaveIntent.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"actual final class "+c);
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();FcNetwork.register(new RegisterPayloadHandlersEvent());
        var v=FcNetwork.class.getDeclaredField("PROTOCOL_VERSION");v.setAccessible(true);check(v.get(null).equals("34"),"new token requires paired protocol34");
        for(var t:List.of(ArcadeHomeInputPayload.TYPE,ArcadeHomeReadyPayload.TYPE,ArcadeHomeSaveActionPayload.TYPE)){check(NetworkRegistry.getCodec(t.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"serverbound registry");check(NetworkRegistry.getCodec(t.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"cannot mint server authority");}
        check(NetworkRegistry.getCodec(ArcadeHomeSaveSlotsPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"server UI offer");check(NetworkRegistry.getCodec(ArcadeHomeSaveSlotsPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot offer own slots");
        UUID p1=UUID.randomUUID(),gun=UUID.randomUUID(),token=UUID.randomUUID(),player=UUID.randomUUID();String rom="00".repeat(32);
        var packets=new ArrayList<CustomPacketPayload>();packets.add(new ArcadeHomeInputPayload(p1,new ArcadeInputPayload(80,2,99,255)));packets.add(new ArcadeHomeInputPayload(p1,new ArcadeInputPayload(80,2,100,0,true)));packets.add(new ArcadeHomeReadyPayload(80,2));
        packets.add(new ArcadeJoinApprovalPayload(80,player,"P1 + 光枪",token));packets.add(new ArcadeJoinDecisionPayload(80,player,true,token));packets.add(new ArcadeJoinDecisionPayload(80,player,false,token));
        for(ArcadeRole role:List.of(ArcadeRole.PLAYER_ONE,ArcadeRole.PLAYER_TWO,ArcadeRole.SPECTATOR))packets.add(new ArcadeSessionPayload(BlockPos.ZERO,80,ArcadeMode.LOCKSTEP,role,1,"Host",16,16,75,rom,2,false,true,NesCoreVariant.ZAPPER_V1,true,role==ArcadeRole.SPECTATOR,role==ArcadeRole.SPECTATOR?null:role==ArcadeRole.PLAYER_ONE?p1:gun,3));
        var slots=new ArcadeSaveSlotsPayload(BlockPos.ZERO,rom,"诊断",List.of(new ArcadeSaveSlotEntry(1,false,"新槽",1,0,"",""),new ArcadeSaveSlotEntry(2,true,"光枪专用",2,123,rom,"诊断"),new ArcadeSaveSlotEntry(3,false,"空",1,0,"","")));
        packets.add(new ArcadeHomeSaveSlotsPayload(token,true,slots));packets.add(new ArcadeHomeSaveSlotsPayload(token,false,slots));packets.add(new ArcadeHomeSaveActionPayload(token,null));
        for(int action=0;action<=2;action++)packets.add(new ArcadeHomeSaveActionPayload(token,new ArcadeSaveSlotActionPayload(BlockPos.ZERO,rom,2,action,"新的名称",2,true)));
        for(var p:packets){boolean up=p instanceof ArcadeHomeInputPayload||p instanceof ArcadeHomeReadyPayload||p instanceof ArcadeJoinDecisionPayload||p instanceof ArcadeHomeSaveActionPayload;byte[] wire=ZapperWire26Probe.encode(p,up);check(wire.length<32767,"whole outer packet bounded");check(p.equals(ZapperWire26Probe.decode(wire,up)),"all fields outer roundtrip");for(int n=0;n<wire.length;n++){var shortWire=Arrays.copyOf(wire,n);rejects(()->ZapperWire26Probe.decode(shortWire,up));}}
        Object connection=new Object();var request=new HomeSaveIntent<>(token,connection,100);var selection=new ArcadeHomeSaveActionPayload(token,new ArcadeSaveSlotActionPayload(BlockPos.ZERO,rom,2,0,"名称",1,true));
        var decoded=(ArcadeHomeSaveActionPayload)ZapperWire26Probe.decode(ZapperWire26Probe.encode(selection,true),true);check(!request.consume(decoded.token(),new Object(),1),"same payload from new connection rejected");check(request.consume(decoded.token(),connection,1),"actual wire token consumes");check(!request.consume(decoded.token(),connection,2),"duplicate real packet rejected");var next=new HomeSaveIntent<>(UUID.randomUUID(),connection,100);check(!next.consume(decoded.token(),connection,2),"consumed old packet cannot choose newly opened same-device slot");
        rejects(()->new ArcadeHomeSaveActionPayload(null,null));rejects(()->new ArcadeHomeSaveSlotsPayload(token,true,null));rejects(()->new ArcadeHomeReadyPayload(80,0));
        stdout.println("{\"ok\":true,\"assertions\":"+checks+",\"payloads\":"+packets.size()+",\"protocol\":34,\"max_outer_bytes\":"+ZapperWire26Probe.maxBytes+",\"actual_registered_outer_codecs\":true,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"server_world_authority_executed\":false}");
    }
}
