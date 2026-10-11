package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetOperatingFiles;
import cn.piq.fcarcade.netplay.*;
import cn.piq.retro.libretro.*;
import cn.piq.retro.replay.ReplayGuard;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CabinetOperatingClientTest {
    @TempDir Path root;
    final NetplaySaveState.Identity game=new NetplaySaveState.Identity("a".repeat(64),"b".repeat(64));
    final Channel channel=new Channel();
    private static final class Channel implements NetplayProcess.Persistence {
        int saves,finishes,aborts;byte[] saved;Runnable onSave=()->{};
        public byte[] load(NetplaySaveState.Identity id){return null;}
        public CompletableFuture<Void> save(byte[] bytes){saves++;saved=bytes;onSave.run();return CompletableFuture.completedFuture(null);}
        public CompletableFuture<Void> finish(){finishes++;return CompletableFuture.completedFuture(null);}
        public void abort(){aborts++;}
    }
    @BeforeEach void before(){ReplayGuard.exitReplay();}
    @AfterEach void after(){ReplayGuard.exitReplay();}
    CabinetOperatingClient client(){String sha="5432".repeat(16);CabinetOperatingFiles.registerFbneoJni(sha);
        var p=new NetplayProfile(getClass(),"/native-runtime/test.dll",sha,"kov.zip",Map.of(),1,48000,1024,4)
                .withJni(new LibretroProfile("FinalBurn Neo","zip",true,List.of(1,1,1,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact("/native-runtime/test.dll",sha))));
        return new CabinetOperatingClient(channel,p);
    }
    LibretroOperatingFile file(CabinetOperatingClient c)throws Exception {var f=c.prepare(game);f.prepare(root);Files.write(root.resolve("fbneo/kov.fs"),new byte[]{1,2,3});return f;}
    @Test void authorizedConfirmedNormalCloseUploadsSeparateEnvelopeAndFinishesOnce()throws Exception {
        var c=client();var f=file(c);c.authorize();c.normalClose(true);assertEquals(0,channel.saves);f.closed(root);c.abort();
        assertEquals(1,channel.saves);assertEquals(1,channel.finishes);assertEquals(0,channel.aborts);
        assertArrayEquals(new byte[]{1,2,3},CabinetOperatingFiles.unpack(CabinetOperatingFiles.identity(game),channel.saved));
    }
    @Test void unprivilegedNormalCloseFinishesReadOnlyWithoutUpload()throws Exception {
        var c=client();var f=file(c);c.normalClose(true);f.closed(root);c.abort();assertEquals(0,channel.saves);assertEquals(1,channel.finishes);
    }
    @Test void beforeReadyOrDiscardedOrNativeFailureNeverWrites()throws Exception {
        var c=client();var f=file(c);c.authorize();f.closed(root);c.normalClose(false);c.abort();
        assertEquals(0,channel.saves);assertEquals(0,channel.finishes);assertEquals(1,channel.aborts);
    }
    @Test void replayAdmissionCancelsOldOwnerEvenAfterReplayExit()throws Exception {
        var c=client();var f=file(c);c.authorize();c.normalClose(true);ReplayGuard.enterReplay();ReplayGuard.exitReplay();
        f.closed(root);c.abort();assertEquals(0,channel.saves);assertEquals(0,channel.finishes);assertEquals(1,channel.aborts);
    }
    @Test void replayWhileClosingNeverUploads()throws Exception {
        var c=client();var f=file(c);c.authorize();c.normalClose(true);ReplayGuard.enterReplay();
        f.closed(root);c.abort();assertEquals(0,channel.saves);assertEquals(0,channel.finishes);assertEquals(1,channel.aborts);
    }
    @Test void epochChangesWhileAwaitingUploadAckCannotFinishAsSuccessful()throws Exception {
        var c=client();var f=file(c);c.authorize();c.normalClose(true);channel.onSave=ReplayGuard::enterReplay;
        assertThrows(java.io.IOException.class,()->f.closed(root));c.normalClose(false);c.abort();
        assertEquals(1,channel.saves);assertEquals(0,channel.finishes);assertEquals(1,channel.aborts);
    }
    @Test void preparationNeverCompletedUsesAbortNotSaveOrFinish(){var c=client();c.authorize();c.normalClose(true);c.abort();assertEquals(0,channel.saves);assertEquals(0,channel.finishes);assertEquals(1,channel.aborts);}
}
