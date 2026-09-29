package cn.piq.sfchome.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcWatchDemandTest {
    @Test void repeatedHeartbeatRefreshesOnlySameIdentity(){
        var gate=new SfcWatchDemand();Object c=new Object();gate.connection(c);
        assertTrue(gate.accept(c,2,"source-a",true));
        assertTrue(gate.accept(c,2,new String("source-a"),true));
        assertFalse(gate.accept(c,2,"source-b",true));
        assertFalse(gate.accept(c,1,"source-a",true));
    }
    @Test void stopAndLocalFailureCannotBeRevivedByOldHeartbeat(){
        var gate=new SfcWatchDemand();Object c=new Object();gate.connection(c);
        assertTrue(gate.accept(c,1,"a",true));assertFalse(gate.accept(c,2,"a",false));
        assertFalse(gate.accept(c,2,"a",true));assertFalse(gate.accept(c,1,"a",true));
        assertTrue(gate.accept(c,3,"a",true));gate.block();
        assertFalse(gate.accept(c,3,"a",true));assertTrue(gate.accept(c,4,"a",true));
    }
    @Test void sameRevisionLocalTimeoutResumesOnlyWithSameAuthorizedIdentity(){
        var gate=new SfcWatchDemand();Object c=new Object();gate.connection(c);
        assertTrue(gate.accept(c,9,"a",true));assertFalse(gate.accept(c,9,"a",false));
        assertFalse(gate.blocked());assertFalse(gate.accept(c,9,"forged",true));assertTrue(gate.accept(c,9,"a",true));
        gate.block();assertFalse(gate.accept(c,9,"a",true));
    }
    @Test void oldConnectionCannotReplaceNewConnectionEvenWithHigherRevision(){
        var gate=new SfcWatchDemand();Object a=new String("same"),b=new String("same");gate.connection(a);
        assertTrue(gate.accept(a,900,"a",true));gate.connection(b);
        assertFalse(gate.accept(a,1000,"b",true));assertTrue(gate.accept(b,1,"b",true));
        gate.connection(null);assertFalse(gate.accept(b,2,"b",true));assertFalse(gate.accept(null,2,"b",true));
    }
    @Test void badIdentityAndSequenceAreRejectedWithoutAdvancing(){
        var gate=new SfcWatchDemand();Object c=new Object();gate.connection(c);
        assertFalse(gate.accept(c,-1,"a",true));assertFalse(gate.accept(c,3,null,true));
        assertTrue(gate.accept(c,1,"a",true));assertEquals(1,gate.revision());
        assertFalse(gate.accept(c,1,"forged",false));assertFalse(gate.blocked());
        assertTrue(gate.accept(c,1,"a",true));
    }
    @Test void oldSourceCleanupCannotPoisonNewConnectionRevision(){
        var gate=new SfcWatchDemand();Object oldConnection=new Object(),newConnection=new Object();
        gate.connection(oldConnection);assertTrue(gate.acceptAuthorized(oldConnection,1000,"old source",true,true));
        gate.connection(newConnection);
        assertFalse(gate.acceptAuthorized(newConnection,1001,"old source",false,false));
        assertEquals(-1,gate.revision());assertTrue(gate.acceptAuthorized(newConnection,1,"new source",true,true));
    }
}
