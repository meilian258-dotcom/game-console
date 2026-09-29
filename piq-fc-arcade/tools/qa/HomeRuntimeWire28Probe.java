import cn.piq.fcarcade.*;
import cn.piq.fcarcade.session.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class HomeRuntimeWire28Probe {
    static int checks;
    static void check(boolean yes,String why){checks++;if(!yes)throw new AssertionError(why);}
    static void rejected(Runnable action){boolean no=false;try{action.run();}catch(RuntimeException e){no=true;}check(no,"invalid/truncated payload must reject");}
    public static void main(String[] args)throws Exception{
        var stdout=System.out;Path jar=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(FcNetwork.class,ArcadeHomeInputPayload.class,ArcadeHomeReadyPayload.class,ArcadeSessionPayload.class,ArcadeJoinApprovalPayload.class,ArcadeJoinDecisionPayload.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final origin "+type);
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();FcNetwork.register(new RegisterPayloadHandlersEvent());
        var version=FcNetwork.class.getDeclaredField("PROTOCOL_VERSION");version.setAccessible(true);check(version.get(null).equals("33"),"protocol33 mandatory on both sides");
        for(var type:List.of(ArcadeHomeInputPayload.TYPE,ArcadeHomeReadyPayload.TYPE)){
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"input/ready registered to server");
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"client cannot grant authority using input/ready");
        }
        UUID lease=UUID.randomUUID(),applicant=UUID.randomUUID(),token=UUID.randomUUID();var packets=new ArrayList<CustomPacketPayload>();
        packets.add(new ArcadeHomeInputPayload(lease,new ArcadeInputPayload(8,2,19,255)));packets.add(new ArcadeHomeInputPayload(lease,new ArcadeInputPayload(8,2,20,0,true)));packets.add(new ArcadeHomeReadyPayload(8,2));
        packets.add(new ArcadeJoinApprovalPayload(8,applicant,"Guest · P2",token));packets.add(new ArcadeJoinDecisionPayload(8,applicant,true,token));packets.add(new ArcadeJoinDecisionPayload(8,applicant,false,token));
        packets.add(new ArcadeJoinApprovalPayload(9,applicant,"Legacy"));packets.add(new ArcadeJoinDecisionPayload(9,applicant,true));
        for(var variant:NesCoreVariant.values()){
            packets.add(new ArcadeSessionPayload(BlockPos.ZERO,8,ArcadeMode.LOCKSTEP,ArcadeRole.SPECTATOR,0,"",16,16,75,"00".repeat(32),2,true,true,variant,true,true,null,1));
            packets.add(new ArcadeSessionPayload(BlockPos.ZERO,8,ArcadeMode.LOCKSTEP,ArcadeRole.PLAYER_ONE,1,"P1",16,16,75,"00".repeat(32),2,false,true,variant,true,false,lease,2));
            packets.add(new ArcadeSessionPayload(BlockPos.ZERO,8,ArcadeMode.LOCKSTEP,ArcadeRole.SPECTATOR,0,"",16,16,75,"00".repeat(32),2,false,true,variant,true,false,null,3));
        }
        for(CustomPacketPayload p:packets){boolean upstream=p instanceof ArcadeHomeInputPayload||p instanceof ArcadeHomeReadyPayload||p instanceof ArcadeJoinDecisionPayload;byte[] raw=ZapperWire26Probe.encode(p,upstream);check(raw.length<32767,"complete outer packet bounded");check(p.equals(ZapperWire26Probe.decode(raw,upstream)),"all authority/epoch/lease/token fields roundtrip");for(int n=0;n<raw.length;n++){byte[] truncated=Arrays.copyOf(raw,n);rejected(()->ZapperWire26Probe.decode(truncated,upstream));}}
        rejected(()->new ArcadeHomeReadyPayload(8,0));rejected(()->new ArcadeHomeInputPayload(null,new ArcadeInputPayload(8,2,20,0,true)));
        rejected(()->new ArcadeSessionPayload(BlockPos.ZERO,8,ArcadeMode.LOCKSTEP,ArcadeRole.PLAYER_ONE,1,"P1",16,16,75,"00".repeat(32),2,false,true,NesCoreVariant.LEGACY,true,false,null,2));
        stdout.println("{\"ok\":true,\"assertions\":"+checks+",\"payloads\":"+packets.size()+",\"max_outer_bytes\":"+ZapperWire26Probe.maxBytes+",\"actual_registered_outer_codecs\":true,\"protocol\":33,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"server_world_authority_executed\":false}");
    }
}
