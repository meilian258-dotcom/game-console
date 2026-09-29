package cn.piq.fcarcade.cabinet;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import io.netty.buffer.Unpooled;

/** Real Minecraft codecs, Connection identity and queued production dispatch; no socket or world. */
public final class CabinetRoomIngressProbe {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static <T>byte[] encode(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(b,value);byte[] out=new byte[b.readableBytes()];b.readBytes(out);return out;}finally{b.release();}
    }
    private static <T>T decode(StreamCodec<RegistryFriendlyByteBuf,T> codec,byte[] bytes){
        var b=new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes),RegistryAccess.EMPTY);
        try{T value=codec.decode(b);check(!b.isReadable(),"codec consumes one bounded body");return value;}finally{b.release();}
    }
    private static <T>void roundtrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){check(value.equals(decode(codec,encode(codec,value))),"record fields roundtrip");}
    private static void denied(Runnable action){boolean failed=false;try{action.run();}catch(IllegalArgumentException|NullPointerException|io.netty.handler.codec.DecoderException expected){failed=true;}check(failed,"invalid bounded packet rejected");}
    private static void dispatch(Class<?> network,Class<?> sinkType)throws Exception{
        Connection old=new Connection(PacketFlow.CLIENTBOUND),next=new Connection(PacketFlow.CLIENTBOUND);
        AtomicReference<Connection> current=new AtomicReference<>(old),source=new AtomicReference<>(old);
        List<Runnable> queue=new ArrayList<>();AtomicInteger delivered=new AtomicInteger();
        Object sink=Proxy.newProxyInstance(sinkType.getClassLoader(),new Class<?>[]{sinkType},(p,m,a)->{
            if(m.getName().equals("acceptsConnection"))return a[0]!=null&&a[0]==current.get();
            throw new AssertionError("Unexpected sink call "+m);
        });
        network.getMethod("setClientSink",sinkType).invoke(null,sink);
        var ingress=network.getDeclaredMethod("dispatch",IPayloadContext.class,Consumer.class);ingress.setAccessible(true);
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{
            if(m.getName().equals("connection"))return source.get();
            if(m.getName().equals("enqueueWork")&&a[0] instanceof Runnable work){queue.add(work);return CompletableFuture.completedFuture(null);}
            throw new AssertionError("Unexpected context call "+m);
        });
        Consumer<Object> action=target->{check(target==sink,"current sink used");delivered.incrementAndGet();};
        ingress.invoke(null,context,action);check(delivered.get()==0&&queue.size()==1,"queued not inline");queue.removeFirst().run();check(delivered.get()==1,"live source delivered");
        ingress.invoke(null,context,action);current.set(next);queue.removeFirst().run();check(delivered.get()==1,"old queued connection rejected");
        source.set(next);ingress.invoke(null,context,action);source.set(old);queue.removeFirst().run();check(delivered.get()==2,"source captured before queue");
        source.set(null);ingress.invoke(null,context,action);queue.removeFirst().run();check(delivered.get()==2,"null source rejected");
        current.set(null);source.set(next);ingress.invoke(null,context,action);queue.removeFirst().run();check(delivered.get()==2,"disconnected queue rejected");
    }
    public static void main(String[] args)throws Exception{
        dispatch(CabinetNetwork.class,CabinetNetwork.ClientSink.class);
        dispatch(CabinetRoomNetwork.class,CabinetRoomNetwork.ClientSink.class);
        UUID room=new UUID(1,2),host=room,guest=new UUID(3,4);
        var dim=ResourceLocation.parse("minecraft:overworld");var backend=ResourceLocation.parse("piq_native_arcade:mame");
        var primary=new CabinetTarget(dim,new BlockPos(1,64,2),new UUID(5,6),true);
        var secondary=new CabinetTarget(dim,new BlockPos(8,64,2),new UUID(7,8),true);
        roundtrip(CabinetRoomNetwork.Assignment.CODEC,new CabinetRoomNetwork.Assignment(room,host,host,0,2,primary,backend,primary,null));
        roundtrip(CabinetRoomNetwork.Assignment.CODEC,new CabinetRoomNetwork.Assignment(room,guest,host,3,4,secondary,backend,primary,secondary));
        roundtrip(CabinetRoomNetwork.Seat.CODEC,new CabinetRoomNetwork.Seat(room,host,guest,3,true));
        roundtrip(CabinetRoomNetwork.Ready.CODEC,new CabinetRoomNetwork.Ready(room,host));
        roundtrip(CabinetRoomNetwork.Reset.CODEC,new CabinetRoomNetwork.Reset(room,guest,7));
        roundtrip(CabinetRoomNetwork.Input.CODEC,new CabinetRoomNetwork.Input(room,guest,Long.MAX_VALUE,4095));
        roundtrip(CabinetRoomNetwork.Buttons.CODEC,new CabinetRoomNetwork.Buttons(room,host,guest,3,7,0,true));
        for(int kind=0;kind<2;kind++){
            byte[] data=new byte[kind==0?24576:19200];new Random(7).nextBytes(data);
            var original=new CabinetRoomNetwork.Media(room,host,kind==0?9:9600,kind,0,1,kind==0?384:0,kind==0?288:0,kind==0?4F/3:1,0,kind==0?221184:19200,data);
            var copy=decode(CabinetRoomNetwork.Media.CODEC,encode(CabinetRoomNetwork.Media.CODEC,original));
            check(copy.room().equals(room)&&copy.hostMember().equals(host)&&copy.sequence()==original.sequence()&&Arrays.equals(copy.data(),data),"media header and bytes roundtrip");
            var stream=decode(CabinetRoomNetwork.Stream.CODEC,encode(CabinetRoomNetwork.Stream.CODEC,new CabinetRoomNetwork.Stream(guest,original)));
            check(stream.member().equals(guest)&&Arrays.equals(stream.media().data(),data),"stream exact recipient lease");
            check(encode(CabinetRoomNetwork.Stream.CODEC,stream).length<25000,"actual downstream body below fragment envelope bound");
        }
        denied(()->new CabinetRoomNetwork.Assignment(room,guest,host,2,2,secondary,backend,primary,null));
        denied(()->new CabinetRoomNetwork.Assignment(room,guest,host,2,4,primary,backend,primary,secondary));
        denied(()->new CabinetRoomNetwork.Assignment(room,host,host,0,4,primary,backend,primary,null));
        denied(()->new CabinetRoomNetwork.Assignment(room,guest,host,1,4,primary,backend,primary,primary));
        denied(()->new CabinetRoomNetwork.Assignment(room,guest,host,1,4,primary,backend,primary,new CabinetTarget(dim,new BlockPos(8,64,2),UUID.randomUUID(),false)));
        denied(()->new CabinetRoomNetwork.Input(room,guest,-1,0));
        denied(()->new CabinetRoomNetwork.Reset(room,guest,-1));
        denied(()->new CabinetRoomNetwork.Input(room,guest,0,4096));
        denied(()->new CabinetRoomNetwork.Buttons(room,host,guest,3,1,1,true));
        denied(()->new CabinetRoomNetwork.Seat(room,host,guest,4,true));
        denied(()->new CabinetRoomNetwork.Stream(null,new CabinetRoomNetwork.Media(room,host,0,1,0,1,0,0,1,0,4,new byte[4])));
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{buf.writeUUID(room);buf.writeUUID(host);buf.writeVarLong(0);buf.writeVarInt(0);buf.writeVarInt(0);buf.writeVarInt(1);buf.writeVarInt(384);buf.writeVarInt(288);buf.writeFloat(4F/3);buf.writeVarInt(0);buf.writeVarInt(221184);buf.writeVarInt(24577);buf.writeZero(24577);
            byte[] bad=new byte[buf.readableBytes()];buf.readBytes(bad);denied(()->decode(CabinetRoomNetwork.Media.CODEC,bad));
        }finally{buf.release();}
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"real_connection_and_context_api\":true,\"minecraft_started\":false,\"network_socket_opened\":false,\"native_core_started\":false}");
    }
}
