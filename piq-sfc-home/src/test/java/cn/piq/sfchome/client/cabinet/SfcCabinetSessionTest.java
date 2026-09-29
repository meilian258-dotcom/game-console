package cn.piq.sfchome.client.cabinet;
import cn.piq.sfcarcade.core.*;
import cn.piq.sfchome.client.SfcCoreLease;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcCabinetSessionTest {
    static SfcRomImage rom(){return SfcRomImage.fromBytes(new byte[32768]);}
    static void until(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);assertTrue(condition.getAsBoolean());}
    static final class Fake implements SfcCore{
        final CopyOnWriteArrayList<Integer> states=new CopyOnWriteArrayList<>();
        final CountDownLatch closeStarted=new CountDownLatch(1),allowClose=new CountDownLatch(1);
        volatile boolean stallClose,failed;long count;
        public String backendName(){return "test";}public void loadRom(SfcRomImage r){}
        public SfcFrameResult runFrame(SfcControllerState a,SfcControllerState b){if(failed)throw new IllegalStateException("fixture failure");states.add(a.mask()|(b.mask()<<12));return new SfcFrameResult(new SfcVideoMode(2,1,8,1,50),2,count++);}
        public void copyRgbaFrame(byte[] b){for(int i=0;i<8;i++)b[i]=(byte)i;}
        public int copyAudioPcm16(short[] b){b[0]=1;b[1]=2;b[2]=3;b[3]=4;return 2;}
        public byte[] saveState(){return new byte[0];}public void loadState(byte[] s){}public byte[] saveSram(){return new byte[0];}public void loadSram(byte[] s){}public void reset(boolean h){}
        public void close(){closeStarted.countDown();while(stallClose&&allowClose.getCount()>0){try{allowClose.await();}catch(InterruptedException e){Thread.interrupted();}}}
    }
    @Test void workerPublishesCorrectStereoCountAndPreservesFastEdges()throws Exception{var f=new Fake();var s=new SfcCabinetSession(rom(),()->f);try{until(s::isReady);s.offerInput(1,256);s.offerInput(0,0);until(()->f.states.contains(1|(256<<12)));until(()->f.states.lastIndexOf(0)>f.states.indexOf(1|(256<<12)));Thread.sleep(50);var p=s.pollFrame();assertNotNull(p);assertEquals(0xff020100,p.abgr()[0]);assertEquals(0,p.pcm48k().length%4);assertTrue(p.pcm48k().length>=4);assertEquals(2f,p.displayAspect());}finally{s.close();until(()->!SfcCoreLease.occupied());}}
    @Test void closeIsNonblockingAndLeaseHeldUntilActualCoreClose()throws Exception{var f=new Fake();f.stallClose=true;var s=new SfcCabinetSession(rom(),()->f);try{until(s::isReady);long start=System.nanoTime();s.close();assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<100);assertTrue(f.closeStarted.await(2,TimeUnit.SECONDS));assertTrue(SfcCoreLease.occupied());assertThrows(IllegalStateException.class,()->new SfcCabinetSession(rom(),Fake::new));assertNull(s.pollFrame());}finally{f.allowClose.countDown();s.close();until(()->!SfcCoreLease.occupied());}}
    @Test void coreFailureIsReportedAndLeaseEventuallyReleased()throws Exception{var f=new Fake();f.failed=true;var s=new SfcCabinetSession(rom(),()->f);try{until(()->s.error()!=null);until(()->!SfcCoreLease.occupied());assertEquals("fixture failure",s.error());assertFalse(s.isReady());}finally{s.close();until(()->!SfcCoreLease.occupied());}}
    @Test void constructionFailureDoesNotLeakLease()throws Exception{var s=new SfcCabinetSession(rom(),()->{throw new IllegalStateException("constructor failed");});try{until(()->s.error()!=null);until(()->!SfcCoreLease.occupied());assertEquals("constructor failed",s.error());}finally{s.close();}}
    @Test void sharedAdapterReportsTwoPortsAndReleaseDoesNotClearHost()throws Exception{
        var f=new Fake();var session=new SfcCabinetSession(rom(),()->f);var shared=session.asRetro();
        try{until(shared::isReady);assertEquals(2,shared.maxPlayers());
            assertThrows(IllegalArgumentException.class,()->shared.offerInputs(0,0,1,0));
            shared.offerInputs(1,256,0,0);until(()->f.states.contains(1|(256<<12)));
            shared.releasePort(1);until(()->f.states.lastIndexOf(1)>f.states.indexOf(1|(256<<12)));
            assertTrue(shared.isReady());assertNull(shared.error());
            assertThrows(IllegalArgumentException.class,()->shared.releasePort(2));
        }finally{shared.close();until(()->!SfcCoreLease.occupied());}
    }
}
