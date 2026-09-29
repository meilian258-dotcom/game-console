package cn.piq.fcarcade.network;

import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerTrafficMeterTest {
    static final ResourceLocation ID=ResourceLocation.parse("piq_fc_arcade:cabinet_input");
    static final StreamCodec<RegistryFriendlyByteBuf,String> RAW=StreamCodec.of((b,v)->b.writeUtf(v),b->b.readUtf());
    static RegistryFriendlyByteBuf buf(){return new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);}
    @AfterEach void clear(){ServerTrafficMeter.install(null);ModTrafficProbe.clientCollector(null);}
    @Test void directionsAreServerPerspectiveAndClientCounterIsIndependent(){
        var clock=new AtomicLong();var server=new ServerTrafficMeter.Session(clock::get);var client=new ModTrafficCounter(clock::get);
        ServerTrafficMeter.install(server);ModTrafficProbe.clientCollector(client);
        var up=ServerTrafficMeter.wrap(ID,false,ModTrafficProbe.toServer(RAW));var down=ServerTrafficMeter.wrap(ID,true,ModTrafficProbe.toClient(RAW));
        var b=buf();try{
            up.encode(b,"input");assertEquals(0,server.total().totalBytes());up.decode(b);
            down.encode(b,"video!");down.decode(b);
            assertEquals(6,server.total().downloadedBytes());assertEquals(7,server.total().uploadedBytes());
            assertEquals(6,client.sample().uploadedBytes());assertEquals(7,client.sample().downloadedBytes());
            assertEquals(13,server.group(ServerTrafficMeter.Group.CABINET).totalBytes());
            assertEquals(0,server.group(ServerTrafficMeter.Group.FC_HOME).totalBytes());
        }finally{b.release();}
    }
    @Test void multicastEncodesAccumulatePerActualRecipientWithoutReadingContent(){
        var server=new ServerTrafficMeter.Session(()->0);ServerTrafficMeter.install(server);var codec=ServerTrafficMeter.wrap(ID,true,RAW);var b=buf();
        try{for(int i=0;i<3;i++)codec.encode(b,"abc");assertEquals(12,server.total().uploadedBytes());}finally{b.release();}
    }
    @Test void badDecodeDoesNotIncreaseSuccessfulPayloadTotals(){
        var server=new ServerTrafficMeter.Session(()->0);ServerTrafficMeter.install(server);var b=buf();
        try{b.writeByte(50);assertThrows(RuntimeException.class,()->ServerTrafficMeter.wrap(ID,false,RAW).decode(b));assertEquals(0,server.total().totalBytes());}finally{b.release();}
    }
    @Test void lifecycleReplacementDoesNotChargeNewSessionForInFlightCodec(){
        var old=new ServerTrafficMeter.Session(()->0);var fresh=new ServerTrafficMeter.Session(()->0);
        var switching=StreamCodec.<RegistryFriendlyByteBuf,String>of((b,v)->{RAW.encode(b,v);ServerTrafficMeter.install(fresh);},b->{String v=RAW.decode(b);ServerTrafficMeter.install(fresh);return v;});
        var b=buf();try{
            ServerTrafficMeter.install(old);ServerTrafficMeter.wrap(ID,true,switching).encode(b,"old");assertEquals(0,fresh.total().totalBytes());
            ServerTrafficMeter.install(old);ServerTrafficMeter.wrap(ID,false,switching).decode(b);assertEquals(0,old.total().totalBytes());assertEquals(0,fresh.total().totalBytes());
        }finally{b.release();}
    }
    @Test void ratesSampleWithoutClearingLifetimeAndHeartbeatUsesWatchBucket(){
        var clock=new AtomicLong();var server=new ServerTrafficMeter.Session(clock::get);ServerTrafficMeter.install(server);
        ServerTrafficMeter.heartbeat(true,17);ServerTrafficMeter.heartbeat(false,18);clock.set(1_000_000_000L);server.sample();
        assertEquals(17,server.total().uploadBytesPerSecond());assertEquals(35,server.group(ServerTrafficMeter.Group.WATCH).totalBytes());
        clock.set(2_000_000_000L);server.sample();assertEquals(0,server.total().uploadBytesPerSecond());assertEquals(35,server.total().totalBytes());
    }
    @Test void fixedBucketsAndCommandUnitsAreExplicit(){
        assertEquals(ServerTrafficMeter.Group.SFC_HOME,ServerTrafficMeter.group(ResourceLocation.parse("piq_sfc_home:home_state")));
        assertEquals(ServerTrafficMeter.Group.WATCH,ServerTrafficMeter.group(ResourceLocation.parse("piq_fc_arcade:watch_media")));
        assertEquals(5,ServerTrafficMeter.Group.values().length);
        assertEquals(ServerTrafficMeter.Group.NETPLAY,ServerTrafficMeter.group(ResourceLocation.parse("piq_fc_arcade:netplay_data")));
        assertTrue(ServerTrafficDiagnostics.line("总计",new ModTrafficCounter.Sample(1024,2048,java.util.List.of(),1048576,2097152)).contains("1.00 MiB / 接收 2.00 MiB / 总计 3.00 MiB"));
    }
    @Test void readOnlyOpCommandRevalidatesSubscribersAndBoundsTheirCount() throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/network/ServerTrafficDiagnostics.java"));
        assertTrue(source.contains("requires(s->s.hasPermission(2))"));assertTrue(source.contains("!p.hasPermissions(2)"));
        assertTrue(source.contains("watchers.size()>=64"));assertTrue(source.contains("getConnection()!=entry.getValue()"));
        assertFalse(source.contains("setDirty("));assertFalse(source.contains("reset("));assertTrue(source.contains("ServerTrafficMeter.install(null)"));
    }
}
