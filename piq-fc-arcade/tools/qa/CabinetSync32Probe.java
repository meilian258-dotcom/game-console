package cn.piq.fcarcade.cabinet;
import cn.piq.fcarcade.client.cabinet.CabinetSyncWorker;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.*;
import com.google.gson.Gson;
/** Actual outer packets and runtime origins, independent of a running game or a server socket. */
public final class CabinetSync32Probe {
    static int checks,roundtrips,maximum;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void denied(Runnable action){boolean failed=false;try{action.run();}catch(RuntimeException e){failed=true;}check(failed,"invalid packet rejected");}
    static byte[] encode(CustomPacketPayload packet,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(packet));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(packet));byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);maximum=Math.max(maximum,bytes.length);return bytes;}finally{b.release();}}
    static CustomPacketPayload decode(byte[] bytes,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);try{CustomPacketPayload value=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"no trailing bytes");return value;}finally{b.release();}}
    static void wire(CustomPacketPayload packet,boolean up){byte[] bytes=encode(packet,up);check(Arrays.equals(bytes,encode(decode(bytes,up),up)),"outer fields exact");if(up)check(bytes.length<32767,"real C2S payload below vanilla bound");roundtrips++;for(int n:new int[]{0,1,bytes.length/2,bytes.length-1})denied(()->decode(Arrays.copyOf(bytes,n),up));}
    public static void main(String[]args)throws Exception{
        var output=System.out;Path jar=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(CabinetSyncCore.class,CabinetSyncMode.class,CabinetSyncTimeline.class,CabinetSyncState.class,CabinetSyncGate.class,CabinetSyncNetwork.class,CabinetSyncNetwork.StatePart.class,CabinetSyncWorker.class,CabinetSynchronizer.class,CabinetSyncSettings.class,CabinetRoomLedger.class,CabinetRoomNetwork.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final jar class origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent());
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);var registrations=(Map<?,?>)((Map<?,?>)field.get(null)).get(ConnectionProtocol.PLAY);
        for(var type:List.of(CabinetSyncNetwork.Hello.TYPE,CabinetSyncNetwork.Input.TYPE,CabinetSyncNetwork.Upload.TYPE,CabinetSyncNetwork.Ack.TYPE,CabinetSyncNetwork.Digest.TYPE,CabinetSyncNetwork.Resync.TYPE,CabinetSyncNetwork.Mode.TYPE,CabinetSyncNetwork.Frames.TYPE,CabinetSyncNetwork.Restore.TYPE,CabinetSyncNetwork.Active.TYPE,CabinetSyncNetwork.UploadGrant.TYPE,CabinetSyncNetwork.Setting.TYPE)){var entry=(PayloadRegistration<?>)registrations.get(type.id());check(entry!=null&&entry.version().equals("cabinet-sync-1")&&!entry.optional(),"mandatory sync protocol");}
        check(((PayloadRegistration<?>)registrations.get(CabinetRoomNetwork.Assignment.TYPE.id())).version().equals("cabinet-room-4"),"room mode strict protocol");
        for(var type:List.of(CabinetSyncNetwork.Frames.TYPE,CabinetSyncNetwork.Restore.TYPE,CabinetSyncNetwork.Active.TYPE,CabinetSyncNetwork.UploadGrant.TYPE,CabinetSyncNetwork.Setting.TYPE))check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"viewer cannot mint authority messages");
        for(var type:List.of(CabinetSyncNetwork.Hello.TYPE,CabinetSyncNetwork.Input.TYPE,CabinetSyncNetwork.Upload.TYPE,CabinetSyncNetwork.Ack.TYPE,CabinetSyncNetwork.Digest.TYPE,CabinetSyncNetwork.Resync.TYPE,CabinetSyncNetwork.Mode.TYPE))check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"C2S has no alternate client ingress");
        UUID room=UUID.randomUUID(),member=UUID.randomUUID(),token=UUID.randomUUID();String hash="a".repeat(64);var target=new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(0,64,0),UUID.randomUUID(),true);var backend=ResourceLocation.parse("test:sfc");
        wire(new CabinetSyncNetwork.Hello(room,member,1,hash,hash,"sfc-diagnostic-v1",60000,hash),true);
        for(int port=0;port<4;port++){wire(new CabinetSyncNetwork.Input(room,member,1,port,1<<port,false),true);}wire(new CabinetSyncNetwork.Input(room,member,1,4,0,true),true);
        var steps=new ArrayList<CabinetSyncTimeline.Step>();for(int i=0;i<120;i++)steps.add(new CabinetSyncTimeline.Step(i+1,4095,4095,4095,4095));wire(new CabinetSyncNetwork.Frames(room,member,1,steps),false);
        byte[] blob=new byte[CabinetSyncState.CHUNK];new Random(32).nextBytes(blob);var begin=new CabinetSyncNetwork.StatePart(room,member,1,token,300,600,CabinetSyncCore.MAX_STATE_BYTES,0,hash,true,new byte[0]);var chunk=new CabinetSyncNetwork.StatePart(room,member,1,token,300,600,CabinetSyncCore.MAX_STATE_BYTES,0,hash,false,blob);
        wire(new CabinetSyncNetwork.Upload(begin),true);wire(new CabinetSyncNetwork.Upload(chunk),true);wire(new CabinetSyncNetwork.Restore(begin),false);wire(new CabinetSyncNetwork.Restore(chunk),false);
        wire(new CabinetSyncNetwork.Ack(room,member,1,token,600),true);wire(new CabinetSyncNetwork.Active(room,member,1,token),false);wire(new CabinetSyncNetwork.UploadGrant(room,member,1,token),false);wire(new CabinetSyncNetwork.Digest(room,member,1,300,hash),true);wire(new CabinetSyncNetwork.Resync(room,member,1),true);
        for(int mode=-1;mode<2;mode++)wire(new CabinetSyncNetwork.Mode(target,backend,mode),true);for(int mode=0;mode<2;mode++)wire(new CabinetSyncNetwork.Setting(target,backend,mode,true,true,"checked"),false);
        for(var mode:CabinetSyncMode.values()){wire(new CabinetRoomNetwork.Assignment(room,room,room,0,2,target,backend,target,null,mode),false);}
        denied(()->new CabinetSyncNetwork.Input(room,member,0,0,0,false));denied(()->new CabinetSyncNetwork.Input(room,member,1,-1,0,false));denied(()->new CabinetSyncNetwork.Input(room,member,1,0,4096,false));denied(()->new CabinetSyncNetwork.Input(room,member,1,0,1,true));
        denied(()->new CabinetSyncNetwork.Hello(room,member,1,hash,hash,"bad\n",60000,hash));denied(()->new CabinetSyncNetwork.Hello(room,member,1,hash,hash,"x",1,hash));denied(()->new CabinetSyncNetwork.Digest(room,member,1,301,hash));
        denied(()->new CabinetSyncNetwork.StatePart(room,member,1,token,0,0,CabinetSyncCore.MAX_STATE_BYTES+1,0,hash,true,new byte[0]));denied(()->new CabinetSyncNetwork.StatePart(room,member,1,token,0,0,blob.length+1,0,hash,false,new byte[blob.length+1]));denied(()->new CabinetSyncNetwork.StatePart(room,member,1,token,0,0,1,0,hash,true,new byte[1]));
        denied(()->new CabinetSyncNetwork.Frames(room,member,1,List.of()));denied(()->new CabinetSyncNetwork.Frames(room,member,1,List.of(steps.get(0),steps.get(2))));
        var ledger=new CabinetRoomLedger<String>();UUID player=UUID.randomUUID();var state=ledger.open(player,"a","b",2,0);check(ledger.ready(player,state.id,state.host().id,0),"host ready");UUID guestPlayer=UUID.randomUUID();var guest=ledger.join(guestPlayer,state,0);Object connection=new Object();var gate=new CabinetSyncGate(state.id,guest.id,connection,false);
        check(!gate.input(state.id,guest.id,1,connection),"approved seat cannot input before restored");check(!gate.input(state.id,guest.id,1,new Object()),"wrong connection never authorized");check(gate.begin(token,300,100),"transaction begins");check(!gate.acknowledge(UUID.randomUUID(),300,300,true,1),"wrong token rejected");check(!gate.acknowledge(token,300,300,false,1),"partial state rejected");check(gate.acknowledge(token,300,300,true,1),"complete restored state activates exact seat");check(gate.input(state.id,guest.id,1,connection),"restored guest input");ledger.remove(guestPlayer,guest.id);gate.close();check(!gate.input(state.id,guest.id,1,connection),"retired lease denied");check(state.host()!=null&&ledger.get(state.id)==state,"guest removal preserves host");var fresh=ledger.join(guestPlayer,state,2);check(fresh!=null&&!fresh.id.equals(guest.id),"rejoin uses fresh lease");check(ledger.input(guestPlayer,state.id,guest.id,100,4095,3)==null,"old lease cannot borrow new seat");
        output.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"outer_roundtrips",roundtrips,"maximum_outer_bytes",maximum,"production_origin","final-jar-only","actual_outer_codecs",true,"minecraft_world_or_socket_started",false,"native_core_started",false)));
    }
}
