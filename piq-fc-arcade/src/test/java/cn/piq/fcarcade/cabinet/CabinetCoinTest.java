package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetCoinTest {
    @Test void onlyMameUsesCoinPolicyAndOtherBitsAreUnchanged(){
        assertTrue(CabinetCoinPolicy.supported("piq_native_arcade:mame"));
        for(String backend:List.of("piq_fc_arcade:nes","piq_sfc_home:sfc","piq_gba_home:gba","unknown:mame"))assertFalse(CabinetCoinPolicy.supported(backend));
        for(int mask=0;mask<4096;mask++){assertEquals(mask,CabinetCoinPolicy.filter(false,mask));assertEquals(mask&~4,CabinetCoinPolicy.filter(true,mask));}
    }
    @Test void paidRoomServerInputCannotManufactureNativeCoins(){
        var ledger=new CabinetRoomLedger<String>();var owner=UUID.randomUUID();var room=ledger.open(owner,"cabinet",CabinetCoinPolicy.BACKEND,4,0);
        room.ready=true;room.coinRequired=true;
        assertNull(ledger.input(owner,room.id,room.host().id,0,4,1));
        assertEquals(8,ledger.input(owner,room.id,room.host().id,1,12,2).mask());
        room.coinRequired=false;assertEquals(12,ledger.input(owner,room.id,room.host().id,2,12,3).mask());
    }
    @Test void nativePulseIsOnePressReleaseWithoutClobberingOtherControls(){
        int[] base={1,2,8,16};for(int port=0;port<4;port++){
            var pulse=CabinetCoinPolicy.pulse(base,port);assertEquals(2,pulse.length);
            for(int p=0;p<4;p++)assertEquals(base[p]|(p==port?4:0),pulse[0][p]);
            assertArrayEquals(base,pulse[1]);assertArrayEquals(new int[]{1,2,8,16},base);
        }
        assertThrows(IllegalArgumentException.class,()->CabinetCoinPolicy.pulse(base,4));
        assertThrows(IllegalArgumentException.class,()->CabinetCoinPolicy.pulse(new int[]{-1,0,0,0},0));
    }
    @Test void localSyncSerializesCoinsAndReplaysExactAuthoritativeFrames(){
        for(int port=0;port<4;port++){
            var timeline=new CabinetSyncTimeline(60000);timeline.input(port,16);assertTrue(timeline.coin(port));assertTrue(timeline.coin(port));
            timeline.input(port,32);var frames=new ArrayList<CabinetSyncTimeline.Step>();frames.addAll(timeline.tick());frames.addAll(timeline.tick());
            assertEquals(20,frames.get(0).masks()[port]);assertEquals(32,frames.get(1).masks()[port]);assertEquals(36,frames.get(2).masks()[port]);assertEquals(32,frames.get(3).masks()[port]);
            assertEquals(frames,timeline.after(0,6));
        }
    }
    @Test void localCoinQueueIsBoundedAndForcedReleaseCannotReplayPendingCoin(){
        var timeline=new CabinetSyncTimeline(60000);for(int i=0;i<8;i++)assertTrue(timeline.coin(2));assertFalse(timeline.coin(2));
        timeline.release(2);for(var frame:timeline.tick())assertEquals(0,frame.p3());assertTrue(timeline.coin(2));
    }
    @Test void hostedPulseInsertionIsAtomicAndFifo(){
        var q=new HostedInputQueue();q.offer(new int[]{1,2,8,16});assertTrue(q.coin(3));q.offer(new int[]{2,2,8,16});
        assertArrayEquals(new int[]{1,2,8,16},q.poll());assertArrayEquals(new int[]{1,2,8,20},q.poll());assertArrayEquals(new int[]{1,2,8,16},q.poll());assertArrayEquals(new int[]{2,2,8,16},q.poll());assertNull(q.poll());
        for(int i=0;i<127;i++)assertTrue(q.offer(new int[]{i%2+1,0,0,0}));assertFalse(q.coin(1));
        int count=0;while(q.poll()!=null)count++;assertEquals(127,count);
    }
    @Test void exactMechanismFrontRaysWorkInFourFacingsAndRejectBackSideAndWrongHeight(){
        for(boolean dual:new boolean[]{false,true})for(int turns=0;turns<4;turns++)for(double x:dual?new double[]{6,18}:new double[]{8}){
            double localX=x/16+(dual?.25:0),y=(dual?9:10)/16D;
            double offset=dual?cn.piq.fcarcade.layout.DualCabinetGeometry.MODEL_Z_OFFSET:0;
            var a=new Point(localX,y,offset-.15);var b=new Point(localX,y,offset+.8);
            assertTrue(CabinetCoinGeometry.hits(dual,turns,rotate(a,turns),rotate(b,turns)));
            assertFalse(CabinetCoinGeometry.hits(dual,turns,rotate(b,turns),rotate(a,turns)));
            assertFalse(CabinetCoinGeometry.hits(dual,turns,rotate(new Point(localX,y+1,offset-.15),turns),rotate(new Point(localX,y+1,offset+.8),turns)));
            if(dual)assertFalse(CabinetCoinGeometry.hits(true,turns,rotate(new Point(localX,y,-2),turns),rotate(new Point(localX,y,.3),turns)),"Retired front plane must not accept coins");
        }
        assertFalse(CabinetCoinGeometry.hits(false,0,new Point(.1,.6,-2),new Point(.1,.6,.8)));
        assertFalse(CabinetCoinGeometry.hits(false,0,new Point(Double.NaN,.6,-2),new Point(.5,.6,.8)));
        assertFalse(CabinetCoinGeometry.hits(false,0,new Point(.5,.6,-2),new Point(.5,.6,-1)));
    }
    private static Point rotate(Point p,int turns){return RocketArcadeGeometry.rotate(p,turns);}
}
