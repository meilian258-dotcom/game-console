package cn.piq.nativearcade.bridge;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import static org.junit.jupiter.api.Assertions.*;

/** Actual parent/helper queues, without constructing a session, opening a ROM or loading a core. */
class NativeCoinReleaseTest {
    @Test void allPortsPreserveTwoPaidPulsesButDropEveryGameplayBitImmediately() {
        for(int port=0;port<4;port++) {
            var queue=new NativeInputPorts();
            int[] values={16,32,64,128};values[port]|=4;
            assertTrue(queue.offer(values[0],values[1],values[2],values[3]));
            values[port]&=~4;assertTrue(queue.offer(values[0],values[1],values[2],values[3]));
            values[port]|=4;assertTrue(queue.offer(values[0],values[1],values[2],values[3]));
            values[port]&=~4;assertTrue(queue.offer(values[0],values[1],values[2],values[3]));
            queue.releaseGameplayPortKeepingCoin(port);queue.releaseGameplayPortKeepingCoin(port);
            assertEquals(4,queue.pending(port));
            for(int expected:new int[]{4,0,4,0}) {
                var frame=queue.nextFrame();assertEquals(expected,frame[port]);
                for(int other=0;other<4;other++)if(other!=port)assertEquals(values[other],frame[other]);
            }
        }
    }
    @Test void menuReleaseIsNotDelayedBy128OldDirectionEdges() {
        var queue=new NativeInputPorts();
        for(int i=0;i<126;i++)assertTrue(queue.offer((i&1)==0?16:32,0,0,0));
        assertTrue(queue.offer(20,0,0,0));assertTrue(queue.offer(16,0,0,0));assertEquals(128,queue.pending(0));
        queue.releaseGameplayPortKeepingCoin(0);assertEquals(2,queue.pending(0));
        assertEquals(4,queue.nextFrame()[0]);assertEquals(0,queue.nextFrame()[0]);
        assertTrue(queue.offer(128,0,0,0));assertEquals(128,queue.nextFrame()[0]);
    }
    @Test void alreadyExecutedCoinDownIsNotInventedAgainAndHardLeaveStillDropsAll() {
        var queue=new NativeInputPorts();assertTrue(queue.offer(20,2,0,0));assertEquals(20,queue.nextFrame()[0]);
        assertTrue(queue.offer(16,2,0,0));queue.releaseGameplayPortKeepingCoin(0);
        assertEquals(0,queue.nextFrame()[0]);assertEquals(0,queue.nextFrame()[0]);
        assertTrue(queue.offer(4,2,0,0));assertTrue(queue.offer(0,2,0,0));
        queue.releasePort(0);assertEquals(0,queue.pending(0));assertArrayEquals(new int[]{0,2,0,0},queue.nextFrame());
    }
    @Test void maskProjectionDropsAllFifteenOtherBitsAndNeverInventsCoin() {
        for(int mask=0;mask<=65535;mask++) {
            var queue=new NativeInputPorts();assertTrue(queue.offer(mask,0,0,0));queue.nextFrame();
            queue.releaseGameplayPortKeepingCoin(0);assertEquals(mask&4,queue.nextFrame()[0]);assertEquals(0,queue.pending(0));
        }
    }
    @Test void invalidSoftPortsDoNotMutateOtherQueues() {
        var queue=new NativeInputPorts();queue.offer(4,8,16,32);
        assertThrows(IllegalArgumentException.class,()->queue.releaseGameplayPortKeepingCoin(-1));
        assertThrows(IllegalArgumentException.class,()->queue.releaseGameplayPortKeepingCoin(4));
        assertArrayEquals(new int[]{4,8,16,32},queue.nextFrame());
    }
    @Test void parentProjectsTheOriginalFifoAndRetainsOtherPortEdges() {
        for(int port=0;port<4;port++) {
            var queue=new ArrayBlockingQueue<NativeProcessSession.Input>(136);var expected=new ArrayList<int[]>();
            for(int i=0;i<32;i++) {
                int[] masks={i&1,8+(i&1),16+(i&1),32+(i&1)};masks[port]|=(i&1)*4;
                queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,masks[0],masks[1],masks[2],masks[3],-1));
                masks[port]&=4;expected.add(masks);
            }
            assertTrue(NativeProcessSession.projectPending(queue,port,true));
            for(var mask:expected) {
                var input=queue.remove();assertEquals(BridgeProtocol.INPUT4,input.command());
                assertArrayEquals(mask,new int[]{input.p1(),input.p2(),input.p3(),input.p4()});
            }
            var release=queue.remove();assertEquals(BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN,release.command());assertEquals(port,release.port());
            assertTrue(queue.isEmpty());
        }
    }
    @Test void parentCollapsesOnlyIdenticalWholePortVectorsAndCoalescesRepeatedSoftMarkers() {
        var queue=new ArrayBlockingQueue<NativeProcessSession.Input>(136);
        for(int i=0;i<128;i++)queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,(i&1)==0?16:32,8,0,0,-1));
        assertTrue(NativeProcessSession.projectPending(queue,0,true));assertEquals(2,queue.size());
        for(int n=0;n<100;n++)assertTrue(NativeProcessSession.projectPending(queue,0,true));
        assertEquals(2,queue.size());assertEquals(0,queue.remove().p1());
        assertEquals(BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN,queue.remove().command());
    }
    @Test void softReleaseNeverErasesAnOlderHardOwnershipRelease() {
        var queue=new ArrayBlockingQueue<NativeProcessSession.Input>(136);
        queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,4,0,0,0,-1));
        assertTrue(NativeProcessSession.projectPending(queue,0,false));
        queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,4,0,0,0,-1));
        queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,0,0,0,0,-1));
        assertTrue(NativeProcessSession.projectPending(queue,0,true));
        assertEquals(List.of(BridgeProtocol.INPUT4,BridgeProtocol.RELEASE_PORT,BridgeProtocol.INPUT4,BridgeProtocol.INPUT4,BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN),queue.stream().map(NativeProcessSession.Input::command).toList());
        assertTrue(NativeProcessSession.projectPending(queue,0,false));
        assertEquals(1,queue.stream().filter(i->i.command()==BridgeProtocol.RELEASE_PORT).count());
        assertTrue(queue.stream().noneMatch(i->i.command()==BridgeProtocol.RELEASE_GAMEPLAY_KEEP_COIN));
        assertTrue(queue.stream().filter(i->i.command()==BridgeProtocol.INPUT4).allMatch(i->i.p1()==0));
    }
    @Test void eightControlReservationsKeepAllFourPortsSafeAtTheInputLimit() {
        var queue=new ArrayBlockingQueue<NativeProcessSession.Input>(136);
        for(int i=0;i<128;i++)queue.add(new NativeProcessSession.Input(BridgeProtocol.INPUT4,i,i,i,i,-1));
        for(int p=0;p<4;p++){assertTrue(NativeProcessSession.projectPending(queue,p,false));assertTrue(NativeProcessSession.projectPending(queue,p,true));}
        for(int repeat=0;repeat<50;repeat++)for(int p=0;p<4;p++)assertTrue(NativeProcessSession.projectPending(queue,p,true));
        assertTrue(queue.size()<=136);
    }
    public static void main(String[] args)throws Exception {
        var instance=new NativeCoinReleaseTest();int count=0;
        for(var method:NativeCoinReleaseTest.class.getDeclaredMethods())if(method.isAnnotationPresent(Test.class)){method.invoke(instance);count++;}
        System.out.println("Native coin release production queue tests passed: "+count+"; all 65536 masks; no ROM/native session");
    }
}
