package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.session.ZapperInput;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ServerCoreWorkerTest {
    static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(2);assertTrue(condition.getAsBoolean());}
    static class Core implements ServerCoreWorker.Core {
        Thread owner;volatile boolean closed;volatile int saves,resets;final List<int[]> frames=new CopyOnWriteArrayList<>();
        void own(){if(owner==null)owner=Thread.currentThread();assertSame(owner,Thread.currentThread());}
        public double targetFps(){own();return 60;}
        public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{own();frames.add(new int[]{a,b,c,d,gun});return new CabinetFrame(1,1,new int[]{frames.size()},1,0,new short[]{1,2});}
        public void persist(){own();saves++;}public void reset(){own();resets++;}public void close(){own();closed=true;}
    }
    @Test void constructionSteppingResetPersistenceAndCloseStayOnOwner()throws Exception{
        var core=new Core();var worker=new ServerCoreWorker(2,true,true,()->{core.own();return core;});
        try{await(worker::isReady);await(()->!core.frames.isEmpty());assertNotSame(Thread.currentThread(),core.owner);assertTrue(worker.supportsZapper());assertTrue(worker.supportsReset());
            int gun=ZapperInput.pack(20,30,false,true);worker.offerZapper(gun);await(()->core.frames.stream().anyMatch(f->f[4]==gun));worker.reset();await(()->core.resets==1);
            assertThrows(IllegalArgumentException.class,()->worker.offerInputs(0,0,1,0));assertThrows(IllegalArgumentException.class,()->worker.offerInput(-1,0));assertThrows(IllegalArgumentException.class,()->worker.offerZapper(-1));
        }finally{worker.close();}await(worker::isTerminated);assertTrue(core.closed);assertTrue(core.saves>0);assertNull(worker.pollFrame());assertFalse(worker.isReady());assertNull(worker.error());
    }
    @Test void closeDuringStartupDoesNotReleaseBeforeOwnerActuallyCloses()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var core=new Core();
        var worker=new ServerCoreWorker(1,false,false,()->{core.own();entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));return core;});
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));worker.close();assertFalse(worker.isTerminated());assertFalse(worker.isReady());assertThrows(UnsupportedOperationException.class,worker::reset);assertThrows(UnsupportedOperationException.class,()->worker.offerZapper(ZapperInput.NEUTRAL));}
        finally{go.countDown();worker.close();}await(worker::isTerminated);assertTrue(core.closed);assertTrue(core.frames.isEmpty());
    }
    @Test void releaseClearsQueuedPortWithoutDroppingOtherPlayersEdges()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var core=new Core(){public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{var result=super.runFrame(a,b,c,d,gun);if(frames.size()==1){entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));}return result;}};
        var worker=new ServerCoreWorker(2,true,true,()->core);
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));worker.offerInputs(1,2,0,0);worker.offerInputs(0,4,0,0);worker.releasePort(0);go.countDown();await(()->core.frames.size()>=3);assertArrayEquals(new int[]{0,2,0,0,ZapperInput.NEUTRAL},core.frames.get(1));assertArrayEquals(new int[]{0,4,0,0,ZapperInput.NEUTRAL},core.frames.get(2));}
        finally{go.countDown();worker.close();}await(worker::isTerminated);
    }
    @Test void overflowingBoundedInputFailsClosedAndDoesNotTerminateActiveCoreEarly()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var core=new Core(){public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));return super.runFrame(a,b,c,d,gun);}};
        var worker=new ServerCoreWorker(1,false,true,()->core);
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));for(int i=0;i<129;i++)worker.offerInput((i&1)+1,0);assertNotNull(worker.error());assertFalse(worker.isReady());assertFalse(worker.isTerminated());}
        finally{go.countDown();worker.close();}await(worker::isTerminated);assertTrue(core.closed);
    }
    @Test void resetDiscardsAnInFlightOldPictureAndAudio()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var second=new CountDownLatch(1);var exit=new CountDownLatch(1);
        var core=new Core(){public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{var out=super.runFrame(a,b,c,d,gun);if(frames.size()==1){entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));}else if(frames.size()==2){second.countDown();assertTrue(exit.await(4,TimeUnit.SECONDS));}return out;}};
        var worker=new ServerCoreWorker(1,false,true,()->core);
        try{assertTrue(entered.await(2,TimeUnit.SECONDS));worker.reset();go.countDown();assertTrue(second.await(2,TimeUnit.SECONDS));assertNull(worker.pollFrame());assertEquals(1,core.resets);}
        finally{go.countDown();exit.countDown();worker.close();}await(worker::isTerminated);
    }
    @Test void audioTailStaysBoundedStereoAlignedAndLatestPictureIsSingleSlot()throws Exception{
        var core=new Core(){public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{super.runFrame(a,b,c,d,gun);short[] sound=new short[8192];java.util.Arrays.fill(sound,(short)frames.size());return new CabinetFrame(1,1,new int[]{frames.size()},1,0,sound);}};
        var worker=new ServerCoreWorker(1,false,false,()->core);
        try{await(()->core.frames.size()>=8);var frame=worker.pollFrame();assertNotNull(frame);assertTrue(frame.pcm48k().length<=32768);assertEquals(0,frame.pcm48k().length%2);for(int i=0;i<frame.pcm48k().length;i+=2)assertEquals(frame.pcm48k()[i],frame.pcm48k()[i+1]);}
        finally{worker.close();}await(worker::isTerminated);
    }
    @Test void startupFailureTerminatesAndReportsWithoutReadyState()throws Exception{
        var worker=new ServerCoreWorker(2,false,true,()->{throw new java.io.IOException("fixture failure");});await(worker::isTerminated);assertFalse(worker.isReady());assertTrue(worker.error().contains("fixture failure"));
    }
}
