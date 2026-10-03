package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real owner/lifecycle and persistence calls with a fake core; not native/MC evidence. */
class JniPreparedHostTest {
    static NetplayProfile profile(){
        var runtime=new LibretroProfile("Test MD","md",false,List.of(513,513),false,Map.of(),
                Map.of("windows-x64",new LibretroProfile.Artifact("/core/test.dll","a".repeat(64))));
        return new NetplayProfile(JniPreparedHostTest.class,"/core/test.dll","a".repeat(64),"content.md",Map.of(),513,44100,1024,2,runtime);
    }
    static final class Save implements NetplayProcess.Persistence {
        final AtomicInteger loads=new AtomicInteger(),writes=new AtomicInteger(),finishes=new AtomicInteger(),aborts=new AtomicInteger();
        boolean failAbort;
        public byte[] load(NetplaySaveState.Identity identity){loads.incrementAndGet();return null;}
        public CompletableFuture<Void> save(byte[] bytes){writes.incrementAndGet();return CompletableFuture.completedFuture(null);}
        public CompletableFuture<Void> finish(){finishes.incrementAndGet();return CompletableFuture.completedFuture(null);}
        public void abort(){aborts.incrementAndGet();if(failAbort)throw new IllegalStateException("test abort failure");}
    }
    static void await(JniNetplaySession run,BooleanSupplier condition)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){assertNull(run.error(),run.diagnostic());Thread.sleep(5);}
        assertNull(run.error(),run.diagnostic());assertTrue(condition.getAsBoolean(),run.diagnostic());
    }
    static JniNetplaySession prepared(JniNetplaySessionTest.Core core,NetplayCabinetInputs inputs){
        return new JniNetplaySession(new NetplayProcess.Grant(902,UUID.randomUUID(),true,true),()->new byte[16],p->{},
                false,()->core,profile(),Map::of,inputs,true);
    }
    @Test void preparedCancelDoesNotRunPresentInputOrCommit()throws Exception{
        var core=new JniNetplaySessionTest.Core();var save=new Save();
        try(var run=prepared(core,new NetplayCabinetInputs(false))){
            assertThrows(IllegalStateException.class,run::activate);
            run.persistence(save);run.start();await(run,run::ready);
            assertEquals(1,save.loads.get());assertFalse(run.active());assertFalse(run.canSave());
            int preparedCalls=core.applied.size();Thread.sleep(60);
            assertEquals(preparedCalls,core.applied.size());assertEquals(0,run.framesReceived());assertNull(run.poll());
            assertThrows(ExecutionException.class,()->run.checkpoint().get());
            assertThrows(ExecutionException.class,()->run.saveNow().get());
            run.close();run.terminated().get(5,TimeUnit.SECONDS);
            assertTrue(core.closed);assertEquals(0,save.writes.get());assertEquals(0,save.finishes.get());assertEquals(1,save.aborts.get());
            assertThrows(IllegalStateException.class,run::activate);
        }
    }
    @Test void twoPortAuthorityActivatesOnceAndPreservesFinalSave()throws Exception{
        var core=new JniNetplaySessionTest.Core();var save=new Save();var inputs=new NetplayCabinetInputs(false);
        try(var run=prepared(core,inputs)){
            run.persistence(save);run.start();await(run,run::ready);
            run.activate();run.activate();assertTrue(run.active());
            inputs.input(0,1024,System.nanoTime());inputs.input(1,2048,System.nanoTime());
            await(run,()->run.framesReceived()>5);assertTrue(run.canSave());
            assertTrue(core.applied.stream().allMatch(f->f.pads().length==2));
            assertTrue(core.applied.stream().anyMatch(f->f.pads()[0]==1024&&f.pads()[1]==2048));
            run.saveNow().get(5,TimeUnit.SECONDS);
            run.close();run.terminated().get(5,TimeUnit.SECONDS);
            assertEquals(2,save.writes.get());assertEquals(1,save.finishes.get());assertTrue(core.closed);
        }
    }
    @Test void publicConstructorRejectsUnsupportedPreparedTransportAndOutOfRangePorts(){
        var host=new NetplayProcess.Grant(904,UUID.randomUUID(),true,true);
        var observer=new NetplayProcess.Grant(904,UUID.randomUUID(),false,false);
        assertThrows(IllegalArgumentException.class,()->new NetplayProcess(host,()->new byte[16],p->{},NetplayProfile.fc(),Map::of,true,false,true));
        assertThrows(IllegalArgumentException.class,()->new NetplayProcess(observer,()->new byte[16],p->{},profile(),Map::of,true,false,true));
        try(var process=new NetplayProcess(host,()->new byte[16],p->{},profile(),Map::of,true,false,true)){
            assertThrows(IllegalStateException.class,process::activate);
            assertThrows(IllegalArgumentException.class,()->process.cabinetInput(2,0));
            assertThrows(IllegalArgumentException.class,()->process.cabinetRelease(-1));
            assertThrows(IllegalArgumentException.class,()->process.cabinetCoin(3,1));
        }
    }
    @Test void closeBeforeStartingAbortsBoundPreparationExactlyOnce()throws Exception{
        var save=new Save();var core=new JniNetplaySessionTest.Core();
        var run=prepared(core,new NetplayCabinetInputs(false));run.persistence(save);
        run.close();run.close();run.start();run.terminated().get(5,TimeUnit.SECONDS);
        assertEquals(1,save.aborts.get());assertEquals(0,save.loads.get());assertEquals(0,save.writes.get());
        assertFalse(run.ready());assertFalse(run.active());assertTrue(core.applied.isEmpty());
    }
    @Test void failingPersistenceAbortStillClosesNativeOwnerAndCompletesTermination()throws Exception{
        var save=new Save();save.failAbort=true;var core=new JniNetplaySessionTest.Core();
        var run=prepared(core,new NetplayCabinetInputs(false));run.persistence(save);run.start();await(run,run::ready);
        run.close();
        var failure=assertThrows(ExecutionException.class,()->run.terminated().get(5,TimeUnit.SECONDS));
        assertEquals("test abort failure",failure.getCause().getMessage());assertTrue(core.closed);
        assertFalse(run.ready());assertFalse(run.active());assertNotNull(run.error());
        run.close();run.start();assertEquals(1,save.aborts.get());
        assertEquals(0,save.writes.get());assertEquals(0,save.finishes.get());
    }
    @Test void failingAbortBeforeStartDoesNotCreateNativeOwnerOrHang()throws Exception{
        var save=new Save();save.failAbort=true;var core=new JniNetplaySessionTest.Core();
        var run=prepared(core,new NetplayCabinetInputs(false));run.persistence(save);run.close();
        assertThrows(ExecutionException.class,()->run.terminated().get(5,TimeUnit.SECONDS));
        run.close();run.start();assertEquals(1,save.aborts.get());assertTrue(core.applied.isEmpty());assertNotNull(run.error());
    }
}
