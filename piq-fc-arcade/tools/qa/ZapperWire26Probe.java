import cn.piq.fcarcade.*;
import cn.piq.fcarcade.home.ZapperBinding;
import cn.piq.fcarcade.session.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import net.minecraft.core.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Actual registered wire and queued handler, with a contract sink rather than a game client. */
public final class ZapperWire26Probe {
    static int assertions,maxBytes;
    static void check(boolean value,String text){assertions++;if(!value)throw new AssertionError(text);}
    static void denied(Runnable r){boolean rejected=false;try{r.run();}catch(RuntimeException e){rejected=true;}check(rejected,"truncated wire refused");}
    static byte[] encode(CustomPacketPayload payload,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
        if(up)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(payload));else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(payload));
        byte[] out=new byte[b.readableBytes()];b.readBytes(out);maxBytes=Math.max(maxBytes,out.length);return out;
    }finally{b.release();}}
    static CustomPacketPayload decode(byte[] bytes,boolean up){var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);try{
        var p=up?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"fully consumed");return p;
    }finally{b.release();}}
    static void ingress(ZapperBinding binding)throws Exception{
        var old=new Connection(PacketFlow.CLIENTBOUND);var next=new Connection(PacketFlow.CLIENTBOUND);var oldChannel=new EmbeddedChannel(old);var nextChannel=new EmbeddedChannel(next);
        try{var current=new AtomicReference<>(old);var source=new AtomicReference<>(old);var tasks=new ArrayList<Runnable>();var received=new AtomicInteger();
            FcNetwork.setZapperSink(new FcNetwork.ZapperSink(){public boolean acceptsConnection(Connection c){return c==current.get()&&c.isConnected();}public void start(ZapperBinding b){check(b.equals(binding),"exact binding delivered");received.incrementAndGet();}public void stop(UUID id){received.incrementAndGet();}});
            var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{
                if(m.getName().equals("connection"))return source.get();if(m.getName().equals("enqueueWork")&&a[0] instanceof Runnable r){tasks.add(r);return CompletableFuture.completedFuture(null);}throw new AssertionError(m);});
            var handlers=Arrays.stream(FcNetwork.class.getDeclaredMethods()).filter(m->Arrays.equals(m.getParameterTypes(),new Class<?>[]{ArcadeZapperSessionPayload.class,IPayloadContext.class})).toList();
            check(handlers.size()==1,"one production registered gun client handler");var handler=handlers.getFirst();handler.setAccessible(true);var p=new ArcadeZapperSessionPayload(binding,true);
            handler.invoke(null,p,context);check(received.get()==0,"handler queues game-thread application");tasks.removeFirst().run();check(received.get()==1,"current source accepted");
            handler.invoke(null,p,context);current.set(next);tasks.removeFirst().run();check(received.get()==1,"old source cannot affect replacement connection");
            source.set(next);handler.invoke(null,p,context);source.set(old);tasks.removeFirst().run();check(received.get()==2,"source captured before enqueue");
            source.set(next);handler.invoke(null,p,context);nextChannel.close();nextChannel.runPendingTasks();tasks.removeFirst().run();check(received.get()==2,"closed same-reference source rejected");
        }finally{oldChannel.close();nextChannel.close();oldChannel.finishAndReleaseAll();nextChannel.finishAndReleaseAll();}
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;var jar=Path.of(args[0]).toRealPath();
        for(var c:List.of(FcNetwork.class,ArcadeSessionPayload.class,ArcadeFramePayload.class,ArcadeHistoryPayload.class,ArcadeZapperInputPayload.class,ArcadeZapperSessionPayload.class,ZapperBinding.class))
            check(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(jar),"final production origin");
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        FcNetwork.register(new RegisterPayloadHandlersEvent());
        check(NetworkRegistry.getCodec(ArcadeZapperInputPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"gun input registered upstream");
        check(NetworkRegistry.getCodec(ArcadeZapperInputPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"server cannot grant with input payload");
        check(NetworkRegistry.getCodec(ArcadeZapperSessionPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"gun grant registered downstream");
        check(NetworkRegistry.getCodec(ArcadeZapperSessionPayload.TYPE.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot mint grant");
        var binding=new ZapperBinding(3,2,UUID.randomUUID(),ResourceLocation.parse("minecraft:overworld"),new BlockPos(1,2,3),UUID.randomUUID(),new BlockPos(3,2,3),UUID.randomUUID(),UUID.randomUUID());
        denied(()->new ArcadeZapperInputPayload(1,1,binding.lease(),0,256,0,false,false,false));
        denied(()->new ArcadeZapperInputPayload(1,1,binding.lease(),0,0,240,false,false,false));
        denied(()->new ArcadeZapperInputPayload(1,1,binding.lease(),0,1,0,true,false,false));
        denied(()->new ArcadeZapperInputPayload(1,1,binding.lease(),0,0,0,true,true,true));
        denied(()->new ArcadeZapperInputPayload(1,0,binding.lease(),0,0,0,true,false,false));
        var values=new ArrayList<CustomPacketPayload>();
        for(boolean pressed:new boolean[]{true,false})values.add(new ArcadeZapperInputPayload(3,2,binding.lease(),4,128,120,false,pressed,false));
        values.add(new ArcadeZapperInputPayload(3,2,binding.lease(),5,0,0,true,false,true));values.add(new ArcadeZapperSessionPayload(binding,true));values.add(new ArcadeZapperSessionPayload(binding,false));
        for(var variant:NesCoreVariant.values())values.add(new ArcadeSessionPayload(binding.tvPos(),3,ArcadeMode.LOCKSTEP,ArcadeRole.PLAYER_ONE,1,"P1",16,16,75,"00".repeat(32),2,true,true,variant));
        values.add(new ArcadeFramePayload(3,2,90,1,0,ZapperInput.pack(128,120,false,true)));
        var runs=new ArrayList<LockstepInputRun>();for(int i=0;i<3600;i++)runs.add(new LockstepInputRun(1,0,0,ZapperInput.pack(i%256,(i/256)%240,false,(i&1)==0)));
        var fullHistory=new ArcadeHistoryPayload(3,2,0,runs);values.add(new ArcadeHistoryPayload(3,2,0,runs.subList(0,3)));
        for(var p:values){boolean up=p instanceof ArcadeZapperInputPayload;byte[] data=encode(p,up);check(p.equals(decode(data,up)),"all payload fields roundtrip");for(int n=0;n<data.length;n++){byte[] cut=Arrays.copyOf(data,n);denied(()->decode(cut,up));}}
        byte[] full=encode(fullHistory,false);check(full.length<32767,"worst 3600 changing-gun history fits complete packet budget");check(fullHistory.equals(decode(full,false)),"full retained gun history roundtrip");
        ingress(binding);
        output.println("{\"ok\":true,\"assertions\":"+assertions+",\"max_outer_bytes\":"+maxBytes+",\"actual_registered_outer_codecs\":true,\"actual_queued_connection_handler\":true,\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"server_world_authority_executed\":false,\"network_socket_opened\":false}");
    }
}
