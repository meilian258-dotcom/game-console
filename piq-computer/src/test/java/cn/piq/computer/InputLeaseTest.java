package cn.piq.computer;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class InputLeaseTest {
    @Test void oneOwner(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);assertNotNull(t);assertNull(l.acquire(UUID.randomUUID(),1));assertTrue(l.accept(p,t,0,1));}
    @Test void staleTicketsNeverAffectNewSeat(){var l=new InputLease();var p=UUID.randomUUID();var old=l.acquire(p,0);var next=l.acquire(p,1);assertNotEquals(old,next);assertFalse(l.accept(p,old,99,2));assertFalse(l.release(p,old));assertTrue(l.accept(p,next,0,2));}
    @Test void sequencesMonotonic(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);assertFalse(l.accept(p,t,-1,1));assertTrue(l.accept(p,t,5,1));assertFalse(l.accept(p,t,5,2));assertFalse(l.accept(p,t,4,2));assertTrue(l.accept(p,t,6,2));}
    @Test void timeoutReleasesOtherPlayer(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);assertFalse(l.accept(p,t,0,InputLease.TIMEOUT));assertNotNull(l.acquire(UUID.randomUUID(),InputLease.TIMEOUT));}
    @Test void rejectedPacketsDoNotRenew(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);assertFalse(l.accept(UUID.randomUUID(),t,0,59));assertFalse(l.active(60));}
    @Test void boundedRateAndNextTickRecovery(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);for(int i=0;i<32;i++)assertTrue(l.accept(p,t,i,1));assertFalse(l.accept(p,t,32,1));assertTrue(l.accept(p,t,33,2));}
    @Test void releaseResets(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);assertTrue(l.release(p,t));assertFalse(l.active(0));assertFalse(l.accept(p,t,0,0));assertNull(l.owner());}
    @Test void heartbeatExtends(){var l=new InputLease();var p=UUID.randomUUID();var t=l.acquire(p,0);for(int i=0;i<100;i++)assertTrue(l.accept(p,t,i,i*20L));assertTrue(l.active(100*20L));}
}
