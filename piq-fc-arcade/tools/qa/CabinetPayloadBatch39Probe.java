package cn.piq.fcarcade.cabinet;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Real Connection/EmbeddedChannel promises. Explicit subclass throw hooks exercise uncertain sends. */
public final class CabinetPayloadBatch39Probe {
    private static int assertions;
    private static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
    private record Payload(int index) implements CustomPacketPayload {
        private static final Type<Payload> TYPE=new Type<>(ResourceLocation.parse("batch39:fixture"));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final class Hold extends ChannelOutboundHandlerAdapter {
        final List<Object> packets=new ArrayList<>();final List<ChannelPromise> promises=new ArrayList<>();
        @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise){packets.add(packet);promises.add(promise);}
        void complete(){for(var promise:List.copyOf(promises))promise.trySuccess();promises.clear();packets.clear();}
    }
    private static final class Fixture implements AutoCloseable {
        final Connection connection;final Hold hold=new Hold();final EmbeddedChannel channel;
        Fixture(boolean up){this(new Connection(up?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND));}
        Fixture(Connection connection){this.connection=connection;channel=new EmbeddedChannel(hold,connection);}
        void drain(){channel.runPendingTasks();}
        @Override public void close(){hold.complete();channel.close();drain();CabinetMediaSender.release(connection);channel.finishAndReleaseAll();}
    }
    private static final class ThrowConnection extends Connection {
        final int throwingIndex;final boolean after,linkage;final List<PacketSendListener> listeners=new ArrayList<>();
        int calls;Runnable first;
        ThrowConnection(boolean up,int throwingIndex,boolean after,boolean linkage){super(up?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND);this.throwingIndex=throwingIndex;this.after=after;this.linkage=linkage;}
        @Override public void send(Packet<?> packet,PacketSendListener listener){
            int index=calls++;listeners.add(listener);if(index==0&&first!=null)first.run();
            if(index==throwingIndex&&!after)fail();
            super.send(packet,listener);
            if(index==throwingIndex&&after)fail();
        }
        private void fail(){if(linkage)throw new LinkageError("Intentional post-queue fixture failure");throw new IllegalStateException("Intentional uncertain send fixture failure");}
    }
    private static List<CustomPacketPayload> batch(int count){var result=new ArrayList<CustomPacketPayload>();for(int i=0;i<count;i++)result.add(new Payload(i));return result;}
    private static int[] sizes(int count,int size){int[] bytes=new int[count];Arrays.fill(bytes,size);return bytes;}

