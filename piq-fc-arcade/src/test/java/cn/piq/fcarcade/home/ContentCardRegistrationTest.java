package cn.piq.fcarcade.home;

import cn.piq.fcarcade.home.content.ContentCardNetwork;
import cn.piq.fcarcade.home.content.ContentCardNetwork.Message;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises NeoForge's actual registry, not a source-text surrogate or full server boot. */
@ResourceLock("NeoForge-PAYLOAD_REGISTRATIONS")
class ContentCardRegistrationTest {
    private Map<ResourceLocation,PayloadRegistration<?>> registrations;
    private PayloadRegistration<?> previous;
    @BeforeAll static void bootstrap(){
        if(net.neoforged.fml.loading.LoadingModList.get()==null)
            net.neoforged.fml.loading.LoadingModList.of(List.of(),List.of(),List.of(),List.of(),Map.of());
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
    }
    @BeforeEach @SuppressWarnings("unchecked") void isolate()throws Exception{
        var field=NetworkRegistry.class.getDeclaredField("PAYLOAD_REGISTRATIONS");field.setAccessible(true);
        registrations=((Map<ConnectionProtocol,Map<ResourceLocation,PayloadRegistration<?>>>)field.get(null)).get(ConnectionProtocol.PLAY);
        previous=registrations.remove(Message.TYPE.id());
    }
    @AfterEach void restore(){registrations.remove(Message.TYPE.id());if(previous!=null)registrations.put(Message.TYPE.id(),previous);}
    @Test void registersExactlyOnceForBothDirections(){
        int count=registrations.size();ContentCardNetwork.register(new RegisterPayloadHandlersEvent());
        var r=registrations.get(Message.TYPE.id());assertEquals(count+1,registrations.size());
        assertEquals("content-card-3",r.version());assertFalse(r.optional());assertTrue(r.flow().isEmpty());
        for(var flow:PacketFlow.values())assertSame(Message.CODEC,NetworkRegistry.getCodec(Message.TYPE.id(),ConnectionProtocol.PLAY,flow));
        assertThrows(UnsupportedOperationException.class,()->ContentCardNetwork.register(new RegisterPayloadHandlersEvent()));
    }
    @Test @SuppressWarnings("unchecked") void serverDirectionWithoutPlayerCannotFallBackToClient(){
        ContentCardNetwork.register(new RegisterPayloadHandlersEvent());var queue=new ArrayList<Runnable>();
        var context=(IPayloadContext)Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),new Class<?>[]{IPayloadContext.class},(proxy,method,args)->switch(method.getName()){
            case "flow"->PacketFlow.SERVERBOUND;
            case "player"->null;
            case "enqueueWork"->{queue.add((Runnable)args[0]);yield CompletableFuture.completedFuture(null);}
            default->throw new AssertionError(method.getName());
        });
        var message=ContentCardNetwork.msg(ContentCardNetwork.STOP,ResourceLocation.parse("example:console"),UUID.randomUUID(),BlockPos.ZERO,"","",0,0,new byte[0]);
        ((PayloadRegistration<Message>)registrations.get(Message.TYPE.id())).handler().handle(message,context);
        assertEquals(1,queue.size());assertDoesNotThrow(queue.removeFirst()::run);
    }
}
