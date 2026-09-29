package cn.piq.fcarcade.cabinet;

import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;

/** Real Connection sends and real promises; no socket, world, simulated Connection or native core. */
public final class CabinetGameTransportProbe {
    private static int assertions;
    private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
    private static final class Hold extends ChannelOutboundHandlerAdapter {
        final List<Object> packets=new ArrayList<>();final List<ChannelPromise> pending=new ArrayList<>();
        @Override public void write(ChannelHandlerContext c,Object p,ChannelPromise done){packets.add(p);pending.add(done);}
        void complete(){for(var promise:pending)promise.trySuccess();pending.clear();packets.clear();}
    }
    private static List<CabinetMediaPacket> frame(UUID source){
        var parts=new ArrayList<CabinetMediaPacket>();for(int i=0;i<6;i++)parts.add(new CabinetMediaPacket(source,source,0,0,i,6,384,288,4F/3,0,221184,new byte[i==5?8192:24576]));return parts;
    }
    public static void main(String[] args)throws Exception {
        var output=System.out;net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        UUID source=new UUID(3,4);var backend=ResourceLocation.parse("piq_native_arcade:mame");
        var reply=new CabinetGameNetwork.Reply(source,1,true,null,0,0,new byte[24576],"");
        var command=new CabinetGameNetwork.Command(source,1,CabinetGameNetwork.GET,source,backend,null,0,0,new byte[0]);
        new ClientboundCustomPayloadPacket(reply);new ServerboundCustomPayloadPacket(command);
        for(boolean up:new boolean[]{false,true}){
            var hold=new Hold();var connection=new Connection(up?PacketFlow.CLIENTBOUND:PacketFlow.SERVERBOUND);var channel=new EmbeddedChannel(hold,connection);
            try{
                var payload=up?command:reply;
                check(CabinetMediaSender.sendPayload(connection,payload,32768,up),"first generic payload");channel.runPendingTasks();
                check(CabinetMediaSender.inFlight(connection)==32768&&hold.packets.size()==1,"real promise holds exactly reserved bytes");
                check(up?hold.packets.getFirst() instanceof ServerboundCustomPayloadPacket:hold.packets.getFirst() instanceof ClientboundCustomPayloadPacket,"actual outer packet direction");
                check(up?CabinetMediaSender.serverbound(connection,frame(source)):CabinetMediaSender.clientbound(connection,source,frame(source)),"room shares remaining generic window");channel.runPendingTasks();
                int retained=CabinetMediaSender.inFlight(connection);check(retained==32768+131072+1536,"one physical generic+room sum");
                for(int i=0;i<200;i++)check(!CabinetMediaSender.sendPayload(connection,payload,32768,up),"blocked generic producer does not grow queue");
                check(hold.packets.size()==7&&CabinetMediaSender.inFlight(connection)==retained,"held Netty task count stays bounded");
                CabinetMediaSender.release(connection);check(!CabinetMediaSender.sendPayload(connection,payload,32768,up),"release cannot reset active mixed window");
                hold.complete();channel.runPendingTasks();check(CabinetMediaSender.inFlight(connection)==0,"actual write completions release room+game");
                for(int i=0;i<6;i++)check(CabinetMediaSender.sendPayload(connection,payload,32768,up),"six generic maximum reservations");channel.runPendingTasks();
                check(!CabinetMediaSender.sendPayload(connection,payload,32,up),"even tiny packet denied over shared cap");
                check(!(up?CabinetMediaSender.watchServerbound(connection,frame(source)):CabinetMediaSender.watchClientbound(connection,source,frame(source))),"game blocks watch at same physical cap");
                hold.complete();channel.runPendingTasks();
                check(!CabinetMediaSender.sendPayload(connection,payload,32769,up),"oversize packet denied");check(!CabinetMediaSender.sendPayload(connection,payload,31,up),"zero/undersized accounting denied");
                check(!CabinetMediaSender.sendPayload(connection,payload,100,!up),"wrong Connection flow denied");
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1,false);check(!CabinetMediaSender.sendPayload(connection,payload,100,up),"unwritable connection queues nothing");
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1,true);
                var window=new CabinetSendWindow();var first=window.reserve(new int[]{100});var completion=CabinetMediaSender.completion(first,0);
                check(completion.onFailure()==null&&window.inFlight()==0,"real PacketSendListener failure releases without kick");var second=window.reserve(new int[]{200});
                completion.onSuccess();completion.onFailure();check(window.inFlight()==200,"duplicate failure/success never releases another ticket");second.complete(0);
                channel.close();channel.runPendingTasks();check(!CabinetMediaSender.sendPayload(connection,payload,100,up),"disconnected original reference rejected");
            }finally{hold.complete();channel.close();channel.runPendingTasks();CabinetMediaSender.release(connection);channel.finishAndReleaseAll();}
        }
        if(args.length==1){var expected=java.nio.file.Path.of(args[0]).toRealPath();for(var type:List.of(CabinetMediaSender.class,CabinetSendWindow.class,CabinetGameNetwork.class,CabinetGameNetwork.Command.class,CabinetGameNetwork.Reply.class))check(java.nio.file.Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected),"final JAR CodeSource "+type.getName());}
        output.println("{\"ok\":true,\"assertions\":"+assertions+",\"actual_connection_and_embedded_channel\":true,\"shared_media_window\":true,\"minecraft_world_started\":false,\"socket_opened\":false,\"native_core_started\":false}");
    }
}