    private static void normal(boolean up){
        try(var f=new Fixture(up)){
            var payloads=batch(6);int[] bytes=sizes(6,32768);
            check(CabinetMediaSender.sendPayloads(f.connection,payloads,bytes,up),"whole max frame accepted");f.drain();
            check(f.hold.packets.size()==6&&CabinetMediaSender.inFlight(f.connection)==196608,"full frame shares existing byte cap");
            for(int i=0;i<6;i++){
                Object packet=f.hold.packets.get(i);
                check(up?packet instanceof ServerboundCustomPayloadPacket:packet instanceof ClientboundCustomPayloadPacket,"actual outer direction");
                var payload=up?((ServerboundCustomPayloadPacket)packet).payload():((ClientboundCustomPayloadPacket)packet).payload();
                check(payload==payloads.get(i),"batch order and exact payload identity");
            }
            for(int i=0;i<100;i++)check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{32},up),"blocked retries queue zero");
            check(!CabinetMediaSender.sendPayload(f.connection,new Payload(9),32,up),"single payload shares saturated batch window");
            CabinetMediaSender.release(f.connection);
            check(CabinetMediaSender.inFlight(f.connection)==196608&&f.hold.packets.size()==6,"active release does not reset reservations");
            f.hold.complete();f.drain();check(CabinetMediaSender.inFlight(f.connection)==0,"actual success promises release full batch");
            check(CabinetMediaSender.sendPayload(f.connection,new Payload(7),32,up),"single reserves first");f.drain();
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(6),sizes(6,32768),up),"partial remaining room rejects entire batch");
            f.drain();check(f.hold.packets.size()==1&&CabinetMediaSender.inFlight(f.connection)==32,"denied frame sends no prefix");
        }
    }
    private static void invalid(boolean up){
        try(var f=new Fixture(up)){
            for(int count:new int[]{0,7})check(!CabinetMediaSender.sendPayloads(f.connection,batch(count),sizes(count,32),up),"invalid batch count");
            check(!CabinetMediaSender.sendPayloads(f.connection,null,new int[]{32},up),"null batch");
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),null,up),"null size list");
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(2),new int[]{32},up),"length mismatch");
            check(!CabinetMediaSender.sendPayloads(f.connection,Arrays.asList(new Payload(1),null),new int[]{32,32},up),"null member");
            var changedSnapshot=new AbstractList<CustomPacketPayload>(){
                @Override public CustomPacketPayload get(int index){return new Payload(index);}
                @Override public int size(){return 1;}
                @Override public Object[] toArray(){return batch(7).toArray();}
            };
            check(!CabinetMediaSender.sendPayloads(f.connection,changedSnapshot,new int[]{32},up),"copied snapshot shape revalidated before reservation");
            for(int size:new int[]{Integer.MIN_VALUE,-1,0,31,32769,Integer.MAX_VALUE})
                check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{size},up),"invalid per-packet accounting");
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{32},!up),"wrong flow");
            f.channel.unsafe().outboundBuffer().setUserDefinedWritability(1,false);
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{32},up),"unwritable rejects");
            f.channel.unsafe().outboundBuffer().setUserDefinedWritability(1,true);f.drain();
            check(f.hold.packets.isEmpty()&&CabinetMediaSender.inFlight(f.connection)==0,"all invalid cases queue/reserve zero");
            check(CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{32},up),"minimum singleton batch accepted");f.drain();
            f.channel.close();f.drain();CabinetMediaSender.release(f.connection);
            check(CabinetMediaSender.inFlight(f.connection)==0,"disconnect releases all capacity");
            check(!CabinetMediaSender.sendPayloads(f.connection,batch(1),new int[]{32},up),"closed connection rejects new frame");
        }
        check(!CabinetMediaSender.sendPayloads(null,batch(1),new int[]{32},up),"null connection rejects");
        check(!CabinetMediaSender.sendPayloads(new Connection(up?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND),batch(1),new int[]{32},up),"unconnected reference rejects");
    }
    private static void defensiveSnapshot(boolean up){
        var connection=new ThrowConnection(up,-1,true,false);
        try(var f=new Fixture(connection)){
            var payloads=batch(3);int[] bytes={100,200,300};var original=List.copyOf(payloads);
            connection.first=()->{
                check(CabinetMediaSender.inFlight(connection)==600,"entire batch reserved before first send");
                payloads.clear();Arrays.fill(bytes,Integer.MAX_VALUE);
            };
            check(CabinetMediaSender.sendPayloads(connection,payloads,bytes,up),"caller mutation cannot change admitted snapshot");f.drain();
            check(f.hold.packets.size()==3&&CabinetMediaSender.inFlight(connection)==600,"original sizes retained");
            for(int i=0;i<3;i++){var packet=f.hold.packets.get(i);check((up?((ServerboundCustomPayloadPacket)packet).payload():((ClientboundCustomPayloadPacket)packet).payload())==original.get(i),"original batch payload retained");}
        }
    }
    private static void uncertainFailure(boolean up,boolean after,boolean linkage,int throwing){
        var connection=new ThrowConnection(up,throwing,after,linkage);
        try(var f=new Fixture(connection)){
            check(!CabinetMediaSender.sendPayloads(connection,batch(4),new int[]{100,200,300,400},up),"injected send failure reports false");f.drain();
            int retained=throwing==0?100:600;
            check(connection.calls==throwing+1,"no later sends after throwing call");
            check(f.hold.packets.size()==throwing+(after?1:0),"real Connection queued expected prefix");
            check(CabinetMediaSender.inFlight(connection)==retained,"throwing reservation retained; only never-attempted suffix canceled");
            CabinetMediaSender.release(connection);check(CabinetMediaSender.inFlight(connection)==retained,"release does not erase uncertain ticket");
            f.hold.complete();f.drain();
            int unresolved=after?0:throwing==0?100:300;
            check(CabinetMediaSender.inFlight(connection)==unresolved,"only actual write completions release prefix");
            if(!after){
                f.channel.close();f.drain();CabinetMediaSender.release(connection);
                check(CabinetMediaSender.inFlight(connection)==0,"disconnect clears never-completed uncertain send");
            }else{
                check(CabinetMediaSender.sendPayloads(connection,batch(1),new int[]{700},up),"later batch can reserve freed window");f.drain();
                for(var callback:connection.listeners.subList(0,throwing+1)){callback.onSuccess();check(callback.onFailure()==null,"failure listener has no recovery packet");}
                check(CabinetMediaSender.inFlight(connection)==700,"duplicate earlier success/failure cannot release later batch");
                f.hold.complete();f.drain();check(CabinetMediaSender.inFlight(connection)==0,"later actual completion releases own batch");
            }
        }
    }
    public static void main(String[] args)throws Exception{
        var output=System.out;
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        for(boolean up:new boolean[]{false,true}){
            normal(up);invalid(up);defensiveSnapshot(up);
            for(boolean after:new boolean[]{false,true})for(boolean linkage:new boolean[]{false,true})for(int throwing:new int[]{0,2})uncertainFailure(up,after,linkage,throwing);
        }
        if(args.length==1){var expected=Path.of(args[0]).toRealPath();for(var type:List.of(CabinetMediaSender.class,CabinetSendWindow.class))check(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"final JAR production origin "+type.getName());}
        output.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_connection_and_embedded_channel\":true,\"explicit_throw_injection\":true,\"minecraft_world_started\":false,\"socket_opened\":false,\"native_core_started\":false}");
    }
}
