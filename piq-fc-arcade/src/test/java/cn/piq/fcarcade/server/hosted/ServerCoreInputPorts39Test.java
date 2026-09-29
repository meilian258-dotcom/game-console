package cn.piq.fcarcade.server.hosted;

import cn.piq.fcarcade.cabinet.CabinetFrame;
import cn.piq.fcarcade.session.ZapperInput;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerCoreInputPorts39Test {
    @Test void twoPlayersAtFortyEdgesPerSecondDoNotBuildACombinedBacklog(){simulate(2);}
    @Test void fourPlayersAtFortyEdgesPerSecondDoNotBuildACombinedBacklog(){simulate(4);}

    private static void simulate(int players){
        var input=new ServerCoreWorker.InputState();int[] offered=new int[4],pressed=new int[5];pressed[4]=ZapperInput.NEUTRAL;
        int[] neutral={0,0,0,0,ZapperInput.NEUTRAL};
        // Deterministic 60 simulated seconds: 20 server ticks/s, 2 edges/player/tick,
        // 3 emulation frames/tick. Each player sends a separate complete-room snapshot.
        for(int tick=0;tick<1200;tick++){
            for(int p=0;p<players;p++){offered[p]=pressed[p]=1<<p;assertTrue(input.offer(offered));}
            for(int p=0;p<players;p++){offered[p]=0;assertTrue(input.offer(offered));}
            assertArrayEquals(pressed,input.sample());
            assertArrayEquals(neutral,input.sample());
            assertArrayEquals(neutral,input.sample());
        }
    }

    @Test void everyPortKeepsItsOwnShortEdgesAndCapacity(){
        var input=new ServerCoreWorker.InputState();int[] offered=new int[4];
        for(int edge=0;edge<128;edge++)for(int p=0;p<4;p++){offered[p]=(edge&1)+1;assertTrue(input.offer(offered));}
        assertTrue(input.offer(offered)); // Same held state does not consume capacity.
        for(int edge=0;edge<128;edge++)assertArrayEquals(new int[]{(edge&1)+1,(edge&1)+1,(edge&1)+1,(edge&1)+1,ZapperInput.NEUTRAL},input.sample());
    }

    @Test void fullPortRejectsTheWholeOfferWithoutMutatingOtherPorts(){
        var input=new ServerCoreWorker.InputState();
        for(int edge=0;edge<128;edge++)assertTrue(input.offer(new int[]{0,0,0,(edge&1)+1}));
        assertFalse(input.offer(new int[]{8,4,2,3}));
        for(int edge=0;edge<128;edge++)assertArrayEquals(new int[]{0,0,0,(edge&1)+1,ZapperInput.NEUTRAL},input.sample());
        assertTrue(input.offer(new int[]{8,4,2,3}));assertArrayEquals(new int[]{8,4,2,3,ZapperInput.NEUTRAL},input.sample());
    }

    @Test void releaseDropsOnlyThatPortsPendingPressesAndCanAcceptANewPress(){
        var input=new ServerCoreWorker.InputState();
        assertTrue(input.offer(new int[]{1,2,4,8}));assertTrue(input.offer(new int[]{0,0,0,0}));
        input.release(1);assertArrayEquals(new int[]{1,0,4,8,ZapperInput.NEUTRAL},input.sample());
        assertTrue(input.offer(new int[]{0,16,0,0}));assertArrayEquals(new int[]{0,16,0,0,ZapperInput.NEUTRAL},input.sample());
    }

    @Test void clearDropsAllButtonsAndGunEdgesWithoutRevival(){
        var input=new ServerCoreWorker.InputState();int gun=ZapperInput.pack(10,20,false,true);
        assertTrue(input.offer(new int[]{1,2,4,8,gun}));input.clear();
        for(int i=0;i<4;i++)assertArrayEquals(new int[]{0,0,0,0,ZapperInput.NEUTRAL},input.sample());
        assertTrue(input.offer(new int[]{1,2,4,8,gun}));assertArrayEquals(new int[]{1,2,4,8,gun},input.sample());
    }

    @Test void realOwnerConsumesFourIndependentPressesInOneFrame()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var core=new ServerCoreWorkerTest.Core(){@Override public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{
            var result=super.runFrame(a,b,c,d,gun);if(frames.size()==1){entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));}return result;
        }};
        var worker=new ServerCoreWorker(4,false,true,()->core);
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            worker.offerInputs(1,0,0,0);worker.offerInputs(1,2,0,0);worker.offerInputs(1,2,4,0);worker.offerInputs(1,2,4,8);
            worker.offerInputs(0,2,4,8);worker.offerInputs(0,0,4,8);worker.offerInputs(0,0,0,8);worker.offerInputs(0,0,0,0);
            go.countDown();ServerCoreWorkerTest.await(()->core.frames.size()>=3);
            assertArrayEquals(new int[]{1,2,4,8,ZapperInput.NEUTRAL},core.frames.get(1));
            assertArrayEquals(new int[]{0,0,0,0,ZapperInput.NEUTRAL},core.frames.get(2));assertNull(worker.error());
        }finally{go.countDown();worker.close();}ServerCoreWorkerTest.await(worker::isTerminated);
    }

    @Test void realOwnerReleaseClearsGunAndP2ButPreservesP1ShortPress()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var core=new ServerCoreWorkerTest.Core(){@Override public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{
            var result=super.runFrame(a,b,c,d,gun);if(frames.size()==1){entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));}return result;
        }};
        var worker=new ServerCoreWorker(2,true,true,()->core);
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            worker.offerFrameInput(1,2,0,0,ZapperInput.pack(10,20,false,true));worker.offerFrameInput(0,0,0,0,ZapperInput.NEUTRAL);
            worker.releasePort(1);go.countDown();ServerCoreWorkerTest.await(()->core.frames.size()>=3);
            assertArrayEquals(new int[]{1,0,0,0,ZapperInput.NEUTRAL},core.frames.get(1));
            assertArrayEquals(new int[]{0,0,0,0,ZapperInput.NEUTRAL},core.frames.get(2));assertNull(worker.error());
        }finally{go.countDown();worker.close();}ServerCoreWorkerTest.await(worker::isTerminated);
    }

    @Test void realOwnerResetDiscardsQueuedButtonsAndGun()throws Exception{
        var entered=new CountDownLatch(1);var go=new CountDownLatch(1);
        var core=new ServerCoreWorkerTest.Core(){@Override public CabinetFrame runFrame(int a,int b,int c,int d,int gun)throws Exception{
            var result=super.runFrame(a,b,c,d,gun);if(frames.size()==1){entered.countDown();assertTrue(go.await(4,TimeUnit.SECONDS));}return result;
        }};
        var worker=new ServerCoreWorker(4,true,true,()->core);
        try{
            assertTrue(entered.await(2,TimeUnit.SECONDS));worker.offerFrameInput(1,2,4,8,ZapperInput.pack(10,20,false,true));
            worker.reset();go.countDown();ServerCoreWorkerTest.await(()->core.frames.size()>=4);
            for(int i=1;i<4;i++)assertArrayEquals(new int[]{0,0,0,0,ZapperInput.NEUTRAL},core.frames.get(i));
            assertEquals(1,core.resets);assertNull(worker.error());
        }finally{go.countDown();worker.close();}ServerCoreWorkerTest.await(worker::isTerminated);
    }
}
