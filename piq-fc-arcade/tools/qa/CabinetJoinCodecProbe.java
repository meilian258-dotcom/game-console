package cn.piq.fcarcade.cabinet;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Actual MC buffers and queued Connection identities; no world, player fabrication or socket. */
public final class CabinetJoinCodecProbe {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static <T> void roundtrip(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{codec.encode(buffer,value);check(buffer.readableBytes()<600,"small bounded consent body");
            check(value.equals(codec.decode(buffer)),"all fields retained");check(!buffer.isReadable(),"no unread body");}
        finally{buffer.release();}
    }
    private static void denied(Runnable action){boolean failed=false;
        try{action.run();}catch(IllegalArgumentException|NullPointerException|io.netty.handler.codec.DecoderException expected){failed=true;}
        check(failed,"malformed packet rejected");}
    private static void connections()throws Exception{
        var old=new Connection(PacketFlow.CLIENTBOUND);var next=new Connection(PacketFlow.CLIENTBOUND);
        var current=new AtomicReference<Connection>(old);var source=new AtomicReference<Connection>(old);
        var calls=new AtomicInteger();List<Runnable> tasks=new ArrayList<>();
        CabinetJoinNetwork.setClientSink(new CabinetJoinNetwork.ClientSink(){
            public boolean acceptsConnection(Connection value){return value==current.get();}
            public void offer(CabinetJoinNetwork.Offer p){calls.incrementAndGet();}
            public void approval(CabinetJoinNetwork.Approval p){throw new AssertionError();}
            public void result(CabinetJoinNetwork.Result p){throw new AssertionError();}
        });
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(p,m,a)->{
            if(m.getName().equals("connection"))return source.get();
            if(m.getName().equals("enqueueWork")&&a[0] instanceof Runnable work){tasks.add(work);return CompletableFuture.completedFuture(null);}
            throw new AssertionError("Unexpected API "+m);
        });
        var dispatch=CabinetJoinNetwork.class.getDeclaredMethod("client",IPayloadContext.class,Consumer.class);dispatch.setAccessible(true);
        var offer=new CabinetJoinNetwork.Offer(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        Consumer<CabinetJoinNetwork.ClientSink> action=s->s.offer(offer);
        dispatch.invoke(null,context,action);check(calls.get()==0,"always queued");tasks.removeFirst().run();check(calls.get()==1,"current connection delivered");
        dispatch.invoke(null,context,action);current.set(next);tasks.removeFirst().run();check(calls.get()==1,"late old connection rejected");
        source.set(next);dispatch.invoke(null,context,action);source.set(old);tasks.removeFirst().run();check(calls.get()==2,"source captured before enqueue");
        source.set(null);dispatch.invoke(null,context,action);tasks.removeFirst().run();check(calls.get()==2,"null source rejected");
        source.set(next);dispatch.invoke(null,context,action);current.set(null);tasks.removeFirst().run();check(calls.get()==2,"disconnect before delivery rejected");
    }
    public static void main(String[] args)throws Exception{
        var room=UUID.randomUUID();var host=UUID.randomUUID();var token=UUID.randomUUID();var applicant=UUID.randomUUID();
        roundtrip(CabinetJoinNetwork.Offer.CODEC,new CabinetJoinNetwork.Offer(room,host,token));
        for(boolean value:new boolean[]{true,false}){
            roundtrip(CabinetJoinNetwork.Allow.CODEC,new CabinetJoinNetwork.Allow(room,host,token,value));
            roundtrip(CabinetJoinNetwork.Decision.CODEC,new CabinetJoinNetwork.Decision(room,host,token,value));
        }
        for(int port=1;port<=3;port++)roundtrip(CabinetJoinNetwork.Approval.CODEC,new CabinetJoinNetwork.Approval(room,host,token,applicant,"玩家".repeat(32),port));
        roundtrip(CabinetJoinNetwork.Result.CODEC,new CabinetJoinNetwork.Result(room,token,"结果".repeat(80)));
        denied(()->new CabinetJoinNetwork.Offer(null,host,token));
        denied(()->new CabinetJoinNetwork.Allow(room,null,token,true));
        denied(()->new CabinetJoinNetwork.Decision(room,host,null,true));
        denied(()->new CabinetJoinNetwork.Approval(room,host,token,applicant,"x".repeat(65),1));
        denied(()->new CabinetJoinNetwork.Approval(room,host,token,applicant,"\n",1));
        denied(()->new CabinetJoinNetwork.Approval(room,host,token,applicant,"player",0));
        denied(()->new CabinetJoinNetwork.Approval(room,host,token,applicant,"player",4));
        denied(()->new CabinetJoinNetwork.Result(room,token,"x".repeat(161)));
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{buffer.writeUUID(room);buffer.writeUUID(host);buffer.writeUUID(token);buffer.writeUUID(applicant);buffer.writeUtf("x".repeat(65));buffer.writeVarInt(1);
            denied(()->CabinetJoinNetwork.Approval.CODEC.decode(buffer));}finally{buffer.release();}
        connections();
        System.out.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_minecraft_codecs\":true,\"actual_queued_connection_guard\":true,\"minecraft_started\":false,\"network_socket_opened\":false,\"server_world_authority_simulated\":false}");
    }
}
