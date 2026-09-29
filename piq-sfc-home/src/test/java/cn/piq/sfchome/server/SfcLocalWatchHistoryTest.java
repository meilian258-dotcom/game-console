package cn.piq.sfchome.server;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcLocalWatchHistoryTest {
    @Test void observerReadDoesNotCreateControllerRepairOrChangeInputHead(){
        var ledger=new SfcRepairLedger();for(int frame=0;frame<1200;frame+=4)ledger.append(frame,new int[]{0,1,2,3},new int[]{4,8,16,32});
        UUID host=UUID.randomUUID();ledger.report(host,true,600,"a".repeat(64));ledger.report(host,true,1200,"b".repeat(64));
        assertEquals("a".repeat(64),ledger.hostDigest(600));assertEquals("b".repeat(64),ledger.hostDigest(1200));
        assertNull(ledger.hostDigest(601));assertNull(ledger.active());
        int next=600;while(next<1200){var replay=ledger.replay(next);assertEquals(next,replay.first());assertTrue(replay.p1().length<=256);
            for(int i=0;i<replay.p1().length;i++)assertEquals((next+i)%4,replay.p1()[i]);next+=replay.p1().length;}
        assertEquals(1200,ledger.next());assertEquals(0,ledger.replay(1200).p1().length);assertNull(ledger.active());
    }
    @Test void lateObservationRejectsExpiredHistoryInsteadOfRebootingHost(){
        var ledger=new SfcRepairLedger();for(int frame=0;frame<10000;frame+=4)ledger.append(frame,new int[4],new int[4]);
        assertThrows(IllegalStateException.class,()->ledger.replay(0));assertEquals(10000,ledger.next());assertNull(ledger.active());
    }
}
