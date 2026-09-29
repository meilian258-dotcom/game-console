package cn.piq.fcarcade.network;

import cn.piq.fcarcade.netplay.NetplayChunk;
import cn.piq.fcarcade.netplay.NetplayNetwork;
import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetplayTrafficTest {
    @AfterEach void clear(){ModTrafficProbe.clientCollector(null);ServerTrafficMeter.install(null);}
    @Test void exactNativeBytesMatchRealCodecForControlsAndVariableLengths(){
        for(long seq:new long[]{0,127,128,16383,16384,Long.MAX_VALUE})for(int n:new int[]{0,1,127,128,16383,16384})for(int port=-1;port<4;port++){
            var packet=new NetplayNetwork.Data(new NetplayChunk(11,UUID.randomUUID(),n==0?NetplayChunk.OPEN:NetplayChunk.DATA,seq,new byte[n]),port);
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
            try{
                NetplayNetwork.Data.CODEC.encode(b,packet);assertEquals(b.readableBytes(),packet.encodedBytes());
                var decoded=NetplayNetwork.Data.CODEC.decode(b);assertEquals(packet.encodedBytes(),decoded.encodedBytes());
                assertEquals(port,decoded.port());
                assertEquals(0,b.readableBytes());assertEquals(seq,decoded.chunk().sequence());assertArrayEquals(packet.chunk().bytes(),decoded.chunk().bytes());
            }finally{b.release();}
        }
    }
    @Test void onlyExactClientEndpointCountsAndReconnectRetiresOldConnection(){
        Object old=new Object(),fresh=new Object(),serverSide=new Object();
        var before=new ModTrafficCounter(()->0);var after=new ModTrafficCounter(()->0);
        ModTrafficProbe.clientCollector(old,before);
        ModTrafficProbe.endpoint(serverSide,true,500);ModTrafficProbe.endpoint(old,true,50);ModTrafficProbe.endpoint(old,false,100);
        assertEquals(150,before.sample().categories().get(TrafficCategory.NETPLAY).totalBytes());
        ModTrafficProbe.clientCollector(fresh,after);ModTrafficProbe.endpoint(old,true,1000);ModTrafficProbe.endpoint(old,false,1000);
        ModTrafficProbe.endpoint(fresh,false,25);assertEquals(25,after.sample().totalBytes());assertEquals(150,before.sample().totalBytes());
        ModTrafficProbe.clientCollector(fresh,null);ModTrafficProbe.endpoint(fresh,true,1000);assertEquals(25,after.sample().totalBytes());
    }
    @Test void integratedServerAndRemoteClientTotalsStayIndependent(){
        var server=new ServerTrafficMeter.Session(()->0);ServerTrafficMeter.install(server);
        Object connection=new Object();var client=new ModTrafficCounter(()->0);ModTrafficProbe.clientCollector(connection,client);
        ModTrafficProbe.endpoint(connection,true,125);ServerTrafficMeter.netplay(false,125);
        ServerTrafficMeter.netplay(true,300);ModTrafficProbe.endpoint(connection,false,300);
        assertEquals(125,client.sample().uploadedBytes());assertEquals(300,client.sample().downloadedBytes());
        assertEquals(300,server.total().uploadedBytes());assertEquals(125,server.total().downloadedBytes());
        assertEquals(425,server.group(ServerTrafficMeter.Group.NETPLAY).totalBytes());
        assertEquals(0,server.group(ServerTrafficMeter.Group.FC_HOME).totalBytes());
    }
    @Test void countersPartitionExactlyAndResetOnlyMeasurement(){
        var clock=new AtomicLong();var counter=new ModTrafficCounter(clock::get);int i=1;
        for(var c:TrafficCategory.values()){counter.upload(c,i*1024);counter.download(c,i*512);i++;}
        clock.set(1_000_000_000L);var s=counter.sample();
        assertEquals(s.uploadBytesPerSecond(),s.categories().values().stream().mapToDouble(ModTrafficCounter.CategoryRate::up).sum());
        assertEquals(s.totalBytes(),s.categories().values().stream().mapToLong(ModTrafficCounter.CategoryRate::totalBytes).sum());
        counter.upload(TrafficCategory.NETPLAY,50);assertEquals(1074,counter.sample().categories().get(TrafficCategory.NETPLAY).uploaded());
        counter.reset();s=counter.sample();assertEquals(0,s.totalBytes());assertEquals(0,s.uploadBytesPerSecond());assertTrue(s.streams().isEmpty());
        counter.download(TrafficCategory.MEDIA,200);assertEquals(200,counter.sample().totalBytes());
        clock.set(2_000_000_000L);assertEquals(200,counter.sample().categories().get(TrafficCategory.MEDIA).down());
        clock.set(3_000_000_000L);assertEquals(0,counter.sample().downloadBytesPerSecond());assertEquals(200,counter.sample().totalBytes());
    }
    @Test void categoriesCoverNativeMediaDownloadsButNeverMisclassifyInputFramesAsVideo(){
        for(String id:new String[]{"netplay_data","netplay_state","netplay_gun","watch_netplay","cabinet_room_netplay_start"})assertCategory("piq_fc_arcade:"+id,TrafficCategory.NETPLAY);
        for(String id:new String[]{"watch_media","watch_stream","cabinet_room_stream","cabinet_room_media","home_hosted_stream"})assertCategory("piq_fc_arcade:"+id,TrafficCategory.MEDIA);
        assertCategory("piq_sfc_home:hosted_stream",TrafficCategory.MEDIA);
        for(String id:new String[]{"rom_download_chunk","skin_upload_chunk","game_reply","cartridge_request"})assertCategory("piq_fc_arcade:"+id,TrafficCategory.FILES);
        for(String id:new String[]{"editor_action","rom_chunk","cover_chunk"})assertCategory("piq_sfc_home:"+id,TrafficCategory.FILES);
        for(String id:new String[]{"arcade_frame","cabinet_sync_frames","cabinet_sync_restore","cartridge_save_reply"})assertCategory("piq_fc_arcade:"+id,TrafficCategory.OTHER);
        assertCategory("another_mod:netplay_data",TrafficCategory.OTHER);
    }
    @Test void classifiedCodecPreservesBytesAndCountsOnlyItsClientDirection(){
        var counter=new ModTrafficCounter(()->0);ModTrafficProbe.clientCollector(counter);
        var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        StreamCodec<RegistryFriendlyByteBuf,Integer> raw=StreamCodec.of((out,n)->out.writeInt(n),RegistryFriendlyByteBuf::readInt);
        try{
            var up=ModTrafficProbe.toServer(raw,TrafficCategory.FILES);up.encode(b,42);assertEquals(42,up.decode(b));
            assertEquals(4,counter.sample().uploadedBytes());assertEquals(0,counter.sample().downloadedBytes());
            var down=ModTrafficProbe.toClient(raw,TrafficCategory.MEDIA);down.encode(b,99);assertEquals(99,down.decode(b));
            assertEquals(4,counter.sample().categories().get(TrafficCategory.FILES).uploaded());
            assertEquals(4,counter.sample().categories().get(TrafficCategory.MEDIA).downloaded());
            assertThrows(RuntimeException.class,()->down.decode(b));assertEquals(8,counter.sample().totalBytes());
        }finally{b.release();}
    }
    @Test void productionHooksAreDirectionAwareMemoryExcludedAndDoNotMeterNativeIpc()throws Exception{
        var net=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/netplay/NetplayNetwork.java"));
        var transport=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/netplay/NetplayTransport.java"));
        assertTrue(net.contains("if(!source.isMemoryConnection())"));assertTrue(net.contains("ctx.flow().isServerbound()"));
        assertTrue(net.contains("endpoint(source,false,packet.encodedBytes())"));
        assertTrue(transport.indexOf("endpoint(connection,true,data.encodedBytes())")>transport.indexOf("connection.send(new ServerboundCustomPayloadPacket(data))"));
        assertTrue(transport.contains("if(!connection.isMemoryConnection())"));
        var nativeBridge=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/netplay/NetplayProcess.java"));
        assertFalse(nativeBridge.contains("ModTrafficProbe"));
    }
    private static void assertCategory(String id,TrafficCategory expected){assertEquals(expected,TrafficCategory.of(ResourceLocation.parse(id)),id);}
}
