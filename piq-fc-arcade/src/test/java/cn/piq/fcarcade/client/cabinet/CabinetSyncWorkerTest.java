package cn.piq.fcarcade.client.cabinet;
import cn.piq.fcarcade.cabinet.*;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
class CabinetSyncWorkerTest {
    static class Core implements CabinetSyncCore{
        Thread owner;volatile int count;volatile boolean closed;int value,loads;boolean badRestore;
        final List<int[]> inputs=new CopyOnWriteArrayList<>();
        void own(){if(owner==null)owner=Thread.currentThread();assertSame(owner,Thread.currentThread());}
        public int maxPlayers(){own();return 4;}public double targetFps(){own();return 60;}public String compatibilityId(){own();return "original-diagnostic-v1";}
        public CabinetFrame runFrame(int a,int b,int c,int d){own();value=value*31+a+3*b+5*c+7*d;inputs.add(new int[]{a,b,c,d});count++;return new CabinetFrame(1,1,new int[]{value},1,0,new short[]{(short)value,(short)value});}
        public byte[] saveState(){own();return ByteBuffer.allocate(8).putInt(count).putInt(value).array();}
        public void loadState(byte[] s){own();var b=ByteBuffer.wrap(s);count=b.getInt();value=b.getInt()+(badRestore?1:0);loads++;}
        public void close(){own();closed=true;}
    }
    static CabinetSyncWorker worker(Core c,boolean host){return new CabinetSyncWorker(()->{c.own();return new CabinetSyncWorker.Opened(c,"0".repeat(64));},host);}
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(condition.getAsBoolean());}
    static List<CabinetSyncTimeline.Step> steps(long start,int n){var out=new ArrayList<CabinetSyncTimeline.Step>();for(int i=0;i<n;i++)out.add(new CabinetSyncTimeline.Step(start+i,(i&1),2,4,8));return out;}
    @Test void factoryFramesStateAndCloseAllBelongToWorker()throws Exception{var c=new Core();var w=worker(c,true);try{await(w::isReady);assertNotSame(Thread.currentThread(),c.owner);assertEquals(0,c.count);assertTrue(w.frames(steps(1,3)));await(()->c.count==3);assertArrayEquals(new int[]{0,2,4,8},c.inputs.getFirst());assertThrows(UnsupportedOperationException.class,()->w.offerInput(1,2));}finally{w.close();}await(()->c.closed);assertNull(w.pollFrame());assertNull(w.pollEvent());}
    @Test void guestDoesNotStepBeforeVerifiedSnapshot()throws Exception{var c=new Core();var w=worker(c,false);try{await(w::isReady);assertTrue(w.frames(steps(1,3)));Thread.sleep(30);assertEquals(0,c.count);w.beginRestore(0);byte[] state=new byte[8];w.restore(UUID.randomUUID(),0,3,state,CabinetSyncState.hash(state));assertTrue(w.frames(steps(1,3)));await(()->c.count==3);assertEquals(1,c.loads);}finally{w.close();}}
    @Test void stateRestorationCannotRewindHost()throws Exception{var c=new Core();var w=worker(c,true);try{await(w::isReady);assertThrows(IllegalStateException.class,()->w.beginRestore(0));assertEquals(0,c.loads);}finally{w.close();}}
    @Test void gapAndDuplicateBatchDoNotPartiallyEnterQueue()throws Exception{var c=new Core();var w=worker(c,true);try{await(w::isReady);assertFalse(w.frames(steps(2,2)));assertEquals(0,c.count);assertTrue(w.frames(steps(1,2)));assertFalse(w.frames(steps(1,2)));assertTrue(w.frames(steps(3,1)));await(()->c.count==3);}finally{w.close();}}
    @Test void badSnapshotHashCannotReachCore()throws Exception{var c=new Core();var w=worker(c,false);try{await(w::isReady);w.beginRestore(0);w.restore(UUID.randomUUID(),0,0,new byte[8],"f".repeat(64));await(()->w.error()!=null);assertEquals(0,c.loads);}finally{w.close();}await(()->c.closed);}
    @Test void coreReturningDifferentStateAfterLoadFailsClosed()throws Exception{var c=new Core();c.badRestore=true;var w=worker(c,false);try{await(w::isReady);byte[] s=new byte[8];w.beginRestore(0);w.restore(UUID.randomUUID(),0,0,s,CabinetSyncState.hash(s));await(()->w.error()!=null);assertEquals(1,c.loads);}finally{w.close();}}
    @Test void cancelDuringFactoryClosesResultOnSameWorker()throws Exception{var c=new Core();var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var w=new CabinetSyncWorker(()->{c.own();entered.countDown();go.await();return new CabinetSyncWorker.Opened(c,"0".repeat(64));},true);assertTrue(entered.await(2,TimeUnit.SECONDS));w.close();go.countDown();await(()->c.closed);assertFalse(w.isReady());assertNull(w.pollEvent());}
    static class AudioCore extends Core {
        public CabinetFrame runFrame(int a,int b,int c,int d){super.runFrame(a,b,c,d);short[] pcm=new short[1600];for(int i=0;i<pcm.length;i++)pcm[i]=(short)((count-1)*1600+i);return new CabinetFrame(1,1,new int[]{count},1,0,pcm);}
    }
    @Test void slowVideoConsumerReceivesEveryNormalAudioSample()throws Exception{var c=new AudioCore();var w=worker(c,true);try{await(w::isReady);assertTrue(w.frames(steps(1,3)));await(()->c.count==3);Thread.sleep(20);var frame=w.pollFrame();assertNotNull(frame);assertEquals(3,frame.abgr()[0]);assertEquals(4800,frame.pcm48k().length);for(int i=0;i<4800;i++)assertEquals((short)i,frame.pcm48k()[i]);assertNull(w.pollFrame());}finally{w.close();}}
    @Test void audioBacklogHasFixed300msLimitAndKeepsStereoTail()throws Exception{var c=new AudioCore();var w=worker(c,true);try{await(w::isReady);for(int frame=1;frame<=21;frame+=3){assertTrue(w.frames(steps(frame,3)));int goal=frame+2;await(()->c.count==goal);}Thread.sleep(20);var frame=w.pollFrame();assertEquals(28800,frame.pcm48k().length);for(int i=0;i<28800;i++)assertEquals((short)(4800+i),frame.pcm48k()[i]);}finally{w.close();}}
    @Test void restoringWhileOldCoreFrameIsInFlightDoesNotConsumeNewQueueOrPublishOldAudio()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var c=new AudioCore(){boolean block=true;public CabinetFrame runFrame(int a,int b,int x,int d){if(block){block=false;entered.countDown();try{assertTrue(go.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}return super.runFrame(a,b,x,d);}};
        var w=worker(c,false);try{await(w::isReady);byte[] initial=new byte[8];w.beginRestore(0);w.restore(UUID.randomUUID(),0,0,initial,CabinetSyncState.hash(initial));assertTrue(w.frames(steps(1,1)));assertTrue(entered.await(2,TimeUnit.SECONDS));
            byte[] state=ByteBuffer.allocate(8).putInt(100).putInt(17).array();UUID token=UUID.randomUUID();w.beginRestore(100);w.restore(token,100,102,state,CabinetSyncState.hash(state));assertTrue(w.frames(steps(101,2)));go.countDown();await(()->c.count==102);Thread.sleep(20);assertNull(w.error());assertEquals(2,c.loads);assertEquals(3,c.inputs.size());var frame=w.pollFrame();assertNotNull(frame);assertEquals(102,frame.abgr()[0]);assertEquals(0,frame.pcm48k().length);boolean acknowledged=false;for(CabinetSyncWorker.Event e;(e=w.pollEvent())!=null;)if(e.kind()==CabinetSyncWorker.Event.RESTORED&&token.equals(e.token()))acknowledged=true;assertTrue(acknowledged);
        }finally{go.countDown();w.close();}
    }
    @Test void aNewRestoreDuringLoadCannotActivateSupersededSnapshot()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var c=new Core(){public void loadState(byte[] s){if(loads==0){entered.countDown();try{assertTrue(go.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}super.loadState(s);}};
        var w=worker(c,false);try{await(w::isReady);byte[] first=new byte[8];w.beginRestore(0);w.restore(UUID.randomUUID(),0,0,first,CabinetSyncState.hash(first));assertTrue(entered.await(2,TimeUnit.SECONDS));byte[] second=ByteBuffer.allocate(8).putInt(50).putInt(19).array();UUID token=UUID.randomUUID();w.beginRestore(50);w.restore(token,50,51,second,CabinetSyncState.hash(second));assertTrue(w.frames(steps(51,1)));go.countDown();await(()->c.count==51);assertNull(w.error());assertEquals(2,c.loads);assertEquals(1,c.inputs.size());}finally{go.countDown();w.close();}
    }
}
