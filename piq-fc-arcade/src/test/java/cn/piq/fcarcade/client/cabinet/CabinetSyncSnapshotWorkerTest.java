package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.piq.fcarcade.client.cabinet.CabinetSyncWorkerTest.*;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSyncSnapshotWorkerTest {
    @Test void oldRestoreGoalCannotAcknowledgeUntilBacklogIsAtMostSixFrames()throws Exception{
        var blocked=new CountDownLatch(1);var resume=new CountDownLatch(1);
        var c=new Core(){public cn.piq.fcarcade.cabinet.CabinetFrame runFrame(int a,int b,int x,int d){
            if(count==101){blocked.countDown();try{assertTrue(resume.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}
            return super.runFrame(a,b,x,d);
        }};
        var w=worker(c,false);try{
            await(w::isReady);byte[] state=java.nio.ByteBuffer.allocate(8).putInt(100).putInt(9).array();UUID token=UUID.randomUUID();
            w.beginRestore(100);w.restore(token,100,101,state,cn.piq.fcarcade.cabinet.CabinetSyncState.hash(state));assertTrue(w.frames(steps(101,30)));
            assertTrue(blocked.await(2,TimeUnit.SECONDS));assertEquals(101,c.count);
            for(CabinetSyncWorker.Event e;(e=w.pollEvent())!=null;)assertNotEquals(CabinetSyncWorker.Event.RESTORED,e.kind(),"Old goal with >6 queued frames must not grant input");
            resume.countDown();AtomicLong acknowledged=new AtomicLong(-1);
            await(()->{for(CabinetSyncWorker.Event e;(e=w.pollEvent())!=null;)if(e.kind()==CabinetSyncWorker.Event.RESTORED){assertEquals(token,e.token());acknowledged.set(e.frame());}return acknowledged.get()>=0;});
            assertEquals(124,acknowledged.get());assertEquals(6,130-acknowledged.get());await(()->c.count==130);assertNull(w.error());
        }finally{resume.countDown();w.close();}
    }
    @Test void restoringPassesAuthoritativeFrameToAdapterOnOwningWorker()throws Exception{
        AtomicLong received=new AtomicLong(-1);var c=new Core(){public void loadState(byte[] state,long logicalFrame){own();received.set(logicalFrame);super.loadState(state);}};
        var w=worker(c,false);try{await(w::isReady);byte[] state=java.nio.ByteBuffer.allocate(8).putInt(53).putInt(19).array();
            w.beginRestore(53);w.restore(UUID.randomUUID(),53,54,state,cn.piq.fcarcade.cabinet.CabinetSyncState.hash(state));assertTrue(w.frames(steps(54,1)));
            await(()->c.count==54);assertEquals(53,received.get());assertEquals(1,c.loads);assertNull(w.error());
        }finally{w.close();}
    }
    @Test void mismatchedEmbeddedAndAuthoritativeFrameCannotActivateGuest()throws Exception{
        var c=new Core(){public void loadState(byte[] state,long logicalFrame){own();if(java.nio.ByteBuffer.wrap(state).getInt()!=logicalFrame)throw new IllegalArgumentException("Embedded frame mismatch");super.loadState(state);}};
        var w=worker(c,false);try{await(w::isReady);byte[] state=java.nio.ByteBuffer.allocate(8).putInt(99).putInt(7).array();
            w.beginRestore(100);w.restore(UUID.randomUUID(),100,101,state,cn.piq.fcarcade.cabinet.CabinetSyncState.hash(state));assertTrue(w.frames(steps(101,1)));
            await(()->w.error()!=null);assertEquals(0,c.loads);assertEquals(0,c.count);assertFalse(w.isReady());assertNull(w.pollFrame());
        }finally{w.close();}
    }
    @Test void longSnapshotIntervalStillChecksFullStateEvery300Frames()throws Exception{
        AtomicInteger saves=new AtomicInteger();var c=new Core(){
            public int snapshotIntervalFrames(){own();return 1800;}
            public byte[] saveState(){saves.incrementAndGet();return super.saveState();}
        };
        var w=worker(c,true);try{
            await(w::isReady);assertEquals(CabinetSyncWorker.Event.HELLO,w.pollEvent().kind());
            var initial=w.pollEvent();assertNotNull(initial);assertEquals(0,initial.frame());assertEquals(CabinetSyncWorker.Event.SNAPSHOT,initial.kind());
            for(int start=1;start<=1800;start+=120)assertTrue(w.frames(steps(start,120)));
            List<Long> digests=new ArrayList<>(),snapshots=new ArrayList<>();
            await(()->{for(CabinetSyncWorker.Event e;(e=w.pollEvent())!=null;){if(e.kind()==CabinetSyncWorker.Event.DIGEST)digests.add(e.frame());if(e.kind()==CabinetSyncWorker.Event.SNAPSHOT)snapshots.add(e.frame());}return snapshots.contains(1800L);});
            assertEquals(List.of(300L,600L,900L,1200L,1500L,1800L),digests);assertEquals(List.of(1800L),snapshots);assertEquals(7,saves.get());assertNull(w.error());
        }finally{w.close();}await(()->c.closed);
    }
    @Test void legacyDefaultStillPublishesCheckpointAt300()throws Exception{
        var c=new Core();assertEquals(300,c.snapshotIntervalFrames());var w=worker(c,true);
        try{await(w::isReady);w.pollEvent();w.pollEvent();assertTrue(w.frames(steps(1,120)));assertTrue(w.frames(steps(121,120)));assertTrue(w.frames(steps(241,60)));
            AtomicBoolean found=new AtomicBoolean();await(()->{for(CabinetSyncWorker.Event e;(e=w.pollEvent())!=null;)if(e.kind()==CabinetSyncWorker.Event.SNAPSHOT&&e.frame()==300)found.set(true);return found.get();});assertNull(w.error());
        }finally{w.close();}
    }
    @Test void malformedSnapshotCadenceFailsBeforeReady()throws Exception{
        for(int interval:new int[]{0,299,301,2100,Integer.MAX_VALUE}){
            var c=new Core(){public int snapshotIntervalFrames(){own();return interval;}};var w=worker(c,true);
            try{await(()->w.error()!=null);assertFalse(w.isReady());assertEquals(0,c.count);}finally{w.close();}await(()->c.closed);
        }
    }
    @Test void cancelSignalUnblocksFactoryThenCoreClosesOnlyOnWorker()throws Exception{
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);var calls=new AtomicInteger();var c=new Core();
        var caller=Thread.currentThread();var w=new CabinetSyncWorker(new CabinetSyncWorker.Factory(){
            public CabinetSyncWorker.Opened open()throws Exception{c.own();entered.countDown();assertTrue(gate.await(3,TimeUnit.SECONDS));return new CabinetSyncWorker.Opened(c,"0".repeat(64));}
            public void requestClose(){assertSame(caller,Thread.currentThread());calls.incrementAndGet();gate.countDown();}
        },true);
        assertTrue(entered.await(2,TimeUnit.SECONDS));w.close();w.close();await(()->c.closed);assertEquals(1,calls.get());assertNotSame(caller,c.owner);assertFalse(w.isReady());assertNull(w.pollEvent());
    }
    @Test void coreCancelSignalUnblocksStepWithoutCrossThreadCoreClose()throws Exception{
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);var signals=new AtomicInteger();Thread caller=Thread.currentThread();
        var c=new Core(){
            public cn.piq.fcarcade.cabinet.CabinetFrame runFrame(int a,int b,int x,int d){own();entered.countDown();try{assertTrue(gate.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return super.runFrame(a,b,x,d);}
            public void requestClose(){assertSame(caller,Thread.currentThread());signals.incrementAndGet();gate.countDown();}
        };
        var w=worker(c,true);await(w::isReady);assertTrue(w.frames(steps(1,1)));assertTrue(entered.await(2,TimeUnit.SECONDS));w.close();await(()->c.closed);assertEquals(1,signals.get());assertNull(w.pollFrame());
    }
    @Test void brokenFactoryCancelCannotSkipCoreSignalAndCleanup()throws Exception{
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);var c=new Core(){
            public cn.piq.fcarcade.cabinet.CabinetFrame runFrame(int a,int b,int x,int d){own();entered.countDown();try{assertTrue(gate.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return super.runFrame(a,b,x,d);}
            public void requestClose(){gate.countDown();}
        };
        var w=new CabinetSyncWorker(new CabinetSyncWorker.Factory(){public CabinetSyncWorker.Opened open(){c.own();return new CabinetSyncWorker.Opened(c,"0".repeat(64));}public void requestClose(){throw new IllegalStateException("diagnostic broken cancellation");}},true);
        await(w::isReady);assertTrue(w.frames(steps(1,1)));assertTrue(entered.await(2,TimeUnit.SECONDS));assertDoesNotThrow(w::close);await(()->c.closed);
    }
}
