package cn.piq.sfchome.server;

import cn.piq.fcarcade.home.content.ContentScanReport;
import cn.piq.sfchome.net.SfcHomeNetwork;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcEditorCatalogTest {
    @TempDir Path temp;
    private static final String HASH="a".repeat(64);
    private static final SfcHomeNetwork.RomEntry OLD=new SfcHomeNetwork.RomEntry(HASH,"已加载.sfc",32768);
    @Test void realRomFailureDoesNotDiscardSuccessfulCoverScan()throws Exception{
        Path obstruction=temp.resolve("roms");Files.writeString(obstruction,"not a directory");
        var result=SfcEditorCatalog.scan(new SfcRomStore(obstruction)::scan,()->List.of(HASH),List.of(OLD),List.of(),true);
        assertEquals(List.of(OLD),result.roms());assertEquals(List.of(HASH),result.covers());
        assertTrue(result.status().contains("ROM读取失败"));assertTrue(result.status().contains("未重新验证"));
        assertTrue(result.status().contains("封面：1 项可用"));assertEquals("not a directory",Files.readString(obstruction));
    }
    @Test void realCoverFailureDoesNotDiscardRomOrPartialDiagnostics()throws Exception{
        Path root=Files.createDirectory(temp.resolve("roms"));Files.write(root.resolve("正常游戏.sfc"),new byte[32768]);
        Files.write(root.resolve("坏文件.sfc"),new byte[]{1});Path cover=temp.resolve("covers");Files.writeString(cover,"blocked");
        var result=SfcEditorCatalog.scan(new SfcRomStore(root)::scan,new SfcCoverStore(cover)::list,List.of(),List.of(HASH),true);
        assertEquals(1,result.roms().size());assertEquals("正常游戏.sfc",result.roms().getFirst().fileName());
        assertEquals(List.of(HASH),result.covers());assertTrue(result.status().contains("1 项被拒绝"));
        assertTrue(result.status().contains("封面读取失败"));assertTrue(Files.exists(root.resolve("坏文件.sfc")));
    }
    @Test void scanningLimitRemainsHardAndCoverStillLoads()throws Exception{
        Path root=Files.createDirectory(temp.resolve("many"));for(int i=0;i<513;i++)Files.createFile(root.resolve(i+".txt"));
        var result=SfcEditorCatalog.scan(new SfcRomStore(root)::scan,()->List.of(HASH),List.of(),List.of(),true);
        assertTrue(result.roms().isEmpty());assertEquals(List.of(HASH),result.covers());
        assertTrue(result.status().contains("超过扫描上限"));try(var files=Files.list(root)){assertEquals(513,files.count());}
    }
    @Test void deniedCoverAccessDoesNotScanOrReturnCachedCoverEntries(){
        AtomicInteger calls=new AtomicInteger();
        var result=SfcEditorCatalog.scan(()->new ContentScanReport<>(List.of(OLD),List.of(),List.of()),()->{calls.incrementAndGet();return List.of(HASH);},List.of(),List.of(HASH),false);
        assertEquals(0,calls.get());assertTrue(result.covers().isEmpty());assertEquals(List.of(OLD),result.roms());
        assertTrue(result.status().contains("无服务器封面使用权限"));
    }
    @Test void revokedPermissionBeforeReplyDoesNotAdvertiseSuccessfulCoverResult(){
        var result=SfcEditorCatalog.scan(()->new ContentScanReport<>(List.of(),List.of(),List.of()),()->List.of(HASH),List.of(),List.of(),true);
        assertTrue(result.status().contains("封面：1 项可用"));assertTrue(result.status(false).contains("无服务器封面使用权限"));
        assertFalse(result.status(false).contains("封面：1 项可用"));
    }
    @Test void repeatedFailureKeepsSnapshotAndRecoveryReplacesIt(){
        var broken=SfcEditorCatalog.scan(()->{throw new IOException("故障");},List::of,List.of(OLD),List.of(),true);
        var again=SfcEditorCatalog.scan(()->{throw new IOException("仍有故障");},List::of,broken.roms(),broken.covers(),true);
        assertEquals(List.of(OLD),again.roms());assertTrue(again.status().contains("仍有故障"));
        var recovered=SfcEditorCatalog.scan(()->new ContentScanReport<>(List.of(),List.of(),List.of()),List::of,again.roms(),again.covers(),true);
        assertTrue(recovered.roms().isEmpty());assertFalse(recovered.status().contains("读取失败"));assertTrue(recovered.status().contains("ROM：0 项可用"));
    }
    @Test void bothDirectoryErrorsRemainDistinctWithinWireBudget(){
        String longError="失败\n".repeat(200);
        var result=SfcEditorCatalog.scan(()->{throw new IOException(longError);},()->{throw new IOException(longError);},List.of(),List.of(),true);
        assertTrue(result.status().contains("ROM读取失败"));assertTrue(result.status().contains("封面读取失败"));
        assertTrue(result.status().length()<=256);assertFalse(result.status().contains("\n"));
    }
}
