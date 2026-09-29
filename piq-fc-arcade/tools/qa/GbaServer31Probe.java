package cn.piq.fcarcade.cabinet;

import cn.piq.gba.GbaMod;
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
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.google.gson.Gson;

/** Real server ledgers and outer codecs; bytecode gates are reported separately from live-world execution. */
public final class GbaServer31Probe {
    private static int checks,roundtrips,maximumPacket,bytecodeChecks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void denied(Runnable action){boolean failed=false;try{action.run();}catch(RuntimeException expected){failed=true;}check(failed,"invalid input rejected");}
    private static byte[] encode(CustomPacketPayload value,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(value));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(value));byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);maximumPacket=Math.max(maximumPacket,bytes.length);return bytes;}finally{b.release();}}
    private static CustomPacketPayload decode(byte[] bytes,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);try{CustomPacketPayload value=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"all outer bytes consumed");return value;}finally{b.release();}}
    private static void wire(CustomPacketPayload value,boolean up){byte[] bytes=encode(value,up);check(Arrays.equals(bytes,encode(decode(bytes,up),up)),"outer roundtrip preserves exact fields");roundtrips++;for(int n=0;n<bytes.length;n++){byte[] cut=Arrays.copyOf(bytes,n);denied(()->decode(cut,up));}}
    private static CabinetTarget target(int x,boolean dual){return new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(x,64,0),UUID.randomUUID(),dual);}
    private static CabinetLinkLedger.End end(CabinetTarget t){return new CabinetLinkLedger.End(t.dimension().toString(),t.anchor().getX(),t.anchor().getY(),t.anchor().getZ(),t.identity(),t.dual());}
    private static void zeroWireRejected(CabinetTarget target){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
        b.writeResourceLocation(CabinetRoomNetwork.Assignment.TYPE.id());UUID id=UUID.randomUUID();b.writeUUID(id);b.writeUUID(id);b.writeUUID(id);b.writeVarInt(0);b.writeVarInt(0);CabinetNetwork.writeTarget(b,target);b.writeUtf(GbaMod.BACKEND.toString(),128);CabinetNetwork.writeTarget(b,target);b.writeBoolean(false);byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);denied(()->decode(bytes,false));
    }finally{b.release();}}
    private static void oneSeatAndWatch(){
        var ledger=new CabinetRoomLedger<CabinetTarget>();UUID host=UUID.randomUUID(),viewer=UUID.randomUUID();var at=target(0,false);var room=ledger.open(host,at,GbaMod.BACKEND.toString(),1,0);check(room!=null&&room.members.length==1&&room.host().port==0,"real one-seat room");
        check(!ledger.ready(viewer,room.id,room.host().id,0),"other player cannot ready host");check(ledger.ready(host,room.id,room.host().id,1),"host ready");check(ledger.join(viewer,room,2)==null,"P2 rejected without slot");check(ledger.join(viewer,room,2,1,2)==null,"explicit P2 rejected");
        check(ledger.join(viewer,room,2,0,1)==null,"join cannot seize P1");check(ledger.open(host,target(3,false),"other",1,2)==null,"host cannot occupy second room");
        var watches=new WatchLedger();Object connection=new Object();var source=new WatchLedger.Source(room.id,room.host().id);var lease=watches.select(viewer,connection,List.of(new WatchLedger.Candidate(source,1)),true,2);check(lease!=null&&watches.count(source)==1,"independent read-only observation lease");
        check(ledger.player(viewer)==null&&ledger.allMembers().size()==1,"observation never allocates controller");check(!ledger.ready(viewer,room.id,lease.token(),3),"watch token cannot Ready");check(ledger.input(viewer,room.id,lease.token(),0,4095,3)==null,"watch token cannot Input");check(ledger.resetInput(viewer,room.id,lease.token(),1,3)==null,"watch token cannot Reset");check(!ledger.heartbeat(viewer,lease.token(),3),"watch heartbeat cannot extend controller");
        check(ledger.input(viewer,room.id,room.host().id,0,4095,3)==null,"leaked host UUID not player authority");check(ledger.resetInput(viewer,room.id,room.host().id,1,3)==null,"host UUID cannot authorize viewer reset");
        var press=ledger.input(host,room.id,room.host().id,0,1,4);check(press!=null&&room.host().mask==1,"host input works");check(ledger.input(host,room.id,room.host().id,0,2,4)==null,"old host sequence rejected");
        check(watches.heartbeat(viewer,connection,lease.token(),lease.revision(),5),"watch keeps own lease");check(room.host().mask==1&&room.host().sequence==0,"watch leaves core input unchanged");
        wire(new CabinetRoomNetwork.Assignment(room.id,room.host().id,room.host().id,0,1,at,GbaMod.BACKEND,at,null),false);zeroWireRejected(at);
        denied(()->new CabinetRoomNetwork.Assignment(room.id,UUID.randomUUID(),room.host().id,1,1,at,GbaMod.BACKEND,at,null));
        wire(new CabinetRoomNetwork.Ready(room.id,lease.token()),true);wire(new CabinetRoomNetwork.Input(room.id,lease.token(),2,1),true);wire(new CabinetRoomNetwork.Reset(room.id,lease.token(),3),true);
        var descriptor=new WatchDescriptor(CabinetRooms.WATCH_PROVIDER,room.id,room.host().id,at.dimension(),new WatchAnchor(at.anchor(),at.identity()),null,List.of(new WatchAnchor(at.anchor(),at.identity())));
        wire(new WatchNetwork.Start(lease.revision(),lease.token(),descriptor),false);wire(new WatchNetwork.Available(true),true);wire(new WatchNetwork.Heartbeat(lease.revision(),lease.token()),true);wire(new WatchNetwork.Release(lease.revision(),lease.token()),true);
        wire(new WatchNetwork.Stream(lease.token(),new CabinetRoomNetwork.Media(room.id,room.host().id,0,1,0,1,0,0,1F,0,4,new byte[4])),false);
        Object replacement=new Object();var next=watches.select(viewer,replacement,List.of(new WatchLedger.Candidate(source,1)),true,6);check(next!=null&&!next.token().equals(lease.token())&&next.revision()>lease.revision(),"reconnect rotates watch identity");
        check(watches.release(viewer,connection,lease.token(),lease.revision(),7)==null,"old connection release cannot stop new viewer");check(!watches.heartbeat(viewer,connection,lease.token(),lease.revision(),7),"old connection heartbeat rejected");
        check(ledger.remove(host,room.host().id).size()==1&&ledger.get(room.id)==null,"host exit removes single room");check(watches.removeSource(source).size()==1&&watches.get(viewer)==null,"source end removes spectators independently");check(ledger.input(host,room.id,room.host().id,99,1,8)==null,"old room cannot resume after removal");
        var nextRoom=ledger.open(host,at,GbaMod.BACKEND.toString(),1,9);check(nextRoom!=null&&!nextRoom.id.equals(room.id),"new run gets new lease");check(!ledger.ready(host,nextRoom.id,room.host().id,9),"old token cannot ready new run");check(!ledger.heartbeat(host,nextRoom.host().id,89),"single host lease expires");
    }
    private static void capacities(){
        for(boolean dual:new boolean[]{false,true})for(int n=-1;n<=5;n++){int expected=n>=1&&n<=4?Math.min(2,n):0;check(CabinetSeats.capacity(n,false,dual,false)==expected,"unlinked capacity including 1");check(CabinetSeats.validCapacity(dual,false,false,n)==(n==1||n==2),"unlinked wire cap");}
        for(boolean first:new boolean[]{false,true})for(boolean second:new boolean[]{false,true}){
            int count=CabinetSeats.linkedCapacity(first,second);var a=target(0,first);var b=target(6,second);var links=new CabinetLinkLedger();check(links.connect(end(a),end(b),true,true,1)==null,"GBA rejects every linked combination");
            for(int supported=0;supported<=5;supported++)check(CabinetSeats.capacity(supported,true,first,second)==(supported>=count&&supported<=4?count:0),"provider capacity bound");
            var ledger=new CabinetRoomLedger<CabinetTarget>();UUID host=UUID.randomUUID();var room=ledger.open(host,a,"test:legacy",count,0);check(room!=null&&ledger.ready(host,room.id,room.host().id,0),"old multi-seat room opens");
            for(int port=0;port<count;port++){var member=port==0?room.host():ledger.join(UUID.randomUUID(),room,1,port,port+1);check(member!=null&&member.port==port,"old numbered port allocates");var at=port<CabinetSeats.physical(first)?a:b;wire(new CabinetRoomNetwork.Assignment(room.id,member.id,room.host().id,port,count,at,ResourceLocation.parse("test:legacy"),a,b),false);}
            check(ledger.join(UUID.randomUUID(),room,1)==null,"old capacity overflow refused");check(links.connect(end(a),end(b),true,true,count)!=null,"old links preserved");
        }
        for(int invalid:new int[]{0,-1,5})check(new CabinetRoomLedger<String>().open(UUID.randomUUID(),"t","b",invalid,0)==null,"invalid room capacity rejects");
        var budget=new WatchBudget();UUID source=UUID.randomUUID();check(budget.tryReserve(source,0,WatchBudget.SOURCE_BYTES),"bounded full source reservation");check(!budget.tryReserve(source,19,1),"rolling budget blocks edge overflow");budget.remove(source);for(int i=0;i<3;i++)check(budget.tryReserve(UUID.randomUUID(),0,WatchBudget.SOURCE_BYTES),"global independent source count");check(!budget.tryReserve(UUID.randomUUID(),1,1),"removed source does not refund global bytes");
    }
    private static List<AbstractInsnNode> ops(MethodNode method){var result=new ArrayList<AbstractInsnNode>();for(var i:method.instructions)if(i.getOpcode()>=0)result.add(i);return result;}
    private static void gates()throws Exception {
        var node=new ClassNode();try(var in=CabinetRooms.class.getResourceAsStream("CabinetRooms.class")){new ClassReader(in).accept(node,0);}
        var interact=node.methods.stream().filter(m->m.name.equals("interact")).findFirst().orElseThrow();var instructions=ops(interact);boolean guarded=false;
        for(int i=0;i+2<instructions.size();i++)if(instructions.get(i)instanceof FieldInsnNode f&&f.name.equals("capacity")&&instructions.get(i+1).getOpcode()==Opcodes.ICONST_1&&instructions.get(i+2)instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IF_ICMPLE){
            boolean creation=false;for(var at=jump.getNext();at!=null&&at!=jump.label;at=at.getNext())if(at instanceof TypeInsnNode type&&type.getOpcode()==Opcodes.NEW&&type.desc.equals("cn/piq/fcarcade/cabinet/CabinetJoinGate"))creation=true;guarded|=creation;
        }
        check(guarded,"final bytecode creates invite gate only when room capacity>1");bytecodeChecks++;
        var request=node.methods.stream().filter(m->m.name.equals("requestJoin")).findFirst().orElseThrow();var r=ops(request);boolean early=false;
        for(int i=0;i+2<r.size();i++)if(r.get(i)instanceof FieldInsnNode f&&f.name.equals("capacity")&&r.get(i+1).getOpcode()==Opcodes.ICONST_1&&r.get(i+2)instanceof JumpInsnNode jump&&jump.getOpcode()==Opcodes.IF_ICMPNE){for(var at=jump.getNext();at!=null&&at!=jump.label;at=at.getNext())if(at.getOpcode()==Opcodes.RETURN)early=true;break;}
        check(early,"final requestJoin bytecode returns for one seat before normal invitation flow");bytecodeChecks++;
    }
    public static void main(String[] args)throws Exception {
        var output=System.out;var fc=Path.of(args[0]).toRealPath();var gba=Path.of(args[1]).toRealPath();for(Class<?> c:List.of(CabinetBackends.class,CabinetSeats.class,CabinetRoomLedger.class,CabinetRoomNetwork.class,CabinetRoomNetwork.Assignment.class,CabinetRooms.class,CabinetLinkLedger.class,WatchLedger.class,WatchBudget.class,WatchNetwork.class,WatchService.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(fc),"exact FC production origin");check(Path.of(GbaMod.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(gba),"exact GBA production origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();GbaMod.registerBackend();check(!CabinetBackends.find(GbaMod.BACKEND).localOnly()&&CabinetBackends.maxPlayers(GbaMod.BACKEND)==1,"real common single-seat metadata");check(CabinetBackends.maxPlayers(CabinetBackends.NES)==0,"NES old path not falsely opted in");
        CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent());WatchNetwork.register(new RegisterPayloadHandlersEvent());check(!WatchProviders.find(CabinetRooms.WATCH_PROVIDER).acceptsUpload(),"cabinet provider refuses alternate spectator upload ingress");
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);var registrations=(Map<?,?>)((Map<?,?>)field.get(null)).get(ConnectionProtocol.PLAY);
        for(var type:List.of(CabinetRoomNetwork.Assignment.TYPE,CabinetRoomNetwork.Seat.TYPE,CabinetRoomNetwork.Buttons.TYPE,CabinetRoomNetwork.Stream.TYPE,CabinetRoomNetwork.Ready.TYPE,CabinetRoomNetwork.Input.TYPE,CabinetRoomNetwork.Reset.TYPE,CabinetRoomNetwork.Media.TYPE)){var entry=(PayloadRegistration<?>)registrations.get(type.id());check(entry!=null&&entry.version().equals("cabinet-room-3")&&!entry.optional(),"mandatory room3 pair");}
        check(NetworkRegistry.getCodec(CabinetRoomNetwork.Assignment.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot mint assignment");check(NetworkRegistry.getCodec(WatchNetwork.Start.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot select observation source");
        var startFields=Arrays.stream(WatchNetwork.Start.class.getRecordComponents()).map(c->c.getName()).toList();check(startFields.equals(List.of("revision","lease","descriptor")),"watch assignment has no ROM payload or controller seat");
        capacities();oneSeatAndWatch();gates();
        output.println(new Gson().toJson(Map.of("ok",true,"assertions",checks,"outer_roundtrips",roundtrips,"maximum_outer_bytes",maximumPacket,"bytecode_gate_checks",bytecodeChecks,"production_origin","final-jar-only","actual_outer_codecs",true,"actual_pure_authority_ledgers",true,"minecraft_world_or_socket_started",false,"native_core_started",false)));
    }
}
