package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Real production registration, outer MC codecs, dispatch and actual write-completion windows.
 * No world/server/client game is created, no socket is opened, no emulator/ROM is loaded. */
public final class Watch23WireProbe {
    private static int checks,maxOuterBytes;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static UUID id(long n){return new UUID(0,n);}
    private static Path origin(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private static <T>byte[] encode(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return bytes;}finally{b.release();}}
    private static <T>T decode(StreamCodec<RegistryFriendlyByteBuf,T> codec,byte[] bytes){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);try{var result=codec.decode(b);check(!b.isReadable(),"one exact body consumed");return result;}finally{b.release();}}
    private static <T>void round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){check(value.equals(decode(codec,encode(codec,value))),"all record fields roundtrip");}
    private static void denied(Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|NullPointerException|IndexOutOfBoundsException|io.netty.handler.codec.DecoderException e){failed=true;}check(failed,"invalid body rejected before use");}
    private static CabinetRoomNetwork.Media media(int kind){int length=kind==0?24576:19200;byte[] bytes=new byte[length];new Random(33).nextBytes(bytes);return new CabinetRoomNetwork.Media(id(1),id(2),Long.MAX_VALUE,kind,0,1,kind==0?384:0,kind==0?288:0,kind==0?16F/9:1,kind==0?3:0,kind==0?221184:length,bytes);}
    private static void registration(){
        // Execute the actual production method, not a separately invented registrar fixture.
        WatchNetwork.register(new RegisterPayloadHandlersEvent());
        Set<CustomPacketPayload.Type<?>> serverOnly=Set.of(WatchNetwork.Available.TYPE,WatchNetwork.Release.TYPE,WatchNetwork.Media.TYPE);
        Set<CustomPacketPayload.Type<?>> clientOnly=Set.of(WatchNetwork.Start.TYPE,WatchNetwork.Stop.TYPE,WatchNetwork.HostDemand.TYPE,WatchNetwork.Stream.TYPE);
        for(var type:serverOnly){check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"registered C2S read-only action");check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"C2S action not S2C");}
        for(var type:clientOnly){check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"registered S2C metadata/media");check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot forge server watch assignment");}
        for(var flow:PacketFlow.values())check(NetworkRegistry.getCodec(WatchNetwork.Heartbeat.TYPE.id(),ConnectionProtocol.PLAY,flow)!=null,"heartbeat bidirectional");
        for(String forbidden:List.of("ready","input","reset","rom","controller","assignment"))check(NetworkRegistry.getCodec(ResourceLocation.parse("piq_fc_arcade:watch_"+forbidden),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"no observer control/ROM capability registered");
    }
    private static void codecs(){
        var mutable=new BlockPos.MutableBlockPos(1,64,2);var anchor=new WatchAnchor(mutable,id(3));mutable.set(8,9,10);check(anchor.pos().equals(new BlockPos(1,64,2)),"anchor copies mutable position");
        var screens=new ArrayList<WatchAnchor>();screens.add(anchor);
        var d=new WatchDescriptor(ResourceLocation.parse("piq_sfc_home:sfc"),id(1),id(2),ResourceLocation.parse("minecraft:overworld"),anchor,null,screens);
        screens.clear();check(d.screens().size()==1,"descriptor copies caller list");
        var dual=new WatchDescriptor(ResourceLocation.parse("piq_fc_arcade:cabinet"),id(5),id(6),d.dimension(),anchor,id(9),List.of(anchor,new WatchAnchor(new BlockPos(-3,90,6),id(10))));
        for(var value:List.of(d,dual)){
            round(WatchNetwork.Start.CODEC,new WatchNetwork.Start(Long.MAX_VALUE,id(20),value));
            for(int watchers:new int[]{0,1,8}){var demand=new WatchNetwork.HostDemand(Long.MAX_VALUE,value,watchers);round(WatchNetwork.HostDemand.CODEC,demand);check(demand.needed()==(watchers>0),"zero disables host demand");}
        }
        round(WatchNetwork.Stop.CODEC,new WatchNetwork.Stop(Long.MAX_VALUE,id(20),"停止旁观"));
        round(WatchNetwork.Heartbeat.CODEC,new WatchNetwork.Heartbeat(Long.MAX_VALUE,id(20)));
        round(WatchNetwork.Release.CODEC,new WatchNetwork.Release(Long.MAX_VALUE,id(20)));
        for(boolean enabled:new boolean[]{true,false})round(WatchNetwork.Available.CODEC,new WatchNetwork.Available(enabled));
        for(int kind=0;kind<2;kind++){
            var original=media(kind);var up=decode(WatchNetwork.Media.CODEC,encode(WatchNetwork.Media.CODEC,new WatchNetwork.Media(original)));
            check(up.media().room().equals(id(1))&&up.media().hostMember().equals(id(2))&&Arrays.equals(up.media().data(),original.data()),"upload preserves source, host lease and exact payload");
            var down=decode(WatchNetwork.Stream.CODEC,encode(WatchNetwork.Stream.CODEC,new WatchNetwork.Stream(id(20),original)));
            check(down.lease().equals(id(20))&&Arrays.equals(down.media().data(),original.data()),"recipient lease cannot be confused with host lease");
            var outer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ServerboundCustomPayloadPacket.STREAM_CODEC.encode(outer,new ServerboundCustomPayloadPacket(new WatchNetwork.Media(original)));maxOuterBytes=Math.max(maxOuterBytes,outer.readableBytes());check(outer.readableBytes()<32767,"actual maximum outer upload stays below vanilla cap");
                var copy=ServerboundCustomPayloadPacket.STREAM_CODEC.decode(outer);check(copy.payload() instanceof WatchNetwork.Media m&&Arrays.equals(m.media().data(),original.data()),"real NeoForge outer codec routes watch upload");check(!outer.isReadable(),"outer upstream consumes exact packet");
            }finally{outer.release();}
            outer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(outer,new ClientboundCustomPayloadPacket(new WatchNetwork.Stream(id(20),original)));check(outer.readableBytes()<25000,"outer downstream is bounded including viewer lease");var copy=ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(outer);check(copy.payload() instanceof WatchNetwork.Stream stream&&stream.lease().equals(id(20))&&Arrays.equals(stream.media().data(),original.data()),"real downstream codec preserves viewer lease");check(!outer.isReadable(),"outer downstream consumes exact packet");}finally{outer.release();}
        }
        for(long bad:new long[]{-1,0,Long.MIN_VALUE}){denied(()->new WatchNetwork.Start(bad,id(1),d));denied(()->new WatchNetwork.Heartbeat(bad,id(1)));denied(()->new WatchNetwork.Release(bad,id(1)));}
        for(int bad:new int[]{-1,9,Integer.MAX_VALUE})denied(()->new WatchNetwork.HostDemand(1,d,bad));
        denied(()->new WatchNetwork.Stop(1,id(1),"x".repeat(129)));denied(()->new WatchNetwork.Stream(null,media(0)));
        denied(()->new WatchDescriptor(d.provider(),id(1),id(2),d.dimension(),anchor,null,List.of()));
        denied(()->new WatchDescriptor(d.provider(),id(1),id(2),d.dimension(),anchor,null,List.of(anchor,anchor)));
        byte[] valid=encode(WatchNetwork.Start.CODEC,new WatchNetwork.Start(1,id(1),dual));
        for(int length=0;length<valid.length;length++){byte[] cut=Arrays.copyOf(valid,length);denied(()->decode(WatchNetwork.Start.CODEC,cut));}
        for(int count:new int[]{-1,0,3,Integer.MAX_VALUE}){
            var invalid=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{invalid.writeVarLong(1);invalid.writeUUID(id(20));invalid.writeUtf(d.provider().toString());invalid.writeUUID(d.source());invalid.writeUUID(d.hostLease());invalid.writeUtf(d.dimension().toString());invalid.writeBlockPos(anchor.pos());invalid.writeUUID(anchor.identity());invalid.writeBoolean(false);invalid.writeVarInt(count);
                byte[] bytes=new byte[invalid.readableBytes()];invalid.readBytes(bytes);denied(()->decode(WatchNetwork.Start.CODEC,bytes));
            }finally{invalid.release();}
        }
        var over=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{over.writeUUID(id(1));over.writeUUID(id(2));over.writeVarLong(0);over.writeVarInt(0);over.writeVarInt(0);over.writeVarInt(1);over.writeVarInt(384);over.writeVarInt(288);over.writeFloat(4F/3);over.writeVarInt(0);over.writeVarInt(221184);over.writeVarInt(24577);over.writeZero(24577);byte[] bytes=new byte[over.readableBytes()];over.readBytes(bytes);denied(()->decode(WatchNetwork.Media.CODEC,bytes));}finally{over.release();}
    }
    private static final class Hold extends ChannelOutboundHandlerAdapter {
        final List<Object> packets=new ArrayList<>();final List<ChannelPromise> promises=new ArrayList<>();
        @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise){packets.add(packet);promises.add(promise);}
        void complete(){for(var promise:promises)promise.trySuccess();promises.clear();packets.clear();}
    }
    private static List<CabinetMediaPacket> batch(int sequence){var all=new ArrayList<CabinetMediaPacket>();for(int i=0;i<6;i++)all.add(new CabinetMediaPacket(id(1),id(2),sequence,0,i,6,384,288,4F/3,0,221184,new byte[i==5?8192:24576]));return List.copyOf(all);}
    private static void windows(){
        for(boolean upstream:new boolean[]{false,true}){
            var hold=new Hold();var c=new Connection(upstream?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND);var channel=new EmbeddedChannel(hold,c);
            try{
                check(upstream?CabinetMediaSender.serverbound(c,batch(1)):CabinetMediaSender.clientbound(c,id(50),batch(1)),"player media gets first admission");channel.runPendingTasks();
                check(CabinetMediaSender.inFlight(c)==132608&&hold.packets.size()==6,"actual player writes remain in flight");
                var otherHold=new Hold();var other=new Connection(upstream?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND);var otherChannel=new EmbeddedChannel(otherHold,other);
                try{check(upstream?CabinetMediaSender.watchServerbound(other,batch(1)):CabinetMediaSender.watchClientbound(other,id(77),batch(1)),"one blocked connection does not hold a separate viewer window");otherChannel.runPendingTasks();check(otherHold.packets.size()==6&&CabinetMediaSender.inFlight(other)==132608,"independent physical connection has its own bounded reservation");otherHold.complete();otherChannel.runPendingTasks();check(CabinetMediaSender.inFlight(c)==132608&&CabinetMediaSender.inFlight(other)==0,"other viewer completion never releases first connection");}
                finally{otherHold.complete();otherChannel.close();otherChannel.runPendingTasks();CabinetMediaSender.release(other);otherChannel.finishAndReleaseAll();}
                for(int i=0;i<512;i++)check(!(upstream?CabinetMediaSender.watchServerbound(c,batch(i+2)):CabinetMediaSender.watchClientbound(c,id(51),batch(i+2))),"spectators share player physical window");
                check(hold.packets.size()==6,"watch rejection never queues partial frame");CabinetMediaSender.release(c);
                check(!(upstream?CabinetMediaSender.watchServerbound(c,batch(999)):CabinetMediaSender.watchClientbound(c,id(52),batch(999))),"watcher replacement cannot reset still-active physical window");
                hold.complete();channel.runPendingTasks();check(CabinetMediaSender.inFlight(c)==0,"actual write completion frees common reservation");
                check(upstream?CabinetMediaSender.watchServerbound(c,batch(1000)):CabinetMediaSender.watchClientbound(c,id(52),batch(1000)),"watch sends after previous write completion");channel.runPendingTasks();
                check(upstream?hold.packets.getFirst() instanceof ServerboundCustomPayloadPacket up&&up.payload() instanceof WatchNetwork.Media:hold.packets.getFirst() instanceof ClientboundCustomPayloadPacket down&&down.payload() instanceof WatchNetwork.Stream s&&s.lease().equals(id(52)),"actual watch wire payload and recipient identity");
                check(!(upstream?CabinetMediaSender.serverbound(c,batch(1001)):CabinetMediaSender.clientbound(c,id(53),batch(1001))),"same window also protects inverse watch-to-player switch");
                check(CabinetMediaSender.inFlight(c)==132608,"no counter reset across route switch");hold.complete();channel.runPendingTasks();
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1,false);
                check(!(upstream?CabinetMediaSender.watchServerbound(c,batch(1002)):CabinetMediaSender.watchClientbound(c,id(52),batch(1002))),"unwritable watch connection drops whole frame");
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1,true);
                check(!(upstream?CabinetMediaSender.watchServerbound(c,batch(1003).subList(0,5)):CabinetMediaSender.watchClientbound(c,id(52),batch(1003).subList(0,5))),"partial caller batch atomically rejected");
                check(CabinetMediaSender.inFlight(c)==0&&hold.packets.isEmpty(),"denials allocate no write budget");
                check(!(upstream?CabinetMediaSender.watchClientbound(c,id(52),batch(1)):CabinetMediaSender.watchServerbound(c,batch(1))),"wrong physical packet direction rejected");
            }finally{hold.complete();channel.close();channel.runPendingTasks();CabinetMediaSender.release(c);channel.finishAndReleaseAll();}
        }
        var window=new CabinetSendWindow();var old=window.reserve(new int[]{1000});var callback=CabinetMediaSender.completion(old,0);
        callback.onSuccess();var next=window.reserve(new int[]{2000});callback.onSuccess();callback.onFailure();check(window.inFlight()==2000,"old completion cannot release a later live ticket");next.complete(0);
        check(!CabinetMediaSender.watchServerbound(new Connection(PacketFlow.CLIENTBOUND),batch(1)),"unconnected watch uploader never queues pre-login send");
    }
    private static void ingress()throws Exception{
        var old=new Connection(PacketFlow.CLIENTBOUND);var next=new Connection(PacketFlow.CLIENTBOUND);var oldChannel=new EmbeddedChannel(old);var nextChannel=new EmbeddedChannel(next);
        try{
            AtomicReference<Connection> current=new AtomicReference<>(old),source=new AtomicReference<>(old);var queue=new ArrayList<Runnable>();var delivered=new AtomicInteger();
            WatchNetwork.ClientSink sink=(WatchNetwork.ClientSink)Proxy.newProxyInstance(WatchNetwork.ClientSink.class.getClassLoader(),new Class<?>[]{WatchNetwork.ClientSink.class},(p,m,a)->{if(m.getName().equals("acceptsConnection"))return a[0]!=null&&a[0]==current.get()&&((Connection)a[0]).isConnected();throw new AssertionError(m);});
            WatchNetwork.setClientSink(sink);var dispatch=WatchNetwork.class.getDeclaredMethod("dispatch",IPayloadContext.class,Consumer.class);dispatch.setAccessible(true);
            var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{if(m.getName().equals("connection"))return source.get();if(m.getName().equals("enqueueWork")&&a[0] instanceof Runnable work){queue.add(work);return CompletableFuture.completedFuture(null);}throw new AssertionError(m);});
            Consumer<WatchNetwork.ClientSink> action=s->{check(s==sink,"current registered sink used");delivered.incrementAndGet();};
            dispatch.invoke(null,context,action);check(delivered.get()==0,"work enqueued, not applied on network thread");queue.removeFirst().run();check(delivered.get()==1,"live source delivered");
            dispatch.invoke(null,context,action);current.set(next);queue.removeFirst().run();check(delivered.get()==1,"old queued connection cannot mutate new client session");
            source.set(next);dispatch.invoke(null,context,action);source.set(old);queue.removeFirst().run();check(delivered.get()==2,"source Connection captured before enqueue");
            source.set(null);dispatch.invoke(null,context,action);queue.removeFirst().run();check(delivered.get()==2,"null source ignored");
            source.set(next);dispatch.invoke(null,context,action);nextChannel.close();nextChannel.runPendingTasks();queue.removeFirst().run();check(delivered.get()==2,"disconnected same-reference source ignored by live sink contract");
        }finally{oldChannel.close();nextChannel.close();oldChannel.finishAndReleaseAll();nextChannel.finishAndReleaseAll();}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("expected production origin and final-jar-only|compiled-workspace-production");var output=System.out;Path expected=Path.of(args[0]).toRealPath();
        for(var type:List.of(WatchNetwork.class,WatchNetwork.Start.class,WatchNetwork.Stream.class,WatchLedger.class,WatchBudget.class,WatchDescriptor.class,WatchAnchor.class,CabinetMediaSender.class,CabinetSendWindow.class,CabinetMediaCodec.class,CabinetMediaPacket.class,CabinetRoomMedia.class,cn.piq.retro.api.RetroFrame.class,cn.piq.fcarcade.client.cabinet.WatchMediaStream.class,cn.piq.fcarcade.client.watch.WatchLeaseState.class))check(origin(type).equals(expected),"all production comes from explicit candidate origin: "+type.getName());
        for(String name:List.of("CabinetMediaStream","CabinetMediaAssembler","CabinetPcmBuffer")){var type=Class.forName("cn.piq.fcarcade.client.cabinet."+name);check(origin(type).equals(expected),"package-private media worker origin: "+name);}
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        registration();codecs();windows();ingress();
        output.println("{\"ok\":true,\"assertions\":"+checks+",\"max_serverbound_outer_bytes\":"+maxOuterBytes+",\"actual_production_registration\":true,\"actual_neoforge_outer_codec\":true,\"actual_connection_write_completion\":true,\"production_origin\":\""+args[1]+"\",\"minecraft_or_native_core_started\":false,\"network_socket_opened\":false,\"server_world_authority_simulated\":false}");
    }
}
