package cn.piq.fcarcade.client.watch;

import cn.piq.fcarcade.cabinet.WatchNetwork;
import cn.piq.fcarcade.cabinet.WatchPreferenceState;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchManagementStateTest {
    private static UUID id(long n){return new UUID(0,n);}
    private static WatchPreferenceState.Source source(long n){return new WatchPreferenceState.Source(ResourceLocation.parse("test:machine"),id(n),id(n+100));}
    private static WatchManagementState.Handle handle(WatchManagementState s,long n){return new WatchManagementState.Handle(s.epoch(),source(n),n+1,id(n+1000));}
    private static WatchNetwork.PreferenceResult ack(WatchNetwork.Preference p){return new WatchNetwork.PreferenceResult(p.sequence(),p.source(),p.paused(),true,"confirmed");}
    @Test void sourcePauseIsLocalImmediateAndLateGrantCannotRestartIt(){
        var s=new WatchManagementState();s.connection(new Object());var a=handle(s,1);var b=handle(s,2);
        assertTrue(s.change(a,true));assertTrue(s.paused(a.source()));assertFalse(s.paused(b.source()));
        var late=new WatchManagementState.Handle(s.epoch(),a.source(),500,id(500));
        assertTrue(s.granted(late));assertEquals(List.of(late),s.paused());assertFalse(s.granted(b));
        assertFalse(s.canResume(a));assertTrue(s.canResume(late));
        var packet=s.next(0);assertEquals(late.lease(),packet.lease());assertEquals(500,packet.revision());
        assertFalse(s.change(a,false));assertTrue(s.change(late,false));assertTrue(s.paused(a.source()));assertTrue(s.resumePending(a.source()));
    }
    @Test void sameUuidDifferentProviderOrHostGenerationIsAnotherSource(){
        var s=new WatchManagementState();s.connection(new Object());var a=handle(s,1);s.change(a,true);
        assertFalse(s.paused(new WatchPreferenceState.Source(a.source().provider(),a.source().source(),id(999))));
        assertFalse(s.paused(new WatchPreferenceState.Source(ResourceLocation.parse("test:other"),a.source().source(),a.source().hostLease())));
    }
    @Test void onlyExactAckAdvancesTheSingleInFlightQueue(){
        var s=new WatchManagementState();s.connection(new Object());s.change(handle(s,1),true);s.change(handle(s,2),true);
        var first=s.next(0);assertNull(s.next(50));
        assertFalse(s.acknowledge(new WatchNetwork.PreferenceResult(first.sequence()+1,first.source(),true,true,"wrong sequence")));
        assertFalse(s.acknowledge(new WatchNetwork.PreferenceResult(first.sequence(),source(9),true,true,"wrong source")));
        assertFalse(s.acknowledge(new WatchNetwork.PreferenceResult(first.sequence(),first.source(),false,true,"wrong action")));
        assertNull(s.next(51));assertTrue(s.acknowledge(ack(first)));
        var second=s.next(52);assertNotNull(second);assertTrue(second.sequence()>first.sequence());assertEquals(source(2),second.source());
    }
    @Test void restoringAllSixtyFourSourcesIsQueuedAndAcknowledgedWithoutTruncation(){
        var s=new WatchManagementState();s.connection(new Object());int tick=0;
        for(int n=0;n<64;n++)assertTrue(s.change(handle(s,n),true));
        assertFalse(s.change(handle(s,65),true));assertEquals(64,s.paused().size());
        var paused=new HashSet<WatchPreferenceState.Source>();
        for(int n=0;n<64;n++,tick+=5){var p=s.next(tick);assertNotNull(p);assertTrue(p.paused());paused.add(p.source());assertTrue(s.acknowledge(ack(p)));assertNull(s.next(tick));}
        for(var h:s.paused())assertTrue(s.change(h,false));assertEquals(64,s.paused().size());assertEquals(64,s.pending());
        var resumed=new HashSet<WatchPreferenceState.Source>();
        for(int n=0;n<64;n++,tick+=5){var p=s.next(tick);assertNotNull(p);assertFalse(p.paused());resumed.add(p.source());assertTrue(s.acknowledge(ack(p)));}
        assertEquals(paused,resumed);assertTrue(s.paused().isEmpty());assertEquals(0,s.pending());assertNull(s.next(tick));
    }
    @Test void fastResumeCoalescesUnsentPauseAndOldPauseAckCannotOverwriteNewIntent(){
        var s=new WatchManagementState();s.connection(new Object());var a=handle(s,1);
        s.change(a,true);assertTrue(s.change(a,false));assertFalse(s.next(0).paused());
        s.connection(new Object());a=handle(s,1);s.change(a,true);var pause=s.next(0);s.change(a,false);
        assertTrue(s.acknowledge(ack(pause)));assertTrue(s.paused(a.source()));assertFalse(s.status().equals("confirmed"));
        var resume=s.next(5);assertFalse(resume.paused());assertTrue(s.acknowledge(ack(resume)));assertEquals("confirmed",s.status());assertFalse(s.paused(a.source()));
    }
    @Test void reconnectClearsPreferencesAndOldUiHandleDespiteEqualConnectionObjects(){
        var s=new WatchManagementState();var old=new String("equal");var next=new String("equal");s.connection(old);
        var h=handle(s,1);s.change(h,true);var packet=s.next(0);assertFalse(s.connection(old));
        assertTrue(s.connection(next));assertFalse(s.current(h));assertFalse(s.change(h,true));assertTrue(s.paused().isEmpty());
        assertNull(s.next(500));assertFalse(s.acknowledge(ack(packet)));assertEquals(0,s.pending());
        s.connection(null);assertFalse(s.change(handle(s,2),true));
    }
    @Test void boundedTimeoutDoesNotUndoLocalPauseOrBlockTheWholeOutbox(){
        var s=new WatchManagementState();s.connection(new Object());s.change(handle(s,1),true);s.change(handle(s,2),true);
        var lost=s.next(0);assertNull(s.next(99));var second=s.next(100);assertNotNull(second);
        assertTrue(s.paused(lost.source()));assertFalse(s.acknowledge(ack(lost)));assertTrue(s.acknowledge(ack(second)));
        assertEquals(0,s.pending());assertEquals(2,s.paused().size());
    }
    @Test void noCompletedFutureCanPretendAStillHeldNativeOwnerIsFree(){
        assertFalse(WatchManagementState.released(false,true,false));assertFalse(WatchManagementState.released(true,false,false));
        assertFalse(WatchManagementState.released(true,true,true));assertTrue(WatchManagementState.released(true,true,false));
    }
    @Test void lostResumeOrSendExceptionKeepsARowThatCanBeRetried(){
        var s=new WatchManagementState();s.connection(new Object());var h=handle(s,1);s.change(h,true);s.acknowledge(ack(s.next(0)));
        assertTrue(s.change(h,false));var lost=s.next(5);assertTrue(s.paused(h.source()));assertTrue(s.resumePending(h.source()));
        // A send exception or a missing server response both mean no ACK arrived.
        assertNull(s.next(105));assertTrue(s.paused(h.source()));assertFalse(s.resumePending(h.source()));
        assertFalse(s.acknowledge(ack(lost)));assertTrue(s.change(h,false));var retry=s.next(106);assertNotNull(retry);
        assertTrue(retry.sequence()>lost.sequence());assertTrue(s.acknowledge(ack(retry)));assertFalse(s.paused(h.source()));
    }
    @Test void rejectedResumeIsVisibleAndStillRetryableWithoutLosingThePausedHandle(){
        var s=new WatchManagementState();s.connection(new Object());var h=handle(s,1);s.change(h,true);s.acknowledge(ack(s.next(0)));
        s.change(h,false);var request=s.next(5);
        assertTrue(s.acknowledge(new WatchNetwork.PreferenceResult(request.sequence(),request.source(),false,false,"拒绝")));
        assertEquals(List.of(h),s.paused());assertFalse(s.resumePending(h.source()));assertTrue(s.status().contains("可再次恢复"));
        assertTrue(s.change(h,false));var retry=s.next(10);assertTrue(s.acknowledge(ack(retry)));assertTrue(s.paused().isEmpty());
    }
    @Test void lateGrantDuringResumeDoesNotFlipTheQueuedIntentBackToPause(){
        var s=new WatchManagementState();s.connection(new Object());var h=handle(s,1);s.change(h,true);s.acknowledge(ack(s.next(0)));s.change(h,false);
        var late=new WatchManagementState.Handle(s.epoch(),h.source(),600,id(600));assertFalse(s.granted(late));
        assertTrue(s.resumePending(h.source()));var resume=s.next(5);assertFalse(resume.paused());assertEquals(h.lease(),resume.lease());
        assertTrue(s.acknowledge(ack(resume)));assertFalse(s.paused(h.source()));
    }
}
