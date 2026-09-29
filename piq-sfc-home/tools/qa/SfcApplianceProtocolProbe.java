package cn.piq.sfchome.net;

import cn.piq.sfchome.client.SfcControlGrantGate;
import cn.piq.sfchome.server.SfcInputHealth;
import cn.piq.sfchome.server.SfcInputTimeline;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.*;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Final JAR records + real NeoForge outer codecs. No emulated server permission claims. */
public final class SfcApplianceProtocolProbe {
    private static int assertions,outerBytes;
    private static final UUID CONSOLE=new UUID(10,1),TV=new UUID(10,2),LINK=new UUID(10,3);
    private static void check(boolean value,String name){assertions++;if(!value)throw new AssertionError(name);}
    private static Path origin(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();}
    private static void rejects(Runnable value,String name){boolean failed=false;try{value.run();}catch(IllegalArgumentException error){failed=true;}check(failed,name);}
    private static <T>T round(StreamCodec<RegistryFriendlyByteBuf,T> codec,T value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{codec.encode(b,value);var result=codec.decode(b);check(b.readableBytes()==0,"payload fully consumed");return result;}finally{b.release();}}
    private static SfcHomeNetwork.Session runtime(int epoch,int port,UUID capability,boolean host){return new SfcHomeNetwork.Session(99,epoch,ResourceLocation.fromNamespaceAndPath("minecraft","overworld"),new BlockPos(0,64,0),CONSOLE,new BlockPos(1,64,0),TV,LINK,"a".repeat(64),SfcHomeNetwork.CORE_BUILD,port,capability,host);}
    private static void outer(net.minecraft.network.protocol.common.custom.CustomPacketPayload value){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.encode(b,new ClientboundCustomPayloadPacket(value));outerBytes=Math.max(outerBytes,b.readableBytes());var copy=ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(b);check(copy.payload().equals(value),"actual NeoForge outer codec retains exact runtime/control identity");check(b.readableBytes()==0,"outer consumed all bytes");}finally{b.release();}}
    public static void main(String[] args)throws Exception{
        Path expected=Path.of(args[0]).toRealPath();
        for(Class<?> type:List.of(SfcHomeNetwork.Session.class,SfcHomeNetwork.Control.class,SfcControlGrantGate.class,SfcInputTimeline.class,SfcInputHealth.class))check(origin(type).equals(expected),"production loaded only from final SFC JAR: "+type);
        net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        // Execute the real production registration, not a test-side substitute codec table.
        SfcHomeNetwork.register(new net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent());
        var connection=new Object();var anotherConnection=new Object();var grants=new SfcControlGrantGate();
        UUID hostToken=new UUID(20,1);var host=runtime(1,-1,hostToken,true);check(round(SfcHomeNetwork.Session.CODEC,host).equals(host),"host runtime roundtrip");outer(host);
        grants.bind(connection,99,1);
        for(int index=0;index<200;index++){
            UUID controller=new UUID(30,index+1);int port=index%2;
            var remote=runtime(1,port,controller,false);check(!remote.executionHost()&&remote.port()==port,"remote P1 or P2 is not host");check(round(SfcHomeNetwork.Session.CODEC,remote).equals(remote),"remote role survives codec");outer(remote);
            var take=new SfcHomeNetwork.Control(99,1,controller,port,true);var put=new SfcHomeNetwork.Control(99,1,controller,port,false);
            check(round(SfcHomeNetwork.Control.CODEC,take).equals(take),"claim token survives codec");check(round(SfcHomeNetwork.Control.CODEC,put).equals(put),"return token survives codec");outer(take);outer(put);
            check(grants.mayGrant(connection,99,1,take.lease()),"fresh controller can be granted");check(!grants.mayGrant(anotherConnection,99,1,controller),"old connection cannot grant");
            check(grants.retire(connection,99,1,put.lease()),"return retires exact controller");check(!grants.mayGrant(connection,99,1,controller),"delayed claim cannot revive returned controller");
            check(host.controllerLease().equals(hostToken)&&host.port()==-1,"controller churn does not alter runtime capability");
        }
        rejects(()->runtime(1,-1,UUID.randomUUID(),false),"remote runtime cannot have no port");rejects(()->runtime(1,2,UUID.randomUUID(),false),"SFC rejects third port");rejects(()->runtime(0,-1,hostToken,true),"invalid epoch");
        rejects(()->new SfcHomeNetwork.Control(99,1,hostToken,-1,true),"host has no implicit controller grant");rejects(()->new SfcHomeNetwork.Control(99,1,hostToken,2,false),"bad revoked port");
        UUID newHost=UUID.randomUUID();var reset=runtime(2,-1,newHost,true);outer(reset);check(!reset.controllerLease().equals(host.controllerLease()),"reset has new runtime token");
        grants.bind(connection,99,2);check(!grants.mayGrant(connection,99,1,newHost),"late previous epoch grant rejected");check(grants.mayGrant(connection,99,2,newHost),"new epoch accepts new token");
        var first=new SfcInputTimeline();var second=new SfcInputTimeline();var health=new SfcInputHealth();health.start(0);
        check(first.offer(0,1,false)&&first.offer(1,0,false),"P1 fast press release queued");check(second.offer(0,256,false),"P2 hold queued");
        check(health.packet(0,0)&&health.packet(1,100),"per-port heartbeat accepted");check(health.expiredPort(0,101)&&!health.expiredPort(1,101),"only expired P1 should detach");
        first=new SfcInputTimeline();check(first.next()==0&&second.next()==256,"detaching P1 clears only its FIFO");check(second.next()==256,"other port retains held input");
        for(int i=0;i<12;i++){int mask=1<<i;var timeline=new SfcInputTimeline();check(timeline.offer(0,mask,false)&&timeline.offer(1,0,false),"SFC button edges");check(timeline.next()==mask&&timeline.next()==0,"same-tick edge never swallowed");check(!timeline.offer(1,mask,false),"replay cannot resurrect released input");}
        System.out.println("{\"ok\":true,\"assertions\":"+assertions+",\"max_outer_bytes\":"+outerBytes+",\"production_origin\":\"final-jar-only\",\"minecraft_started\":false,\"actual_server_transitions_exercised\":false}");
    }
}
