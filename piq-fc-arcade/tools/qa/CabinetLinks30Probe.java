package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.client.CabinetDataCableRenderer;
import cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Actual registered outer codecs, SavedData and BlockEntity tags, not a game/world or permission-plugin test. */
public final class CabinetLinks30Probe {
    private static int checks,maxBytes;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    private static void denied(Runnable action){boolean fail=false;try{action.run();}catch(RuntimeException expected){fail=true;}check(fail,"invalid packet refused");}
    private static byte[] encode(CustomPacketPayload value,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(value));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(value));var result=new byte[b.readableBytes()];b.readBytes(result);maxBytes=Math.max(maxBytes,result.length);return result;}finally{b.release();}}
    private static CustomPacketPayload decode(byte[] bytes,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);try{var value=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"entire packet consumed");return value;}finally{b.release();}}
    private static void roundtrip(CustomPacketPayload value,boolean up){byte[] bytes=encode(value,up);check(value.equals(decode(bytes,up)),"outer packet exact fields");for(int n=0;n<bytes.length;n++){byte[] cut=Arrays.copyOf(bytes,n);denied(()->decode(cut,up));}}
    private static CabinetTarget target(int x,boolean dual){return new CabinetTarget(ResourceLocation.parse("minecraft:overworld"),new BlockPos(x,64,0),UUID.randomUUID(),dual);}
    private static CabinetLinkLedger.End end(CabinetTarget t){return new CabinetLinkLedger.End(t.dimension().toString(),t.anchor().getX(),t.anchor().getY(),t.anchor().getZ(),t.identity(),t.dual());}
    private static LegacyFcArcadeBlockEntity block(CabinetTarget target)throws Exception{var constructor=LegacyFcArcadeBlockEntity.class.getDeclaredConstructor(BlockEntityType.class,BlockPos.class,BlockState.class);constructor.setAccessible(true);return constructor.newInstance(BlockEntityType.CHEST,target.anchor(),Blocks.CHEST.defaultBlockState());}
    private static void load(LegacyFcArcadeBlockEntity block,CompoundTag tag)throws Exception{var m=LegacyFcArcadeBlockEntity.class.getDeclaredMethod("loadAdditional",CompoundTag.class,HolderLookup.Provider.class);m.setAccessible(true);m.invoke(block,tag,RegistryAccess.EMPTY);}
    private static CompoundTag visual(CabinetTarget own,CabinetTarget other,UUID pair){var tag=new CompoundTag();tag.putUUID("CabinetId",own.identity());var v=new CompoundTag();v.putUUID("Pair",pair);v.putUUID("Id",other.identity());v.putString("Dimension",other.dimension().toString());v.putInt("X",other.anchor().getX());v.putInt("Y",other.anchor().getY());v.putInt("Z",other.anchor().getZ());v.putBoolean("Dual",other.dual());tag.put("VisualCabinetLink",v);return tag;}
    public static void main(String[] args)throws Exception{
        var output=System.out;var origin=Path.of(args[0]).toRealPath();
        for(Class<?> c:List.of(CabinetSeats.class,CabinetLinks.class,CabinetLinkLedger.class,CabinetRooms.class,CabinetRoomNetwork.class,CabinetRoomNetwork.Assignment.class,LegacyFcArcadeBlockEntity.class,CabinetDataCableRenderer.class,
                cn.piq.fcarcade.layout.CabinetDataCableGeometry.class,cn.piq.fcarcade.layout.CabinetDataCableGeometry.Quad.class,
                Class.forName("cn.piq.fcarcade.client.LegacyArcadeSkinRenderer",false,CabinetLinks30Probe.class.getClassLoader()),cn.piq.fcarcade.client.DualCabinetRenderer.class,
                cn.piq.fcarcade.client.cabinet.CabinetPeerInputs.class,cn.piq.fcarcade.client.cabinet.CabinetClientBackends.class))check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(origin),"exact production origin "+c);
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        CabinetRoomNetwork.register(new RegisterPayloadHandlersEvent());
        var registrationsField=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");registrationsField.setAccessible(true);
        var registrations=(Map<?,?>)((Map<?,?>)registrationsField.get(null)).get(ConnectionProtocol.PLAY);
        for(var type:List.of(CabinetRoomNetwork.Assignment.TYPE,CabinetRoomNetwork.Seat.TYPE,CabinetRoomNetwork.Buttons.TYPE,CabinetRoomNetwork.Stream.TYPE,CabinetRoomNetwork.Ready.TYPE,CabinetRoomNetwork.Input.TYPE,CabinetRoomNetwork.Reset.TYPE,CabinetRoomNetwork.Media.TYPE)){
            var registration=(net.neoforged.neoforge.network.registration.PayloadRegistration<?>)registrations.get(type.id());
            check(registration!=null&&registration.version().equals("cabinet-room-2")&&!registration.optional(),"actual mandatory paired protocol2 registration");
        }
        check(NetworkRegistry.getCodec(CabinetRoomNetwork.Assignment.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"assignment is server to client");
        check(NetworkRegistry.getCodec(CabinetRoomNetwork.Assignment.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"clients cannot mint seats");
        for(boolean a:new boolean[]{false,true})for(boolean b:new boolean[]{false,true}){
            var first=target(0,a);var second=target(6,b);var room=UUID.randomUUID();var host=UUID.randomUUID();int capacity=CabinetSeats.linkedCapacity(a,b);
            for(int port=0;port<capacity;port++){var at=port<CabinetSeats.physical(a)?first:second;var member=port==0?host:UUID.randomUUID();roundtrip(new CabinetRoomNetwork.Assignment(room,member,host,port,capacity,at,ResourceLocation.parse("example:backend"),first,second),false);
                final int assigned=port;var wrong=at.equals(first)?second:first;denied(()->new CabinetRoomNetwork.Assignment(room,member,host,assigned,capacity,wrong,ResourceLocation.parse("example:backend"),first,second));}
            for(int invalid:new int[]{1,5,capacity==2?3:2})denied(()->new CabinetRoomNetwork.Assignment(room,host,host,0,invalid,first,ResourceLocation.parse("example:backend"),first,second));
            var data=new CabinetLinks.Data();var pair=data.ledger.connect(end(first),end(second),true,true,capacity);check(pair!=null,"real topology stores combination");var tag=data.save(new CompoundTag(),RegistryAccess.EMPTY);var copy=CabinetLinks.Data.load(tag,RegistryAccess.EMPTY);check(copy.ledger.snapshot().equals(data.ledger.snapshot()),"NBT exact pair and seats");
            var replacement=new CabinetTarget(second.dimension(),second.anchor(),UUID.randomUUID(),second.dual());check(copy.ledger.remove(end(replacement))==null,"replaced block cannot remove original identity");
            check(copy.ledger.remove(end(second)).equals(pair),"physical removal exact second endpoint removes pair");check(copy.ledger.find(end(first))==null&&copy.ledger.find(end(second))==null,"both seats free after real ledger removal");
            check(CabinetLinks.Data.load(copy.save(new CompoundTag(),RegistryAccess.EMPTY),RegistryAccess.EMPTY).ledger.snapshot().isEmpty(),"removed pair stays removed after save/reload");
            check(copy.ledger.connect(end(first),end(replacement),true,true,capacity)!=null,"new placement new identity can be connected without resurrecting old link");
            for(String field:List.of("Dual","Id","X")){var bad=tag.copy();bad.getList("Pairs",Tag.TAG_COMPOUND).getCompound(0).getCompound("Primary").remove(field);check(CabinetLinks.Data.load(bad,RegistryAccess.EMPTY).ledger.snapshot().isEmpty(),"missing endpoint field does not become single");}
            var left=block(first);var right=block(second);load(left,visual(first,second,pair.id()));load(right,visual(second,first,pair.id()));check(left.visualLinkId().equals(pair.id())&&left.visualLinkPeer().equals(second),"real BE accepts bounded display tag");
            check(CabinetDataCableRenderer.matches(first,left.visualLinkId(),left.visualLinkPeer(),second,right.visualLinkId(),right.visualLinkPeer()),"mutual display identities match");
            check(!CabinetDataCableRenderer.matches(first,left.visualLinkId(),left.visualLinkPeer(),second,UUID.randomUUID(),right.visualLinkPeer()),"old pair cannot draw new cable");
            check(!CabinetDataCableRenderer.matches(first,left.visualLinkId(),left.visualLinkPeer(),replacement,right.visualLinkId(),right.visualLinkPeer()),"replacement UUID cannot draw old pair");
            check(!CabinetDataCableRenderer.matches(first,left.visualLinkId(),left.visualLinkPeer(),second,right.visualLinkId(),second),"one-sided visual cannot draw");
            check(!left.saveWithoutMetadata(RegistryAccess.EMPTY).contains("VisualCabinetLink"),"display data is not persisted as authority");
            var update=left.getUpdateTag(RegistryAccess.EMPTY);check(update.contains("VisualCabinetLink",Tag.TAG_COMPOUND),"display data is emitted in actual BE update");var replay=block(first);load(replay,update);check(replay.visualLinkPeer().equals(second)&&replay.visualLinkId().equals(pair.id()),"client display update roundtrip");
            load(replay,new CompoundTag());check(replay.visualLinkPeer()==null&&replay.visualLinkId()==null,"missing update clears stale visual");
            var far=visual(first,second,pair.id());far.getCompound("VisualCabinetLink").putInt("X",10000);load(replay,far);check(replay.visualLinkPeer()==null,"overlong visual cannot force loading or large render bounds");
        }
        UUID room=UUID.randomUUID(),host=UUID.randomUUID(),guest=UUID.randomUUID();roundtrip(new CabinetRoomNetwork.Input(room,guest,9,4095),true);roundtrip(new CabinetRoomNetwork.Reset(room,guest,10),true);roundtrip(new CabinetRoomNetwork.Ready(room,host),true);roundtrip(new CabinetRoomNetwork.Seat(room,host,guest,3,true),false);roundtrip(new CabinetRoomNetwork.Buttons(room,host,guest,3,5,0,true),false);
        output.println("{\"ok\":true,\"assertions\":"+checks+",\"max_outer_bytes\":"+maxBytes+",\"actual_registered_outer_codecs\":true,\"actual_nbt_saveddata_blockentity\":true,\"production_origin\":\""+args[1]+"\",\"minecraft_world_or_socket_started\":false,\"server_permission_events_executed\":false}");
    }
}
