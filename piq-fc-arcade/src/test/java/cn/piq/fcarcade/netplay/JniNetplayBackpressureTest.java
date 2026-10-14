// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.*;
import cn.piq.retro.netplay.RollbackTimeline;
import cn.piq.retro.netplay.RollbackTimeline.Input;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.netplay.JniNetplayCodec.*;

/** Deterministic owner-thread capacity edges in the real session and timeline.
 * Only the core is fake; no enlarged history, altered CRC or native-runtime claim. */
class JniNetplayBackpressureTest {
    private static Object get(Object value,String name)throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    private static void set(Object value,String name,Object replacement)throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,replacement);
    }
    private static Object call(Object value,String name)throws Exception {
        var method=value.getClass().getDeclaredMethod(name);method.setAccessible(true);
        try{return method.invoke(value);}catch(InvocationTargetException failure){
            if(failure.getCause() instanceof Exception cause)throw cause;
            throw (Error)failure.getCause();
        }
    }
    private static final class Fixture {
        final JniNetplaySessionTest.Core core;
        final JniNetplaySession session;
        final RollbackTimeline<LibretroProcess.Output> timeline;
        final List<Message> sent=new ArrayList<>();
        final Object remote;
        final NavigableMap<Long,Input> canonical;
        final NavigableMap<Long,Integer> future;
        final NavigableMap<Long,String> pending;
        @SuppressWarnings("unchecked")
        Fixture(boolean host,boolean gun,NetplayCabinetInputs cabinet,int retained)throws Exception {
            core=new JniNetplaySessionTest.Core(gun);core.load(new byte[16]);
            NetplayProfile profile=null;
            if(cabinet!=null){
                var runtime=new LibretroProfile("Test","zip",true,List.of(1,1,1,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact("/core/test.dll","a".repeat(64))));
                profile=new NetplayProfile(getClass(),"/core/test.dll","a".repeat(64),"test.zip",Map.of(),1,48000,1024,4).withJni(runtime);
            }
            session=new JniNetplaySession(new NetplayProcess.Grant(760,UUID.randomUUID(),host,true,host?0:1),
                    ()->new byte[16],packet->sent.add(decode(packet.bytes())),gun,()->core,profile,Map::of,cabinet);
            set(session,"core",core);
            var method=JniNetplaySession.class.getDeclaredMethod("newTimeline",long.class);method.setAccessible(true);
            timeline=(RollbackTimeline<LibretroProcess.Output>)method.invoke(session,80L);
            set(session,"timeline",timeline);set(session,"ready",true);
            for(int n=0;n<retained;n++)timeline.advance(new Input(timeline.next(),0,0,0,0,gun?65536:0,3));
            var peerType=Class.forName(JniNetplaySession.class.getName()+"$Peer");
            var constructor=peerType.getDeclaredConstructor(UUID.class,int.class);constructor.setAccessible(true);
            remote=constructor.newInstance(UUID.randomUUID(),1);set(session,"remote",remote);
            canonical=(NavigableMap<Long,Input>)get(session,"canonicalFuture");
            future=(NavigableMap<Long,Integer>)get(session,"futurePads");
            pending=(NavigableMap<Long,String>)get(session,"pendingDigests");
            set(session,"confirmed",96L);set(session,"lastHostNext",108L);
        }
        Fixture(boolean host,int retained)throws Exception {this(host,false,null,retained);}
        void confirmed(long frame)throws Exception {set(session,"confirmed",frame);}
        void hostNext(long frame)throws Exception {set(session,"lastHostNext",frame);}
        void peer()throws Exception {call(session,"peerFrame");}
        void host()throws Exception {call(session,"hostFrame");}
        List<Pad> pads(){return sent.stream().filter(Pad.class::isInstance).map(Pad.class::cast).toList();}
    }

    @Test void fourFrameCatchUpReclaimsOnlyConfirmedHistoryBeforeThirdPad()throws Exception {
        var f=new Fixture(false,30); // oldest=80, next=110, two free slots.
        f.confirmed(102);f.hostNext(114); // Valid Commands: next-confirmed=12.
        for(int n=0;n<4;n++)f.canonical.put(110L+n,new Input(110L+n,256+n,0,1));
        for(int n=0;n<4;n++){f.session.input(16+n);f.peer();call(f.session,"checkDigests");}
        assertEquals(114,f.timeline.next());assertEquals(86,f.timeline.oldest());
        assertEquals(28,f.timeline.retained());assertTrue(f.canonical.isEmpty());
        assertEquals(List.of(110L,111L,112L,113L),f.pads().stream().map(Pad::frame).toList());
        assertEquals(List.of(16,17,18,19),f.pads().stream().map(Pad::mask).toList());
        for(int n=0;n<4;n++){assertEquals(256+n,f.timeline.input(110+n).p1());assertEquals(16+n,f.timeline.input(110+n).p2());}
        assertEquals(4,f.session.framesReceived());
    }
    @Test void fullUnconfirmedWindowWaitsWithoutSendingOrConsumingThenResumesExactlyOnce()throws Exception {
        var f=new Fixture(false,32);f.session.input(16);
        var canonical=new Input(112,256,0,1);f.canonical.put(112L,canonical);
        set(f.session,"lastP1",8);set(f.session,"lastP2",32);
        for(int n=0;n<4;n++)f.peer();
        assertEquals(112,f.timeline.next());assertEquals(80,f.timeline.oldest());assertTrue(f.sent.isEmpty());
        assertSame(canonical,f.canonical.get(112L));assertEquals(8,get(f.session,"lastP1"));assertEquals(32,get(f.session,"lastP2"));
        assertEquals(0,f.session.framesReceived());assertEquals(0L,get(f.session,"nextPresent"));
        f.confirmed(102);f.hostNext(114);f.peer();
        assertEquals(List.of(new Pad(112,16)),f.pads());assertNull(f.canonical.get(112L));
        assertEquals(113,f.timeline.next());assertEquals(256,f.timeline.input(112).p1());assertEquals(16,f.timeline.input(112).p2());
    }
    @Test void hostDoesNotRemoveFuturePadOrAdvancePacingWhileBlocked()throws Exception {
        var f=new Fixture(true,32);f.future.put(112L,128);f.session.input(256);
        set(f.session,"lastP1",8);set(f.session,"lastP2",32);
        for(int n=0;n<4;n++)f.host();
        assertEquals(128,f.future.get(112L));assertEquals(112,f.timeline.next());assertTrue(f.sent.isEmpty());
        assertEquals(8,get(f.session,"lastP1"));assertEquals(32,get(f.session,"lastP2"));assertEquals(0L,get(f.session,"nextPresent"));
        f.confirmed(102);f.host();
        assertTrue(f.future.isEmpty());assertEquals(113,f.timeline.next());assertEquals(128,f.timeline.input(112).p2());
        assertEquals(1,f.session.framesReceived());
    }
    @Test void dueDigestIsVerifiedBeforeItsSnapshotCanBeTrimmed()throws Exception {
        var f=new Fixture(false,32);f.confirmed(102);f.hostNext(114);
        f.pending.put(80L,NetplaySaveState.hash(f.timeline.stateBefore(80)));
        f.pending.put(114L,"0".repeat(64)); // Future snapshot must remain pending.
        f.peer();
        assertInstanceOf(Digest.class,f.sent.getFirst());assertEquals(80,((Digest)f.sent.getFirst()).frame());
        assertInstanceOf(Pad.class,f.sent.get(1));assertEquals(Set.of(114L),f.pending.keySet());
        assertEquals(86,f.timeline.oldest());assertEquals(113,f.timeline.next());
    }
    @Test void mismatchingDueDigestStillFailsBeforeTrimPadOrCoreStep()throws Exception {
        var f=new Fixture(false,32);f.confirmed(102);f.hostNext(114);
        f.pending.put(80L,"0".repeat(64));int steps=f.core.applied.size();
        var failure=assertThrows(IllegalArgumentException.class,f::peer);assertTrue(failure.getMessage().contains("CRC"));
        assertEquals(80,f.timeline.oldest());assertEquals(112,f.timeline.next());assertEquals(steps,f.core.applied.size());assertTrue(f.sent.isEmpty());
    }
    @Test void queuedCoinEdgesAreNotConsumedByCapacityRetries()throws Exception {
        var cabinet=new NetplayCabinetInputs(true);var f=new Fixture(true,false,cabinet,32);
        assertTrue(cabinet.coin(3,1));
        for(int n=0;n<5;n++)f.host();
        assertEquals(112,f.timeline.next());assertEquals(0,f.session.framesReceived());
        f.confirmed(112);
        for(int n=0;n<4;n++)f.host();
        for(int n=0;n<3;n++)assertEquals(4,f.timeline.input(112+n).p4());
        assertEquals(0,f.timeline.input(115).p4());
    }
    @Test void queuedGunTriggerAndReleaseSurviveCapacityRetries()throws Exception {
        var f=new Fixture(true,true,null,32);
        int shot=cn.piq.fcarcade.session.ZapperInput.pack(100,60,false,true);
        int release=cn.piq.fcarcade.session.ZapperInput.pack(100,60,false,false);
        f.session.authoritativeGun(1,1,1,shot);f.session.authoritativeGun(1,2,0,release);
        for(int n=0;n<5;n++)f.host();
        assertEquals(112,f.timeline.next());f.confirmed(112);f.host();f.host();
        assertEquals(shot,f.timeline.input(112).gun());assertEquals(256,f.timeline.input(112).p1());
        assertEquals(release,f.timeline.input(113).gun());assertEquals(0,f.timeline.input(113).p1());
    }
    @Test void fullFinalSaveFailsClosedWithoutReplacingPreviousSave()throws Exception {
        var f=new Fixture(true,32);var saves=new AtomicInteger();var finishes=new AtomicInteger();
        set(f.session,"persistence",new NetplayProcess.Persistence(){
            public byte[] load(NetplaySaveState.Identity identity){return null;}
            public CompletableFuture<Void> save(byte[] bytes){saves.incrementAndGet();return CompletableFuture.completedFuture(null);}
            public CompletableFuture<Void> finish(){finishes.incrementAndGet();return CompletableFuture.completedFuture(null);}
        });
        call(f.session,"finishSave");
        assertEquals(112,f.timeline.next());assertEquals(0,saves.get());assertEquals(0,finishes.get());
        assertEquals("最终保存未确认，保留最近确认版本",f.session.saveStatus());
    }
    @Test void capacityStillCapsAt32AndCannotDiscardUnconfirmedFrames()throws Exception {
        var f=new Fixture(true,32);assertEquals(32,RollbackTimeline.HISTORY);
        assertFalse(f.timeline.canAdvance());f.host();
        assertEquals(32,f.timeline.retained());assertEquals(80,f.timeline.oldest());
        assertThrows(IllegalStateException.class,()->f.timeline.advance(0,0,3));
    }
}
