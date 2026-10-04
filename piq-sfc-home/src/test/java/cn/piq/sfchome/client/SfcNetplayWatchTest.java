package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcHomeNetwork;
import java.nio.file.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcNetplayWatchTest {
    private static final String SHA="a".repeat(64);
    private static SfcHomeNetwork.RomChunk chunk(int total,int offset,int size){return new SfcHomeNetwork.RomChunk(SHA,total,offset,new byte[size]);}
    @Test void boundedDownloadAssemblesOnlyCompleteOrderedGame(){
        var d=new SfcNetplayWatchContent.Download(SHA,null);
        d.accept(chunk(65536,0,32768));assertFalse(d.result.isDone());
        d.accept(chunk(65536,32768,32768));assertArrayEquals(new byte[65536],d.result.join());assertNull(d.bytes);
    }
    @Test void badOffsetAndChangedTotalFailClosed(){
        var d=new SfcNetplayWatchContent.Download(SHA,null);d.accept(chunk(65536,100,32768));assertTrue(d.result.isCompletedExceptionally());
        d=new SfcNetplayWatchContent.Download(SHA,null);d.accept(chunk(65536,0,32768));d.accept(chunk(65537,32768,32768));assertTrue(d.result.isCompletedExceptionally());
    }
    @Test void cancelledPreparationCannotAcceptLateChunks(){
        var d=new SfcNetplayWatchContent.Download(SHA,null);d.accept(chunk(65536,0,32768));d.cancel();
        d.accept(chunk(65536,32768,32768));assertTrue(d.result.isCancelled());assertNull(d.bytes);
    }
    @Test void independentDownloadDoesNotReusePreviousPartialBytes(){
        var old=new SfcNetplayWatchContent.Download(SHA,null);old.accept(chunk(65536,0,32768));old.cancel();
        var fresh=new SfcNetplayWatchContent.Download(SHA,null);assertEquals(0,fresh.at);assertNull(fresh.bytes);
        fresh.accept(chunk(32768,0,32768));assertEquals(32768,fresh.result.join().length);
    }
    @Test void productionSeparatesNativeObserversFromControlsAndLegacySnapshotWatch()throws Exception{
        var base=Path.of("src/main/java/cn/piq/sfchome");
        String server=Files.readString(base.resolve("server/SfcHomeServer.java"));
        String provider=Files.readString(base.resolve("server/SfcWatchProvider.java"));
        String legacy=Files.readString(base.resolve("server/SfcLocalWatchServer.java"));
        String client=Files.readString(base.resolve("client/SfcNetplayWatchContent.java"));
        assertTrue(server.contains("WatchNetplay.connections(server,s.watchSource)"));
        assertTrue(server.contains("NETPLAY.containsKey(s)||s.mode!="));
        assertTrue(provider.contains("SfcLocalWatchServer.netplayAllowed(p)"));assertTrue(legacy.contains("WatchNetplay.observing(p)"));
        for(String prohibited:Arrays.asList("SfcCoreLease.acquire(","SfcControllerData", "ControllerReady", "ControllerInput", "saveState"))assertFalse(client.contains(prohibited),prohibited);
        assertTrue(client.contains("SfcHomeNetwork::requestRom"));assertTrue(client.contains("SfcClientFiles.hash(bytes).equals(pending.hash)"));
        assertTrue(client.contains("get(60,TimeUnit.SECONDS)"));assertTrue(client.contains("c.getConnection()==connection"));
        assertTrue(client.contains("DOWNLOADS.request(pending.hash,connection)"));
    }
}
