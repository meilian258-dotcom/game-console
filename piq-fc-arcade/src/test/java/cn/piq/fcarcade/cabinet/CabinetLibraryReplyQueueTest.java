package cn.piq.fcarcade.cabinet;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetLibraryReplyQueueTest {
    @Test void shortBackpressureRetriesOncePerIntervalAndStopsAfterSuccess(){
        var q=new CabinetLibraryReplyQueue<String,Integer>();var sent=new ArrayList<Integer>();
        assertTrue(q.offer("connection",1,0));
        q.tick(249_999_999L,v->true,v->{fail("too early");return false;},v->fail("expired"));
        q.tick(250_000_000L,v->true,v->{sent.add(v);return false;},v->fail("expired"));
        assertEquals(1,q.size());
        q.tick(499_999_999L,v->true,v->{fail("too early");return false;},v->fail("expired"));
        q.tick(500_000_000L,v->true,v->{sent.add(v);return true;},v->fail("expired"));
        assertEquals(List.of(1,1),sent);assertEquals(0,q.size());
    }
    @Test void newestRequestReplacesOldReplyForTheSameConnection(){
        var q=new CabinetLibraryReplyQueue<String,Integer>();q.offer("a",1,0);q.offer("a",2,1);
        assertEquals(1,q.size());q.tick(250_000_001L,v->true,v->{assertEquals(2,v);return true;},v->fail());assertEquals(0,q.size());
    }
    @Test void revokedConnectionIsRemovedWithoutSendingOrExpiryFeedback(){
        var q=new CabinetLibraryReplyQueue<String,Integer>();q.offer("old",1,0);
        q.tick(1,v->false,v->{fail();return false;},v->fail());assertEquals(0,q.size());
    }
    @Test void longBackpressureExpiresExactlyOnceAtFiveSeconds(){
        var q=new CabinetLibraryReplyQueue<String,Integer>();var expired=new ArrayList<Integer>();q.offer("a",1,0);
        q.tick(5_000_000_000L,v->true,v->{fail();return false;},expired::add);
        q.tick(6_000_000_000L,v->true,v->{fail();return false;},expired::add);
        assertEquals(List.of(1),expired);assertEquals(0,q.size());
    }
    @Test void queueIsBoundedButExistingConnectionsCanReplaceTheirReply(){
        var q=new CabinetLibraryReplyQueue<Integer,Integer>();
        for(int i=0;i<64;i++)assertTrue(q.offer(i,i,0));
        assertFalse(q.offer(64,64,0));assertTrue(q.offer(0,100,1));assertEquals(64,q.size());
        q.remove(0);assertTrue(q.offer(64,64,0));assertEquals(64,q.size());
    }
    @Test void nullKeysAndValuesAreRejected(){var q=new CabinetLibraryReplyQueue<String,Integer>();assertThrows(NullPointerException.class,()->q.offer(null,1,0));assertThrows(NullPointerException.class,()->q.offer("a",null,0));}
}
