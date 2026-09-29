package cn.piq.fcarcade.cabinet;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.junit.jupiter.api.Test;

/** Actual final classes and outer codecs. No Minecraft instance, world, socket or core. */
public final class CabinetJoin24FinalProbe {
    private static int checks,maxOuter,tests;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static UUID id(long n){return new UUID(0,n);}
    private static void denied(Runnable action){boolean failed=false;try{action.run();}catch(RuntimeException expected){failed=true;}check(failed,"invalid/truncated payload rejected");}
    private static byte[] outer(CustomPacketPayload value,boolean upstream){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{
            if(upstream)ServerboundCustomPayloadPacket.STREAM_CODEC.encode(b,new ServerboundCustomPayloadPacket(value));
            else ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(value));
            maxOuter=Math.max(maxOuter,b.readableBytes());byte[] data=new byte[b.readableBytes()];b.readBytes(data);return data;
        }finally{b.release();}
    }
    private static CustomPacketPayload decode(byte[] data,boolean upstream){
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data),RegistryAccess.EMPTY);
        try{var value=upstream?ServerboundCustomPayloadPacket.STREAM_CODEC.decode(b).payload():ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b).payload();check(!b.isReadable(),"outer packet fully consumed");return value;}finally{b.release();}
    }
    private static void codec(){
        CabinetJoinNetwork.register(new RegisterPayloadHandlersEvent());
        for(var type:List.of(CabinetJoinNetwork.Allow.TYPE,CabinetJoinNetwork.Decision.TYPE)){
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)!=null,"only client consent upstream");
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)==null,"upstream cannot arrive as server assignment");
        }
        for(var type:List.of(CabinetJoinNetwork.Offer.TYPE,CabinetJoinNetwork.Approval.TYPE,CabinetJoinNetwork.Result.TYPE)){
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.CLIENTBOUND)!=null,"only server prompt/result downstream");
            check(NetworkRegistry.getCodec(type.id(),ConnectionProtocol.PLAY,PacketFlow.SERVERBOUND)==null,"client cannot forge approval prompt or result");
        }
        var values=new ArrayList<CustomPacketPayload>();values.add(new CabinetJoinNetwork.Offer(id(1),id(2),id(3)));
        for(boolean yes:new boolean[]{false,true}){values.add(new CabinetJoinNetwork.Allow(id(1),id(2),id(3),yes));values.add(new CabinetJoinNetwork.Decision(id(1),id(2),id(3),yes));}
        for(int port=1;port<4;port++)values.add(new CabinetJoinNetwork.Approval(id(1),id(2),id(3),id(4),"玩".repeat(64),port));
        values.add(new CabinetJoinNetwork.Result(id(1),id(3),"申".repeat(160)));
        for(var value:values){
            boolean up=value instanceof CabinetJoinNetwork.Allow||value instanceof CabinetJoinNetwork.Decision;
            byte[] bytes=outer(value,up);check(bytes.length<32767,"actual complete outer packet below cap");
            check(value.equals(decode(bytes,up)),"every UUID/choice/name/seat roundtrips exactly");
            for(int n=0;n<bytes.length;n++){byte[] shortPacket=Arrays.copyOf(bytes,n);denied(()->decode(shortPacket,up));}
        }
        for(int port:new int[]{-1,0,4,Integer.MAX_VALUE})denied(()->new CabinetJoinNetwork.Approval(id(1),id(2),id(3),id(4),"P2",port));
        for(String text:new String[]{"a".repeat(65),"bad\nname","bad\u0000name"})denied(()->new CabinetJoinNetwork.Approval(id(1),id(2),id(3),id(4),text,1));
        denied(()->new CabinetJoinNetwork.Result(id(1),id(2),"x".repeat(161)));
        denied(()->new CabinetJoinNetwork.Result(id(1),id(2),"a\rb"));
        denied(()->new CabinetJoinNetwork.Allow(null,id(2),id(3),true));
        denied(()->new CabinetJoinNetwork.Decision(id(1),null,id(3),true));
    }
    private static void ingress()throws Exception{
        var old=new Connection(PacketFlow.CLIENTBOUND);var next=new Connection(PacketFlow.CLIENTBOUND);var oldChannel=new EmbeddedChannel(old);var nextChannel=new EmbeddedChannel(next);
        try{
            var current=new AtomicReference<Connection>(old);var source=new AtomicReference<Connection>(old);var queued=new ArrayList<Runnable>();var delivered=new AtomicInteger();
            var sink=(CabinetJoinNetwork.ClientSink)Proxy.newProxyInstance(CabinetJoinNetwork.ClientSink.class.getClassLoader(),new Class<?>[]{CabinetJoinNetwork.ClientSink.class},(p,m,a)->{
                if(m.getName().equals("acceptsConnection"))return a[0]==current.get()&&((Connection)a[0]).isConnected();throw new AssertionError(m);
            });
            CabinetJoinNetwork.setClientSink(sink);
            var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{
                if(m.getName().equals("connection"))return source.get();
                if(m.getName().equals("enqueueWork")&&a[0]instanceof Runnable task){queued.add(task);return CompletableFuture.completedFuture(null);}throw new AssertionError(m);
            });
            var client=CabinetJoinNetwork.class.getDeclaredMethod("client",IPayloadContext.class,Consumer.class);client.setAccessible(true);
            Consumer<CabinetJoinNetwork.ClientSink> action=s->{check(s==sink,"current registered sink");delivered.incrementAndGet();};
            client.invoke(null,context,action);check(delivered.get()==0,"network callback enqueues instead of applying");queued.removeFirst().run();check(delivered.get()==1,"current live connection accepted");
            client.invoke(null,context,action);current.set(next);queued.removeFirst().run();check(delivered.get()==1,"old connection callback cannot mutate replacement session");
            source.set(next);client.invoke(null,context,action);source.set(old);queued.removeFirst().run();check(delivered.get()==2,"source captured before scheduling");
            source.set(null);client.invoke(null,context,action);queued.removeFirst().run();check(delivered.get()==2,"null source ignored");
            source.set(next);client.invoke(null,context,action);nextChannel.close();nextChannel.runPendingTasks();queued.removeFirst().run();check(delivered.get()==2,"same-reference closed channel rejected");
        }finally{oldChannel.close();nextChannel.close();oldChannel.finishAndReleaseAll();nextChannel.finishAndReleaseAll();}
    }
    private static void pureTests()throws Exception{
        for(String name:List.of("cn.piq.fcarcade.cabinet.CabinetJoinGateTest","cn.piq.fcarcade.client.cabinet.CabinetPromptGuardTest","cn.piq.fcarcade.client.cabinet.CabinetImmersiveInputTest","cn.piq.fcarcade.client.cabinet.CabinetInputLifecycleTest")){
            var type=Class.forName(name);var ctor=type.getDeclaredConstructor();ctor.setAccessible(true);
            for(var test:type.getDeclaredMethods())if(test.isAnnotationPresent(Test.class)){
                // Match JUnit's default PER_METHOD isolation (Gate tests have mutable instance fields).
                var instance=ctor.newInstance();test.setAccessible(true);
                try{test.invoke(instance);}catch(java.lang.reflect.InvocationTargetException failure){throw new AssertionError(name+"."+test.getName(),failure.getCause());}tests++;
            }
        }
        check(tests>=37,"all actual consent and keyboard regressions executed");
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;var expected=Path.of(args[0]).toRealPath();check(expected.toString().endsWith(".jar"),"explicit final jar");
        for(String name:List.of("cabinet.CabinetJoinNetwork","cabinet.CabinetJoinNetwork$Offer","cabinet.CabinetJoinNetwork$Allow","cabinet.CabinetJoinNetwork$Approval","cabinet.CabinetJoinNetwork$Decision","cabinet.CabinetJoinNetwork$Result","cabinet.CabinetJoinGate","cabinet.CabinetRoomLedger","client.cabinet.CabinetPromptGuard","client.cabinet.CabinetImmersiveInput")){
            var type=Class.forName("cn.piq.fcarcade."+name,false,CabinetJoin24FinalProbe.class.getClassLoader());
            check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"production class from final jar: "+name);
        }
        pureTests();net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        codec();ingress();
        output.println("{\"ok\":true,\"assertions\":"+checks+",\"pure_tests\":"+tests+",\"max_outer_bytes\":"+maxOuter+",\"actual_production_registration\":true,\"actual_neoforge_outer_codec\":true,\"actual_client_dispatch_with_contract_sink\":true,\"production_origin\":\"final-jar-only\",\"production_compiled\":false,\"minecraft_or_core_started\":false,\"network_socket_opened\":false,\"real_server_world_authority_executed\":false}");
    }
}
