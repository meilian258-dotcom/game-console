package cn.piq.sfchome.net;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import io.netty.buffer.Unpooled;

/** Actual production ingress dispatch, real Minecraft Connection objects and real codecs. No socket/world. */
public final class SfcClientIngressProbe {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static <T>byte[] encode(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(b,value);byte[] out=new byte[b.readableBytes()];b.readBytes(out);return out;}finally{b.release();}
    }
    private static <T>T decode(StreamCodec<RegistryFriendlyByteBuf,T> codec,byte[] bytes){
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);
        try{T value=codec.decode(b);check(!b.isReadable(),"codec consumes exactly one payload");return value;}finally{b.release();}
    }
    private static void dispatch(Class<?> network,Class<?> handlerType,String setter)throws Exception{
        Connection old=new Connection(PacketFlow.CLIENTBOUND),next=new Connection(PacketFlow.CLIENTBOUND);
        AtomicReference<Connection> current=new AtomicReference<>(old);List<Runnable> queue=new ArrayList<>();AtomicInteger delivered=new AtomicInteger();
        Object handler=Proxy.newProxyInstance(handlerType.getClassLoader(),new Class<?>[]{handlerType},(p,m,a)->{
            if(m.getName().equals("acceptsConnection"))return a[0]!=null&&a[0]==current.get();
            throw new AssertionError("Unexpected handler method "+m);
        });
        network.getMethod(setter,handlerType).invoke(null,handler);
        var ingress=network.getDeclaredMethod("dispatch",IPayloadContext.class,Consumer.class);ingress.setAccessible(true);
        AtomicReference<Connection> source=new AtomicReference<>(old);
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{
            if(m.getName().equals("connection"))return source.get();
            if(m.getName().equals("enqueueWork")&&a[0] instanceof Runnable work){queue.add(work);return CompletableFuture.completedFuture(null);}
            throw new AssertionError("Unexpected payload context method "+m);
        });
        Consumer<Object> deliver=h->{check(h==handler,"current registered handler");delivered.incrementAndGet();};
        ingress.invoke(null,context,deliver);check(queue.size()==1&&delivered.get()==0,"dispatch queues before consumption");queue.removeFirst().run();check(delivered.get()==1,"live source delivered");
        ingress.invoke(null,context,deliver);current.set(next);queue.removeFirst().run();check(delivered.get()==1,"queued old source rejected after server switch");
        source.set(next);ingress.invoke(null,context,deliver);source.set(old);queue.removeFirst().run();check(delivered.get()==2,"source connection captured before queue, not reread later");
        source.set(null);ingress.invoke(null,context,deliver);queue.removeFirst().run();check(delivered.get()==2,"null source rejected");
        current.set(null);source.set(next);ingress.invoke(null,context,deliver);queue.removeFirst().run();check(delivered.get()==2,"disconnected client rejects queue");
    }
    public static void main(String[] args)throws Exception{
        dispatch(SfcHomeNetwork.class,SfcHomeNetwork.ClientHandler.class,"setClientHandler");
        dispatch(SfcJoinNetwork.class,SfcJoinNetwork.Client.class,"client");
        UUID lease=new UUID(0x1234,0x9876);String sha="a".repeat(64);
        var ready=new SfcHomeNetwork.Ready(2,1,sha,SfcHomeNetwork.CORE_BUILD,60.0,sha);
        var leave=new SfcHomeNetwork.Leave(2,1);
        var boundReady=new SfcJoinNetwork.ControllerReady(lease,ready);var boundLeave=new SfcJoinNetwork.ControllerLeave(lease,leave);
        byte[] readyBytes=encode(SfcJoinNetwork.ControllerReady.CODEC,boundReady),leaveBytes=encode(SfcJoinNetwork.ControllerLeave.CODEC,boundLeave);
        check(decode(SfcJoinNetwork.ControllerReady.CODEC,readyBytes).equals(boundReady),"Ready lease and body roundtrip");
        check(decode(SfcJoinNetwork.ControllerLeave.CODEC,leaveBytes).equals(boundLeave),"Leave lease and body roundtrip");
        check(Arrays.equals(Arrays.copyOfRange(readyBytes,16,readyBytes.length),encode(SfcHomeNetwork.Ready.CODEC,ready)),"Ready inner codec preserved exactly");
        check(Arrays.equals(Arrays.copyOfRange(leaveBytes,16,leaveBytes.length),encode(SfcHomeNetwork.Leave.CODEC,leave)),"Leave inner codec preserved exactly");
        check(!Arrays.equals(readyBytes,encode(SfcJoinNetwork.ControllerReady.CODEC,new SfcJoinNetwork.ControllerReady(new UUID(0,3),ready))),"lease changes encoded identity");
        for(Runnable invalid:List.<Runnable>of(()->new SfcJoinNetwork.ControllerReady(null,ready),()->new SfcJoinNetwork.ControllerReady(lease,null),()->new SfcJoinNetwork.ControllerLeave(null,leave),()->new SfcJoinNetwork.ControllerLeave(lease,null))){
            boolean rejected=false;try{invalid.run();}catch(NullPointerException expected){rejected=true;}check(rejected,"missing lease/body refused");
        }
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"real_connection_and_context_api\":true,\"minecraft_started\":false,\"network_socket_opened\":false,\"native_core_started\":false}");
    }
}
