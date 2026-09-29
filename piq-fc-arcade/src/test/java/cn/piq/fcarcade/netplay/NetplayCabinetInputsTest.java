package cn.piq.fcarcade.netplay;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NetplayCabinetInputsTest {
    @Test void freePlayStillAllowsCoinKeyOnCanonicalLane(){var q=new NetplayCabinetInputs(false);q.input(0,4|3080,1);assertEquals(4|3080,q.next(2)[0]);q.release(0);assertEquals(0,q.next(3)[0]);}
    @Test void stripsFreeCoinAndKeepsFourPortsIndependent(){
        var q=new NetplayCabinetInputs();for(int p=0;p<4;p++)q.input(p,4|(16<<p),100);
        assertArrayEquals(new int[]{16,32,64,128},q.next(101));q.release(2);
        assertArrayEquals(new int[]{16,32,0,128},q.next(102));
        assertArrayEquals(new int[4],q.next(2_000_000_100L));
    }
    @Test void paidEdgesSurviveReleaseButNeverReplay(){
        var q=new NetplayCabinetInputs();assertTrue(q.coin(3,1));assertFalse(q.coin(0,1));assertFalse(q.coin(2,0));
        assertTrue(q.coin(3,2));q.release(3);
        for(int i=0;i<12;i++)assertEquals(i%6<3?4:0,q.next(i)[3]);
        assertArrayEquals(new int[4],q.next(13));q.close();assertFalse(q.coin(0,3));
    }
    @Test void queueBoundAndClose(){
        var q=new NetplayCabinetInputs();for(int i=1;i<=64;i++)assertTrue(q.coin(0,i));
        assertThrows(IllegalStateException.class,()->q.coin(0,65));q.close();assertArrayEquals(new int[4],q.next(0));
        assertThrows(IllegalArgumentException.class,()->q.input(4,0,0));assertThrows(IllegalArgumentException.class,()->q.input(0,4096,0));
    }
    @Test void authorityHostOwnsAllPortsAndPeersCannotRequestPlay(){
        var host=new NetplayProcess.Grant(1,UUID.randomUUID(),true,true,0);
        var peer=new NetplayProcess.Grant(1,UUID.randomUUID(),false,true,3);
        String h=NetplayProcess.cabinetConfig(host,false,true,4),p=NetplayProcess.cabinetConfig(peer,false,true,4);
        for(int i=1;i<=4;i++)assertTrue(h.contains("netplay_request_device_p"+i+" = \"true\""));
        assertTrue(p.contains("netplay_start_as_spectator = \"true\""));
        assertEquals(NetplayProcess.config(peer,false),NetplayProcess.cabinetConfig(peer,false,false,4));
    }
}
