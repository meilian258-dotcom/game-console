package cn.piq.sfchome.client;

import cn.piq.fcarcade.client.privateplay.PrivateSaveStore;
import cn.piq.sfcarcade.core.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SfcPrivateEngineTest {
    private static final String NAMESPACE="sfc-private-test-v1/"+"a".repeat(64);
    @TempDir Path temp;
    private Path rom()throws Exception{Path file=temp.resolve("fixture.sfc");Files.write(file,new byte[32768]);return file;}
    private PrivateSaveStore.Key key(){return new PrivateSaveStore.Key("sfc",NAMESPACE,SfcRomImage.fromBytes(new byte[32768]).sha256());}
    private PrivateSaveStore store(){return new PrivateSaveStore(temp.resolve("saves"));}
    private SfcPrivateEngine open(Fake core)throws Exception{return new SfcPrivateEngine(rom(),temp.resolve("saves"),()->core,NAMESPACE);}
    private static void until(BooleanSupplier condition)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static void close(SfcPrivateEngine engine)throws Exception{
        engine.stopAndSave().get(5,TimeUnit.SECONDS);assertFalse(SfcCoreLease.occupied());
    }
    static final class Fake implements SfcCore {
        final CopyOnWriteArrayList<Integer> inputs=new CopyOnWriteArrayList<>();
        final CountDownLatch closing=new CountDownLatch(1),allowClose=new CountDownLatch(1);
        final AtomicInteger frames=new AtomicInteger();
        volatile boolean failFrame,stallClose,restoreMismatch;
        volatile int restored=-1;
        volatile Thread owner;
        byte[] sram={7};
        double fps=50;
        private void owned(){if(owner==null)owner=Thread.currentThread();else if(owner!=Thread.currentThread())throw new AssertionError("Cross-thread core access");}
        public String backendName(){owned();return "test";}
        public void loadRom(SfcRomImage rom){owned();}
        public SfcFrameResult runFrame(SfcControllerState a,SfcControllerState b){
            owned();if(failFrame)throw new IllegalStateException("Fixture failure");
            if(b.mask()!=0)throw new AssertionError("Private P2 must stay neutral");
            inputs.add(a.mask());return new SfcFrameResult(new SfcVideoMode(2,1,8,1,fps),2,frames.incrementAndGet());
        }
        public void copyRgbaFrame(byte[] bytes){owned();for(int i=0;i<8;i++)bytes[i]=(byte)i;}
        public int copyAudioPcm16(short[] pcm){owned();pcm[0]=1;pcm[1]=2;pcm[2]=3;pcm[3]=4;return 2;}
        public byte[] saveState(){owned();return ByteBuffer.allocate(4).putInt(frames.get()+(restoreMismatch&&restored>=0?1:0)).array();}
        public void loadState(byte[] state){owned();restored=ByteBuffer.wrap(state).getInt();frames.set(restored);}
        public byte[] saveSram(){owned();return sram.clone();}
        // Like original core9, loading SRAM recreates the emulator instead of only copying RAM.
        public void loadSram(byte[] bytes){owned();sram=bytes.clone();frames.set(0);}
        public void reset(boolean hard){owned();frames.set(0);inputs.clear();}
        public void close(){owned();closing.countDown();while(stallClose&&allowClose.getCount()>0)try{allowClose.await();}catch(InterruptedException ignored){Thread.interrupted();}}
    }
    @Test void actualWorkerPacesPalAndKeepsFastP1EdgesAndStereoAudio()throws Exception{
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{
            until(engine::isReady);assertEquals(1,engine.maxPlayers());assertNotEquals(Thread.currentThread(),core.owner);
            engine.offerInput(0,0);engine.offerInput(256,0);engine.offerInput(0,0);
            until(()->core.inputs.contains(256));until(()->core.inputs.lastIndexOf(0)>core.inputs.indexOf(256));
            Thread.sleep(50);var frame=engine.pollFrame();assertNotNull(frame);
            assertEquals(0xff020100,frame.abgr()[0]);assertEquals(2f,frame.displayAspect());
            assertTrue(frame.pcm48k().length>=4);assertEquals(0,frame.pcm48k().length%4);
            assertThrows(IllegalArgumentException.class,()->engine.offerInput(1,1));
            assertThrows(IllegalArgumentException.class,()->engine.offerInput(4096,0));
            assertThrows(IllegalArgumentException.class,()->engine.releasePort(1));
        }finally{close(engine);}
        assertTrue(engine.stopAndSave().get().saved());assertTrue(store().load(key()).isPresent());
    }
    @Test void pauseDoesNotAdvanceAndResumeRequiresPhysicalNeutralBeforePress()throws Exception{
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{
            until(engine::isReady);engine.offerInput(0,0);engine.offerInput(1,0);until(()->core.inputs.contains(1));
            engine.paused(true);Thread.sleep(35);int frame=core.frames.get();Thread.sleep(60);
            assertEquals(frame,core.frames.get());assertNull(engine.pollFrame());
            engine.offerInput(1,0);engine.paused(false);engine.offerInput(1,0);
            int from=core.inputs.size();until(()->core.inputs.size()>=from+3);
            assertTrue(core.inputs.subList(from,core.inputs.size()).stream().allMatch(x->x==0));
            engine.offerInput(0,0);engine.offerInput(1,0);until(()->core.inputs.getLast()==1);
            engine.clearInput();engine.offerInput(1,0);Thread.sleep(60);assertEquals(0,core.inputs.getLast());
        }finally{close(engine);}
    }
    @Test void stopWhilePausedSavesImmediatelyWithoutExtraEmulatedFrames()throws Exception{
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{
            until(engine::isReady);engine.paused(true);Thread.sleep(35);int frames=core.frames.get();
            assertTrue(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());
            assertEquals(frames,ByteBuffer.wrap(store().load(key()).orElseThrow().state()).getInt());
        }finally{close(engine);}
    }
    @Test void initializationCanBecomeReadyWhilePausedBeforeTheFirstGameplayFrame()throws Exception{
        CountDownLatch creating=new CountDownLatch(1),proceed=new CountDownLatch(1);Fake core=new Fake();
        SfcPrivateEngine engine=new SfcPrivateEngine(rom(),temp.resolve("saves"),()->{
            creating.countDown();try{proceed.await();}catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}return core;
        },NAMESPACE);
        try{
            assertTrue(creating.await(3,TimeUnit.SECONDS));engine.paused(true);proceed.countDown();
            until(engine::isReady);Thread.sleep(40);assertEquals(0,core.frames.get());assertNull(engine.pollFrame());
            assertTrue(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());
            assertEquals(0,ByteBuffer.wrap(store().load(key()).orElseThrow().state()).getInt());
        }finally{proceed.countDown();close(engine);}
    }
    @Test void restoresStateAndSramFromPrivateNamespaceOnOwningWorker()throws Exception{
        store().save(key(),ByteBuffer.allocate(4).putInt(42).array(),new byte[]{9});
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{until(engine::isReady);assertEquals(42,core.restored);assertArrayEquals(new byte[]{9},core.sram);until(()->core.frames.get()>42);}
        finally{close(engine);}
        assertTrue(ByteBuffer.wrap(store().load(key()).orElseThrow().state()).getInt()>42);
    }
    @Test void restoreRoundTripMismatchStopsWithoutOverwritingOriginal()throws Exception{
        byte[] original=ByteBuffer.allocate(4).putInt(42).array();store().save(key(),original,new byte[]{9});
        Fake core=new Fake();core.restoreMismatch=true;SfcPrivateEngine engine=open(core);
        try{until(()->engine.error()!=null);assertFalse(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());
            assertArrayEquals(original,store().load(key()).orElseThrow().state());assertFalse(engine.isReady());}
        finally{close(engine);}
    }
    @Test void jniTrialBackupNeverImportsOrOverwritesNormalPrivateNamespace()throws Exception{
        var process=cn.piq.retro.libretro.LibretroRuntimes.Backend.PROCESS;
        var trial=cn.piq.retro.libretro.LibretroRuntimes.Backend.JNI_TRIAL;
        String ordinaryNamespace=SfcPrivateEngine.saveNamespace(process),trialNamespace=SfcPrivateEngine.saveNamespace(trial);
        assertEquals(cn.piq.sfchome.core.LibretroSfcCore.saveNamespace(),ordinaryNamespace);
        assertEquals("jni-trial-v1/"+ordinaryNamespace,trialNamespace);
        String hash=SfcRomImage.fromBytes(new byte[32768]).sha256();
        var ordinaryKey=new PrivateSaveStore.Key("sfc",ordinaryNamespace,hash);
        var trialKey=new PrivateSaveStore.Key("sfc",trialNamespace,hash);
        byte[] original=ByteBuffer.allocate(4).putInt(91).array();store().save(ordinaryKey,original,new byte[]{9});
        Fake first=new Fake();var running=new SfcPrivateEngine(rom(),temp.resolve("saves"),()->first,trialNamespace);
        try{until(running::isReady);assertEquals(-1,first.restored);}finally{close(running);}
        assertArrayEquals(original,store().load(ordinaryKey).orElseThrow().state());
        byte[] saved=store().load(trialKey).orElseThrow().state();Fake second=new Fake();
        var resumed=new SfcPrivateEngine(rom(),temp.resolve("saves"),()->second,trialNamespace);
        try{until(resumed::isReady);assertEquals(ByteBuffer.wrap(saved).getInt(),second.restored);}finally{close(resumed);}
        assertArrayEquals(original,store().load(ordinaryKey).orElseThrow().state());
    }
    @Test void restoreInstallsRecreatingSramBeforeExactStateIncludingEmptySram()throws Exception{
        for(byte[] sram:new byte[][]{new byte[0],new byte[]{9}}){
            byte[] original=ByteBuffer.allocate(4).putInt(42).array();store().save(key(),original,sram);
            CountDownLatch creating=new CountDownLatch(1),proceed=new CountDownLatch(1);Fake core=new Fake();
            SfcPrivateEngine engine=new SfcPrivateEngine(rom(),temp.resolve("saves"),()->{
                creating.countDown();try{proceed.await();}catch(InterruptedException interrupted){throw new IllegalStateException(interrupted);}return core;
            },NAMESPACE);
            try{
                assertTrue(creating.await(3,TimeUnit.SECONDS));engine.paused(true);proceed.countDown();until(engine::isReady);
                assertEquals(42,core.frames.get());assertArrayEquals(sram,core.sram);
                assertTrue(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());
                assertArrayEquals(original,store().load(key()).orElseThrow().state());
                assertArrayEquals(sram,store().load(key()).orElseThrow().sram());
            }finally{proceed.countDown();close(engine);}
        }
    }
    @Test void corruptPrivateSaveStopsBeforeCoreConstructionAndIsNotReplaced()throws Exception{
        store().save(key(),new byte[]{1},new byte[0]);
        Path archive;try(var files=Files.walk(temp.resolve("saves"))){archive=files.filter(p->p.getFileName().toString().equals("latest.zip")).findFirst().orElseThrow();}
        byte[] corrupt={1,2,3};Files.write(archive,corrupt);AtomicInteger opened=new AtomicInteger();
        SfcPrivateEngine engine=new SfcPrivateEngine(rom(),temp.resolve("saves"),()->{opened.incrementAndGet();return new Fake();},NAMESPACE);
        try{until(()->engine.error()!=null);assertFalse(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());assertEquals(0,opened.get());assertArrayEquals(corrupt,Files.readAllBytes(archive));}
        finally{close(engine);}
    }
    @Test void coreFailurePreservesPriorPrivateSave()throws Exception{
        byte[] original=ByteBuffer.allocate(4).putInt(42).array();store().save(key(),original,new byte[]{7});
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{until(engine::isReady);core.failFrame=true;until(()->engine.error()!=null);
            assertFalse(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());assertArrayEquals(original,store().load(key()).orElseThrow().state());}
        finally{close(engine);}
    }
    @Test void saveFailureIsNotReportedAsSuccessAndRetainsKnownGoodLatest()throws Exception{
        Fake core=new Fake();SfcPrivateEngine engine=open(core);
        try{
            until(engine::isReady);store().save(key(),new byte[]{5},new byte[0]);
            Path archive;try(var files=Files.walk(temp.resolve("saves"))){archive=files.filter(p->p.getFileName().toString().equals("latest.zip")).findFirst().orElseThrow();}
            Files.write(archive.resolveSibling("previous.zip"),new byte[]{3});
            byte[] original=Files.readAllBytes(archive);
            assertFalse(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());assertArrayEquals(original,Files.readAllBytes(archive));
        }finally{close(engine);}
    }
    @Test void stopIsNonblockingAndCompletesOnlyAfterActualTeardownAndLeaseRelease()throws Exception{
        Fake core=new Fake();core.stallClose=true;SfcPrivateEngine engine=open(core);
        try{
            until(engine::isReady);long start=System.nanoTime();var future=engine.stopAndSave();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<100);
            assertTrue(core.closing.await(3,TimeUnit.SECONDS));assertFalse(future.isDone());assertTrue(SfcCoreLease.occupied());
            assertSame(future,engine.stopAndSave());
            assertThrows(IllegalStateException.class,()->new SfcPrivateEngine(temp.resolve("other.sfc"),temp.resolve("other"),Fake::new,NAMESPACE));
        }finally{core.allowClose.countDown();close(engine);}
    }
    @Test void missingRomDoesNotDownloadOrCreatePrivateSave()throws Exception{
        AtomicInteger opened=new AtomicInteger();
        SfcPrivateEngine engine=new SfcPrivateEngine(temp.resolve("missing.sfc"),temp.resolve("saves"),()->{opened.incrementAndGet();return new Fake();},NAMESPACE);
        try{until(()->engine.error()!=null);assertFalse(engine.stopAndSave().get(5,TimeUnit.SECONDS).saved());assertEquals(0,opened.get());assertFalse(Files.exists(temp.resolve("saves")));}
        finally{close(engine);}
    }
    @Test void privateEngineHasNoPublicNetworkWatchOrSessionIntegration()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcPrivateEngine.java"));
        for(String forbidden:new String[]{"PacketDistributor","SfcHomeNetwork","SfcJoinClient","SfcRepairClient","SfcWatchPublisher","SfcPlayback","RomRequest","SfcRecoveryBackups"})assertFalse(source.contains(forbidden),forbidden);
        assertTrue(source.contains("LibretroSfcCore.saveNamespace()"));assertTrue(source.contains("new LibretroSfcCore(backend)"));
        assertTrue(source.contains("this(localRom,saveRoot,LibretroRuntimes.Backend.JNI_TRIAL)"));
        assertNotEquals(SfcPrivateEngine.saveNamespace(cn.piq.retro.libretro.LibretroRuntimes.Backend.PROCESS),
                SfcPrivateEngine.saveNamespace(cn.piq.retro.libretro.LibretroRuntimes.Backend.JNI_TRIAL));
        assertTrue(source.contains("store.load(key)"));assertTrue(source.contains("store.save(key,core.saveState(),core.saveSram())"));
        assertTrue(source.contains("lease.close();stopped.complete(result)"));
    }
}
