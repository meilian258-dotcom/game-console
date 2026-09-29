package cn.piq.fcarcade.cabinet;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise real queues: a normal focus reset must not revoke an accepted paid event. */
class CabinetCoinReleaseTest {
    @Test void localFocusResetClearsEveryGameplayBitImmediatelyButRetainsAllPaidEdges() {
        for(int port=0;port<4;port++)for(int fps:new int[]{40000,60000,80000}) {
            var q=new CabinetSyncTimeline(fps);
            assertTrue(q.input(port,4095&~4));
            for(int i=0;i<8;i++)assertTrue(q.coin(port));
            q.releaseGameplay(port);
            var frames=new ArrayList<CabinetSyncTimeline.Step>();
            for(int i=0;i<10;i++)frames.addAll(q.tick());
            int down=0;boolean previous=false;
            for(var frame:frames){int mask=frame.masks()[port];assertEquals(0,mask&~4);
                if((mask&4)!=0){assertFalse(previous);down++;}previous=(mask&4)!=0;}
            assertEquals(8,down);
            assertEquals(frames,q.after(0,frames.size()));
        }
    }
    @Test void localResetBetweenCoinEdgesPreservesReleaseAndFollowingCoin() {
        var q=new CabinetSyncTimeline(60000);q.input(1,8);assertTrue(q.coin(1));assertTrue(q.coin(1));assertTrue(q.coin(1));
        assertEquals(12,q.tick().getLast().p2());
        q.releaseGameplay(1);var frames=q.tick();
        assertEquals(0,frames.getFirst().p2());assertEquals(4,frames.get(1).p2());assertEquals(0,frames.getLast().p2());
    }
    @Test void localGameplayOverflowCannotRevokePaidCoinsButDestroyedSeatCan() {
        var q=new CabinetSyncTimeline(60000);assertTrue(q.coin(3));
        for(int i=0;i<32;i++)assertTrue(q.input(3,(i%2+1)<<4));
        assertFalse(q.input(3,64));assertFalse(q.coin(3));
        var frames=q.tick();assertEquals(4,frames.getFirst().p4());assertEquals(0,frames.get(1).p4());
        assertTrue(q.input(3,0));assertTrue(q.coin(3));q.release(3);
        for(var frame:q.tick())assertEquals(0,frame.p4());
    }
    @Test void hostedFocusResetImmediatelyStripsGameplayButRetainsEachCoinAndOtherPorts() {
        for(int port=0;port<4;port++){
            var q=new HostedInputQueue();int[] base={16,32,64,128};
            assertTrue(q.offer(base));assertTrue(q.coin(port));assertTrue(q.coin(port));
            q.releasePreservingCoins(port);int count=0,coins=0;boolean previous=false;
            for(int[] masks;(masks=q.poll())!=null;){
                assertEquals(0,masks[port]&~4);
                for(int p=0;p<4;p++)if(p!=port)assertEquals(base[p],masks[p]);
                boolean down=(masks[port]&4)!=0;if(down){assertFalse(previous);coins++;}previous=down;count++;
            }
            assertEquals(5,count);assertEquals(2,coins);assertFalse(previous);
            int[] neutral=base.clone();neutral[port]=0;
            assertTrue(q.offer(neutral));assertNull(q.poll()); // latest was reset too.
        }
    }
    @Test void hostedSoftReleaseKeepsCapacityAndHardReleaseCancelsOldSeatCoins() {
        var q=new HostedInputQueue();for(int i=0;i<64;i++)assertTrue(q.coin(2));
        assertFalse(q.coin(2));q.releasePreservingCoins(2);assertFalse(q.coin(2));
        int coins=0,count=0;for(int[] masks;(masks=q.poll())!=null;){if((masks[2]&4)!=0)coins++;count++;}
        assertEquals(64,coins);assertEquals(128,count);
        assertTrue(q.coin(2));q.release(2);assertEquals(0,q.poll()[2]);assertEquals(0,q.poll()[2]);assertNull(q.poll());
        assertThrows(IllegalArgumentException.class,()->q.releasePreservingCoins(4));
    }

    /** Independent javac runner; Gradle remains owned by the integration task. */
    public static void main(String[] args)throws Exception{
        var test=new CabinetCoinReleaseTest();
        test.localFocusResetClearsEveryGameplayBitImmediatelyButRetainsAllPaidEdges();
        test.localResetBetweenCoinEdgesPreservesReleaseAndFollowingCoin();
        test.localGameplayOverflowCannotRevokePaidCoinsButDestroyedSeatCan();
        test.hostedFocusResetImmediatelyStripsGameplayButRetainsEachCoinAndOtherPorts();
        test.hostedSoftReleaseKeepsCapacityAndHardReleaseCancelsOldSeatCoins();
        System.out.println("Passed 5 real-queue coin release tests (4 ports; local 40/60/80 fps; reset/overflow/full queue/destruction).");
        var source=new CabinetCoinSourceTest();
        source.oldNbtIsFreeButPlacementDefaultsPaid();
        source.physicalUseRechecksHeldIdentityRayRoomAndTopologyBeforeOneDebit();
        source.debugOnlyChangesCoinsAndDoesNotChangeFcOrSfcInput();
        source.ordinaryReleaseUsesPreservingCapabilityButSeatDestructionStillHardReleases();
        System.out.println("Passed 4 FC coin source-wiring guards; these are not live permission/network acceptance tests.");
    }
}
