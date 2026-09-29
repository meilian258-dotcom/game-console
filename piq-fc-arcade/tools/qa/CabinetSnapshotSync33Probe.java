package cn.piq.fcarcade.cabinet;

import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;

/** Real MC outer codecs and real common registry; no Minecraft world/socket/native process. */
public final class CabinetSnapshotSync33Probe {
    private static int checks,packets,maximum;
    private static final String PIN="snapshot-profile-diagnostic-v1";
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void denied(Runnable action){boolean rejected=false;try{action.run();}catch(RuntimeException expected){rejected=true;}check(rejected,"rejected invalid operation");}
    private static CustomPacketPayload roundtrip(CustomPacketPayload payload,boolean up){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(payload));
            else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(payload));
            maximum=Math.max(maximum,b.readableBytes());if(up)check(b.readableBytes()<32767,"complete C2S packet under bound");
            var copy=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();
            check(!b.isReadable(),"no trailing fields");check(copy.type().equals(payload.type()),"same outer payload type");packets++;return copy;
        }finally{b.release();}
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(CabinetBackends.class,CabinetSyncPolicy.class,CabinetSyncPolicy.Identity.class,CabinetSyncNetwork.class,CabinetSynchronizer.class,CabinetRooms.class,CabinetSyncSettings.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"exact audited production CodeSource");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var nativeId=ResourceLocation.parse("snapshot_qa:native");var strictId=ResourceLocation.parse("snapshot_qa:sfc");
        CabinetBackends.register(nativeId,"Diagnostic snapshot core",false);CabinetBackends.registerNetwork(nativeId,4);CabinetBackends.registerSnapshotSync(nativeId,2,PIN);
        check(CabinetBackends.maxPlayers(nativeId)==4,"media still four ports");check(CabinetBackends.syncMaxPlayers(nativeId)==2,"experimental sync two ports");
        check(CabinetBackends.hostSnapshotSync(nativeId),"explicit host snapshot policy");check(PIN.equals(CabinetBackends.expectedSyncCompatibility(nativeId)),"exact common pin");
        CabinetBackends.register(strictId,"Diagnostic strict core",false);CabinetBackends.registerNetwork(strictId,2);CabinetBackends.registerSync(strictId);
        check(CabinetBackends.syncMaxPlayers(strictId)==2&&!CabinetBackends.hostSnapshotSync(strictId)&&CabinetBackends.expectedSyncCompatibility(strictId)==null,"legacy strict default unchanged");
        denied(()->CabinetBackends.registerSnapshotSync(strictId,2,PIN));denied(()->CabinetBackends.registerSync(nativeId));
        denied(()->CabinetBackends.registerSnapshotSync(CabinetBackends.NES,2,PIN));denied(()->CabinetBackends.registerSnapshotSync(ResourceLocation.parse("snapshot_qa:missing"),2,PIN));
        var limited=ResourceLocation.parse("snapshot_qa:one");CabinetBackends.register(limited,"One port",false);CabinetBackends.registerNetwork(limited,1);
        denied(()->CabinetBackends.registerSnapshotSync(limited,2,PIN));check(!CabinetBackends.supportsSync(limited),"failed registration leaves no capability");
        CabinetSyncNetwork.register(new RegisterPayloadHandlersEvent());
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);var registrations=(Map<?,?>)((Map<?,?>)field.get(null)).get(ConnectionProtocol.PLAY);
        for(var type:List.of(CabinetSyncNetwork.Hello.TYPE,CabinetSyncNetwork.Input.TYPE,CabinetSyncNetwork.Upload.TYPE,CabinetSyncNetwork.Ack.TYPE,CabinetSyncNetwork.Digest.TYPE,CabinetSyncNetwork.Resync.TYPE,CabinetSyncNetwork.Mode.TYPE,CabinetSyncNetwork.Frames.TYPE,CabinetSyncNetwork.Restore.TYPE,CabinetSyncNetwork.Active.TYPE,CabinetSyncNetwork.UploadGrant.TYPE,CabinetSyncNetwork.Setting.TYPE)){
            var r=(PayloadRegistration<?>)registrations.get(type.id());check(r!=null&&r.version().equals("cabinet-sync-2")&&!r.optional(),"mandatory semantic v2");
        }
        for(var type:List.of(CabinetSyncNetwork.Frames.TYPE,CabinetSyncNetwork.Restore.TYPE,CabinetSyncNetwork.Active.TYPE,CabinetSyncNetwork.UploadGrant.TYPE))check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot upload authority payload");
        UUID room=UUID.randomUUID(),member=UUID.randomUUID(),token=UUID.randomUUID();String hash="a".repeat(64),other="b".repeat(64);
        var host=new CabinetSyncNetwork.Hello(room,room,1,hash,hash,PIN,59186,hash);
        var guest=new CabinetSyncNetwork.Hello(room,member,1,hash,hash,PIN,59186,other);
        check(roundtrip(host,true).equals(host)&&roundtrip(guest,true).equals(guest),"different cold states survive real codec");
        check(CabinetBackends.syncPolicy(nativeId).acceptsGuest(host.identity(),guest.identity()),"explicit pin accepts cold restore candidate");
        check(!CabinetBackends.syncPolicy(strictId).acceptsGuest(host.identity(),guest.identity()),"strict same packets rejected");
        byte[] state=new byte[3334118];new Random(33).nextBytes(state);String digest=CabinetSyncState.hash(state);var assembled=new CabinetSyncState(token,state.length,digest);
        var begin=new CabinetSyncNetwork.StatePart(room,member,1,token,1800,2400,state.length,0,digest,true,new byte[0]);
        roundtrip(new CabinetSyncNetwork.Upload(begin),true);roundtrip(new CabinetSyncNetwork.Restore(begin),false);
        int chunks=0;
        for(int offset=0;offset<state.length;offset+=CabinetSyncState.CHUNK){
            byte[] part=Arrays.copyOfRange(state,offset,Math.min(state.length,offset+CabinetSyncState.CHUNK));
            var payload=new CabinetSyncNetwork.StatePart(room,member,1,token,1800,2400,state.length,offset,digest,false,part);
            var upload=(CabinetSyncNetwork.Upload)roundtrip(new CabinetSyncNetwork.Upload(payload),true);
            var restore=(CabinetSyncNetwork.Restore)roundtrip(new CabinetSyncNetwork.Restore(upload.part()),false);
            check(restore.part().frame()==1800&&restore.part().goal()==2400&&restore.part().total()==state.length,"state metadata preserved");
            check(assembled.append(restore.part().token(),restore.part().offset(),restore.part().bytes()),"ordered exact chunk");chunks++;
        }
        check(chunks==136&&Arrays.equals(assembled.finish(),state),"whole 3.334MB state exact after both real outer codecs");
        denied(()->new CabinetSyncNetwork.StatePart(room,member,1,token,0,0,CabinetSyncCore.MAX_STATE_BYTES+1,0,hash,true,new byte[0]));
        denied(()->new CabinetSyncNetwork.StatePart(room,member,1,token,0,0,32768,0,hash,false,new byte[CabinetSyncState.CHUNK+1]));
        output.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"outer_roundtrips",packets,"maximum_outer_bytes",maximum,"snapshot_bytes",state.length,"snapshot_chunks",chunks,"actual_outer_codecs",true,"actual_backend_registry",true,"minecraft_world_or_socket_started",false,"native_core_started",false)));
    }
}
