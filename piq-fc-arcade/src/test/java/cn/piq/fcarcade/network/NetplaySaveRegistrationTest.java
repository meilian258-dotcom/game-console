// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.network;

import cn.piq.fcarcade.netplay.NetplaySaveNetwork;
import cn.piq.fcarcade.netplay.NetplaySaveNetwork.Message;
import cn.piq.fcarcade.netplay.NetplaySaveTransfer;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static cn.piq.fcarcade.netplay.NetplaySaveNetwork.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real NeoForge registration/codec/handler wrappers; no copied registry implementation. */
@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class NetplaySaveRegistrationTest {
    private Map<ResourceLocation,PayloadRegistration<?>> registrations;
    private PayloadRegistration<?> previousRegistration;
    private Object previousSink;
    private ServerTrafficMeter.Session previousMeter;
    private final List<TestConnection> connections=new ArrayList<>();

    @BeforeAll static void bootstrapPacketDependencies(){
        // Test-only empty language JSON supplies the resource absent from the unit-test
        // classpath. Real Minecraft bootstrap/packets and NeoForge registration still run.
        // This is not a substitute for an actual ModLoader client/dedicated-server launch.
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach @SuppressWarnings("unchecked") void isolate() throws Exception {
        // Reflection is test-only: save and restore just our ID in NeoForge's real registry.
        Field field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        var byProtocol=(Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null);
        registrations=byProtocol.get(ConnectionProtocol.PLAY);
        previousRegistration=registrations.remove(Message.TYPE.id());
        Field sinkField=NetplaySaveNetwork.class.getDeclaredField("sink");sinkField.setAccessible(true);
        previousSink=sinkField.get(null);
        previousMeter=ServerTrafficMeter.current();
    }
    @AfterEach void restore() throws Exception {
        registrations.remove(Message.TYPE.id());
        if(previousRegistration!=null)registrations.put(Message.TYPE.id(),previousRegistration);
        Field sinkField=NetplaySaveNetwork.class.getDeclaredField("sink");sinkField.setAccessible(true);sinkField.set(null,previousSink);
        ServerTrafficMeter.install(previousMeter);ModTrafficProbe.clientCollector(null);
        for(var connection:connections)connection.transport.finishAndReleaseAll();
    }

    @Test void oldSeparateDirectionalRegistrationsReallyFailInNeoForge() {
        var registrar=TrafficPayloadRegistrar.create(new RegisterPayloadHandlersEvent(),"netplay-save-1");
        registrar.playToServer(Message.TYPE,Message.CODEC,(m,c)->{});
        var failure=assertThrows(UnsupportedOperationException.class,
                ()->registrar.playToClient(Message.TYPE,Message.CODEC,(m,c)->{}));
        assertEquals("Cannot register payload piq_fc_arcade:netplay_save as it is already registered.",failure.getMessage());
        assertEquals(PacketFlow.SERVERBOUND,registrations.get(Message.TYPE.id()).flow().orElseThrow());
    }

    @Test void productionRegistersOnceAndProvidesTheUnchangedCodecInBothDirections() {
        int before=registrations.size();
        assertDoesNotThrow(()->NetplaySaveNetwork.register(new RegisterPayloadHandlersEvent()));
        assertEquals(before+1,registrations.size());
        var registration=registration();
        assertEquals("netplay-save-1",registration.version());assertFalse(registration.optional());
        assertTrue(registration.flow().isEmpty());assertEquals(List.of(ConnectionProtocol.PLAY),registration.protocols());
        for(var flow:PacketFlow.values())assertSame(Message.CODEC,NetworkRegistry.getCodec(Message.TYPE.id(),ConnectionProtocol.PLAY,flow));
        // A repeated event remains an error. Do not swallow the registry's duplicate protection.
        assertThrows(UnsupportedOperationException.class,()->NetplaySaveNetwork.register(new RegisterPayloadHandlersEvent()));
    }

    @Test void registeredHandlerQueuesToMainAndOnlyInvokesItsActualDirection() {
        NetplaySaveNetwork.register(new RegisterPayloadHandlersEvent());
        var source=connection();var replies=new ArrayList<Message>();var playerLookups=new AtomicInteger();
        NetplaySaveNetwork.sink((connection,message)->{assertSame(source,connection);replies.add(message);});
        var queued=new ArrayList<Runnable>();var message=message(EMPTY,0,0,0,"");
        registration().handler().handle(message,context(PacketFlow.CLIENTBOUND,source,playerLookups,queued));
        assertTrue(replies.isEmpty());assertEquals(1,queued.size());assertEquals(0,playerLookups.get());
        queued.removeFirst().run();assertEquals(List.of(message),replies);assertEquals(0,playerLookups.get());
        registration().handler().handle(message,context(PacketFlow.SERVERBOUND,source,playerLookups,queued));
        assertEquals(0,playerLookups.get());assertEquals(1,queued.size());queued.removeFirst().run();
        assertEquals(1,playerLookups.get());assertEquals(List.of(message),replies);
        // No ServerPlayer was supplied: a server-bound message must not fall back to the client sink.
    }

    @Test void sendReceiveDirectionsKeepIntegratedServerAndClientCountersIndependent() {
        NetplaySaveNetwork.register(new RegisterPayloadHandlersEvent());
        var server=new ServerTrafficMeter.Session(()->0);ServerTrafficMeter.install(server);
        var client=new ModTrafficCounter(()->0);var clientConnection=connection();var serverConnection=connection();
        ModTrafficProbe.clientCollector(clientConnection,client);
        NetplaySaveNetwork.sink((connection,message)->{});
        var request=message(READ,0,0,0,"资料");var reply=message(EMPTY,0,0,0,"没有存档");
        NetplaySaveNetwork.send(clientConnection,request,false);
        assertInstanceOf(ServerboundCustomPayloadPacket.class,clientConnection.sent.getFirst());
        assertEquals(request.encodedBytes(),client.sample().uploadedBytes());assertEquals(0,server.total().totalBytes());
        registration().handler().handle(request,context(PacketFlow.SERVERBOUND,serverConnection,new AtomicInteger(),null));
        assertEquals(request.encodedBytes(),server.total().downloadedBytes());assertEquals(0,client.sample().downloadedBytes());
        NetplaySaveNetwork.send(serverConnection,reply,true);
        assertInstanceOf(ClientboundCustomPayloadPacket.class,serverConnection.sent.getFirst());
        assertEquals(reply.encodedBytes(),server.total().uploadedBytes());
        registration().handler().handle(reply,context(PacketFlow.CLIENTBOUND,clientConnection,new AtomicInteger(),null));
        assertEquals(reply.encodedBytes(),client.sample().downloadedBytes());
        assertEquals(request.encodedBytes()+reply.encodedBytes(),client.sample().categories().get(TrafficCategory.NETPLAY).totalBytes());
        assertEquals(client.sample().totalBytes(),server.group(ServerTrafficMeter.Group.NETPLAY).totalBytes());
        assertEquals(0,server.group(ServerTrafficMeter.Group.FC_HOME).totalBytes());
    }

    @Test void staleClientEndpointAndFailedSendsAreNotCounted() {
        NetplaySaveNetwork.register(new RegisterPayloadHandlersEvent());
        var current=connection();var old=connection();var client=new ModTrafficCounter(()->0);
        ModTrafficProbe.clientCollector(current,client);NetplaySaveNetwork.sink((c,m)->{});
        var packet=message(EMPTY,0,0,0,"");
        NetplaySaveNetwork.send(old,packet,false);
        registration().handler().handle(packet,context(PacketFlow.CLIENTBOUND,old,new AtomicInteger(),null));
        assertEquals(0,client.sample().totalBytes());
        current.connected=false;assertThrows(IllegalStateException.class,()->NetplaySaveNetwork.send(current,packet,false));
        current.connected=true;current.missingChannel=true;assertThrows(IllegalStateException.class,()->NetplaySaveNetwork.send(current,packet,false));
        current.missingChannel=false;current.rejectSend=true;assertThrows(IllegalStateException.class,()->NetplaySaveNetwork.send(current,packet,false));
        assertEquals(0,client.sample().totalBytes());
    }

    @Test void exactMeterLengthMatchesRealCodecAcrossKindsUtf8AndVarintBoundaries() {
        for(int kind=READ;kind<=CANCEL;kind++){
            boolean chunk=kind==DOWNLOAD||kind==UPLOAD;
            var packet=message(kind,kind==LOAD||kind==BEGIN||kind==DOWNLOAD?16384:0,chunk?128:0,chunk?16384:0,"");
            assertEncodedLength(packet);
        }
        for(int size:new int[]{1,127,128,16383,NetplaySaveTransfer.CHUNK})
            for(int offset:new int[]{0,127,128,16383,16384,NetplaySaveTransfer.MAX_PACKED})
                assertEncodedLength(message(UPLOAD,0,offset,size,""));
        for(int value:new int[]{1,127,128,16383,16384,NetplaySaveTransfer.MAX_PACKED})assertEncodedLength(message(BEGIN,value,0,0,""));
        for(String text:List.of("","x".repeat(127),"x".repeat(128),"中".repeat(256),"🎮".repeat(128),"bad\uD800"))
            assertEncodedLength(message(ERROR,0,0,0,text));
    }

    @SuppressWarnings("unchecked") private PayloadRegistration<Message> registration(){return (PayloadRegistration<Message>)registrations.get(Message.TYPE.id());}
    private TestConnection connection(){var value=new TestConnection();connections.add(value);return value;}
    private static Message message(int kind,int value,int offset,int size,String text){return new Message(42,UUID.randomUUID(),UUID.randomUUID(),kind,value,offset,new byte[size],text);}
    private static void assertEncodedLength(Message message){
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{Message.CODEC.encode(buffer,message);assertEquals(buffer.readableBytes(),message.encodedBytes());
            var decoded=Message.CODEC.decode(buffer);assertEquals(message.encodedBytes(),decoded.encodedBytes());assertEquals(0,buffer.readableBytes());
            assertEquals(message.kind(),decoded.kind());assertArrayEquals(message.bytes(),decoded.bytes());
        }finally{buffer.release();}
    }
    private static IPayloadContext context(PacketFlow flow,Connection connection,AtomicInteger playerLookups,List<Runnable> queued){
        return (IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(proxy,method,args)->switch(method.getName()){
            case "flow"->flow;
            case "connection"->connection;
            case "player"->{assertTrue(flow.isServerbound(),"client-bound handler must not access server authority");playerLookups.incrementAndGet();yield null;}
            case "enqueueWork"->{
                CompletableFuture<Object> future=new CompletableFuture<>();
                Runnable work=()->{try{if(args[0] instanceof Runnable r){r.run();future.complete(null);}else future.complete(((Supplier<?>)args[0]).get());}catch(Throwable failure){future.completeExceptionally(failure);throw failure;}};
                if(queued==null)work.run();else queued.add(work);yield future;
            }
            default->throw new AssertionError("Unexpected payload context method: "+method.getName());
        });
    }
    private static final class TestConnection extends Connection {
        final EmbeddedChannel transport=new EmbeddedChannel();final List<Packet<?>> sent=new ArrayList<>();
        boolean connected=true,missingChannel,rejectSend;
        TestConnection(){super(PacketFlow.CLIENTBOUND);}
        @Override public boolean isConnected(){return connected;}
        @Override public Channel channel(){return missingChannel?null:transport;}
        @Override public void send(Packet<?> packet){if(rejectSend)throw new IllegalStateException("send rejected");sent.add(packet);}
    }
}
