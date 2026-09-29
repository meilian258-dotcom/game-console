package cn.piq.fcarcade.cabinet;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** Actual Connection.send + EmbeddedChannel write promises. No fake Connection or network socket. */
public final class CabinetSendBackpressureProbe {
    private static int checks;
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static final class Hold extends ChannelOutboundHandlerAdapter {
        final List<Object> packets=new ArrayList<>();final List<ChannelPromise> promises=new ArrayList<>();
        @Override public void write(ChannelHandlerContext context,Object packet,ChannelPromise promise){packets.add(packet);promises.add(promise);}
        void complete(){for(var promise:promises)promise.trySuccess();promises.clear();packets.clear();}
    }
    private static List<CabinetMediaPacket> frame(UUID room,int seq){
        var result=new ArrayList<CabinetMediaPacket>();for(int i=0;i<6;i++)result.add(new CabinetMediaPacket(room,room,seq,0,i,6,384,288,4F/3,0,221184,new byte[i==5?8192:24576]));return List.copyOf(result);
    }
    public static void main(String[] args){
        var output=System.out;
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        var room=new UUID(1,2);var member=new UUID(3,4);
        var hold=new Hold();var connection=new Connection(PacketFlow.SERVERBOUND);
        var channel=new EmbeddedChannel(hold,connection);
        try{
            check(connection.isConnected()&&connection.channel()==channel,"real Connection activated with EmbeddedChannel");
            var batch=frame(room,0);
            // Initialize actual outer packet classes outside the sender's fail-closed boundary so
            // a missing probe runtime dependency is reported, not mistaken for backpressure.
            new ClientboundCustomPayloadPacket(new CabinetRoomNetwork.Stream(member,
                    new CabinetRoomNetwork.Media(room,room,0,1,0,1,0,0,1,0,4,new byte[4])));
            check(CabinetMediaSender.clientbound(connection,member,batch),"first complete frame accepted");
            channel.runPendingTasks();
            check(hold.packets.size()==6,"actual six packet writes held without completion");
            int bytes=CabinetMediaSender.inFlight(connection);check(bytes==131072+6*256,"all actual in-flight wire budget retained");
            for(int i=0;i<1000;i++)check(!CabinetMediaSender.clientbound(connection,member,frame(room,i+1)),"blocked writer rejects whole frame");
            check(hold.packets.size()==6&&CabinetMediaSender.inFlight(connection)==bytes,"blocked queue cannot grow with repeated sends");
            CabinetMediaSender.release(connection);
            check(!CabinetMediaSender.clientbound(connection,new UUID(7,8),frame(room,1001)),"leave/rejoin same Connection cannot reset live window");
            check(hold.packets.getFirst() instanceof ClientboundCustomPayloadPacket p&&p.payload() instanceof CabinetRoomNetwork.Stream stream&&stream.member().equals(member),"actual downstream packet retains recipient lease");
            hold.complete();channel.runPendingTasks();
            check(CabinetMediaSender.inFlight(connection)==0,"actual write promises release reservations");
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1,false);
            check(!CabinetMediaSender.clientbound(connection,member,frame(room,1002))&&hold.packets.isEmpty(),"unwritable transport drops whole frame before queue");
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1,true);
            check(CabinetMediaSender.clientbound(connection,member,frame(room,1003)),"writable transport resumes");
            hold.complete();channel.runPendingTasks();
            check(!CabinetMediaSender.serverbound(connection,batch),"wrong connection direction rejected");
            check(!CabinetMediaSender.clientbound(connection,member,batch.subList(0,5)),"partial caller batch rejected atomically");
            var w=new CabinetSendWindow();var ticket=w.reserve(new int[]{1000});var listener=CabinetMediaSender.completion(ticket,0);
            check(listener.onFailure()==null&&w.inFlight()==0,"real PacketSendListener failure returns budget without disconnect packet");
            var current=w.reserve(new int[]{1000});listener.onSuccess();listener.onFailure();check(w.inFlight()==1000,"duplicate mixed callbacks cannot release next ticket");current.complete(0);
        }finally{hold.complete();channel.close();channel.runPendingTasks();CabinetMediaSender.release(connection);channel.finishAndReleaseAll();}
        var upstreamHold=new Hold();var upstream=new Connection(PacketFlow.CLIENTBOUND);var upstreamChannel=new EmbeddedChannel(upstreamHold,upstream);
        try{check(CabinetMediaSender.serverbound(upstream,frame(room,0)),"host upstream admits whole frame");upstreamChannel.runPendingTasks();check(upstreamHold.packets.getFirst() instanceof ServerboundCustomPayloadPacket p&&p.payload() instanceof CabinetRoomNetwork.Media,"actual upstream packet type");upstreamHold.complete();upstreamChannel.runPendingTasks();check(CabinetMediaSender.inFlight(upstream)==0,"upstream callbacks free budget");}
        finally{upstreamHold.complete();upstreamChannel.close();upstreamChannel.runPendingTasks();CabinetMediaSender.release(upstream);upstreamChannel.finishAndReleaseAll();}
        check(!CabinetMediaSender.serverbound(new Connection(PacketFlow.CLIENTBOUND),frame(room,0)),"unconnected connection never queues pre-login actions");
        output.println("{\"ok\":true,\"assertions\":"+checks+",\"actual_connection_and_embedded_channel\":true,\"minecraft_started\":false,\"network_socket_opened\":false,\"native_core_started\":false}");
    }
}
