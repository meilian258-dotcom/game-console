package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual session's file routing, cancellation and save gate without native code. */
class JniNetplayFileContentTest {
    @TempDir Path temp;
    private NetplayProfile profile(){
        var runtime=new LibretroProfile("Test","zip",true,List.of(1,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact("/core/test.dll","a".repeat(64))));
        return new NetplayProfile(getClass(),"/core/test.dll","a".repeat(64),"game.zip",Map.of(),1,48000,LibretroContentFiles.MAX_MAIN,2).withJni(runtime);
    }
    private LibretroContentFiles content()throws Exception {
        Path main=temp.resolve("source"),bios=temp.resolve("bios");Files.write(main,new byte[32]);Files.write(bios,new byte[]{1,2,3});
        return LibretroContentFiles.inspect("game.zip",Map.of("game.zip",main,"neogeo.zip",bios),LibretroContentFiles.MAX_MAIN,16*1024*1024,()->{});
    }
    private JniNetplaySession session(java.util.function.Supplier<LibretroRuntime> core){
        return new JniNetplaySession(new NetplayProcess.Grant(995,UUID.randomUUID(),true,true,0),
            ()->{throw new AssertionError("File adapter must not read the ROM as byte[]");},p->{},false,core,profile(),
            ()->{throw new AssertionError("File adapter must not clone BIOS byte[]");},null,true);
    }
    private static class Saves implements NetplayProcess.Persistence {
        final AtomicInteger loads=new AtomicInteger(),writes=new AtomicInteger(),aborts=new AtomicInteger();
        NetplaySaveState.Identity identity;
        public byte[] load(NetplaySaveState.Identity i){loads.incrementAndGet();identity=i;return null;}
        public CompletableFuture<Void> save(byte[] bytes){writes.incrementAndGet();return CompletableFuture.completedFuture(null);}
        public void abort(){aborts.incrementAndGet();}
    }
    private static void ready(JniNetplaySession run)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!run.ready()&&run.error()==null&&System.nanoTime()<end)Thread.sleep(2);
        assertNull(run.error(),run.diagnostic());assertTrue(run.ready(),run.diagnostic());
    }
    @Test void actualOwnerUsesVerifiedFilesAndSameSaveIdentityThenWaitsForAuthority()throws Exception {
        var files=content();var saves=new Saves();Thread caller=Thread.currentThread();
        var core=new JniNetplaySessionTest.Core(){
            public LibretroProcess.Info loadFiles(LibretroContentFiles value,LibretroContentFiles.Check check){
                owner=Thread.currentThread();assertNotSame(caller,owner);assertSame(files,value);assertEquals(0,saves.loads.get());
                try{value.stage(Files.createDirectory(temp.resolve("stage")),check);}catch(IOException e){throw new UncheckedIOException(e);}
                return info();
            }
        };
        var run=session(()->core);run.persistence(saves);run.fileContent(files);run.start();
        try{
            ready(run);assertFalse(run.active());assertFalse(run.canSave());assertEquals(0,run.framesReceived());
            assertEquals(NetplaySaveState.identity(profile(),files.main().sha256(),files.auxiliaryHashes()),saves.identity);
            assertEquals(1,saves.loads.get());assertEquals(0,saves.writes.get());
        }finally{run.close();run.terminated().get(5,TimeUnit.SECONDS);}
        assertTrue(core.closed);assertEquals(1,saves.aborts.get());assertEquals(0,saves.writes.get());
    }
    @Test void changedFileNeverOpensASaveLeaseOrOverwritesExistingProgress()throws Exception {
        var files=content();Files.write(temp.resolve("source"),new byte[33]);var saves=new Saves();
        var core=new JniNetplaySessionTest.Core(){
            public LibretroProcess.Info loadFiles(LibretroContentFiles value,LibretroContentFiles.Check check){
                owner=Thread.currentThread();try{value.stage(Files.createDirectory(temp.resolve("changed")),check);return info();}
                catch(IOException e){throw new UncheckedIOException(e);}
            }
        };
        var run=session(()->core);run.persistence(saves);run.fileContent(files);run.start();run.terminated().get(5,TimeUnit.SECONDS);
        assertNotNull(run.error());assertFalse(run.ready());assertTrue(core.closed);
        assertEquals(0,saves.loads.get());assertEquals(0,saves.writes.get());assertEquals(1,saves.aborts.get());
    }
    @Test void cancelDuringStagingReleasesOwnerAndNeverReadsOrWritesProgress()throws Exception {
        var files=content();var saves=new Saves();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var core=new JniNetplaySessionTest.Core(){
            public LibretroProcess.Info loadFiles(LibretroContentFiles value,LibretroContentFiles.Check check){
                owner=Thread.currentThread();entered.countDown();
                try{assertTrue(release.await(5,TimeUnit.SECONDS));check.run();throw new AssertionError("Cancellation was ignored");}
                catch(IOException e){throw new UncheckedIOException(e);}catch(InterruptedException e){throw new AssertionError(e);}
            }
        };
        var run=session(()->core);run.persistence(saves);run.fileContent(files);run.start();
        assertTrue(entered.await(5,TimeUnit.SECONDS));run.close();release.countDown();run.terminated().get(5,TimeUnit.SECONDS);
        assertTrue(core.closed);assertEquals(0,saves.loads.get());assertEquals(0,saves.writes.get());assertFalse(run.ready());
    }
    @Test void oldRuntimeFailsExplicitlyInsteadOfSilentlyDroppingNamedFiles()throws Exception {
        var core=new JniNetplaySessionTest.Core();var saves=new Saves();var run=session(()->{core.owner=Thread.currentThread();return core;});
        run.persistence(saves);run.fileContent(content());run.start();run.terminated().get(5,TimeUnit.SECONDS);
        assertTrue(run.error().contains("no verified-file adapter"),run.diagnostic());assertTrue(core.closed);
        assertEquals(0,saves.loads.get());assertEquals(0,saves.writes.get());
    }
}
