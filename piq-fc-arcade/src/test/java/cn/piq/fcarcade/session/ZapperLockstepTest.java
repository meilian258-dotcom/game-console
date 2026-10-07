package cn.piq.fcarcade.session;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZapperLockstepTest {
    @Test void allNativePixelsRoundTripAndOffscreenIsCanonical(){
        for(int y=0;y<240;y++)for(int x=0;x<256;x++)for(boolean trigger:new boolean[]{false,true}){
            int p=ZapperInput.pack(x,y,false,trigger);assertEquals(x,ZapperInput.x(p));assertEquals(y,ZapperInput.y(p));assertEquals(trigger,ZapperInput.trigger(p));assertEquals(p,ZapperInput.validate(p));
        }
        assertEquals(ZapperInput.NEUTRAL,ZapperInput.pack(-200,999,true,false));
        assertThrows(IllegalArgumentException.class,()->ZapperInput.validate(ZapperInput.NEUTRAL|1));
        assertThrows(IllegalArgumentException.class,()->ZapperInput.pack(0,240,false,false));
        assertThrows(IllegalArgumentException.class,()->ZapperInput.validate(1<<20));
    }
    @Test void sameTickTapTapPreservesEachTriggerLevel(){var q=new ZapperInputQueue();int on=ZapperInput.pack(80,90,false,true),off=ZapperInput.pack(80,90,false,false);
        for(int s:new int[]{on,off,on,off})assertTrue(q.offer(s));for(int s:new int[]{on,off,on,off})assertEquals(s,q.nextFrame());assertEquals(off,q.nextFrame());}
    @Test void motionCoalescesWithoutErasingTriggerBoundaries(){var q=new ZapperInputQueue();for(int x=0;x<200;x++)q.offer(ZapperInput.pack(x,10,false,false));assertEquals(1,q.pending());
        q.offer(ZapperInput.pack(200,10,false,true));q.offer(ZapperInput.pack(201,10,false,true));q.offer(ZapperInput.pack(202,10,false,false));
        assertEquals(199,ZapperInput.x(q.nextFrame()));assertTrue(ZapperInput.trigger(q.nextFrame()));assertFalse(ZapperInput.trigger(q.nextFrame()));}
    @Test void overflowFailsDarkAndRequiresTriggerRelease(){var q=new ZapperInputQueue();for(int i=0;i<=32;i++)q.offer(ZapperInput.pack(10,10,false,(i&1)==0));
        assertEquals(ZapperInput.NEUTRAL,q.nextFrame());assertFalse(q.offer(ZapperInput.pack(20,20,false,true)));assertEquals(ZapperInput.NEUTRAL,q.nextFrame());
        assertFalse(q.offer(ZapperInput.pack(20,20,false,false)));assertTrue(q.offer(ZapperInput.pack(20,20,false,true)));assertTrue(ZapperInput.trigger(q.nextFrame()));}
    @Test void staleEpochSequenceAndWrongForceCannotClearNewPress(){var l=new LockstepState();var p=UUID.randomUUID();l.restart();int shot=ZapperInput.pack(40,50,false,true);
        assertTrue(l.acceptZapper(p,1,10,shot,false,false));assertFalse(l.acceptZapper(p,0,11,ZapperInput.NEUTRAL,true,false));assertFalse(l.acceptZapper(p,1,9,ZapperInput.NEUTRAL,true,false));
        assertFalse(l.acceptZapper(p,1,11,shot,true,false));assertEquals(shot,l.advanceFrame().zapperState());assertTrue(l.acceptZapper(p,1,11,ZapperInput.NEUTRAL,true,false));assertEquals(ZapperInput.NEUTRAL,l.advanceFrame().zapperState());}
    @Test void controllerAndGunUseSeparateSequencesAndReleaseIndependently(){var l=new LockstepState();var p=UUID.randomUUID();l.restart();
        assertTrue(l.acceptInput(p,1,0,0,128));assertTrue(l.acceptZapper(p,1,0,ZapperInput.pack(1,1,false,true),false,false));var f=l.advanceFrame();assertEquals(128,f.playerOneMask());assertTrue(ZapperInput.trigger(f.zapperState()));
        l.clearZapper();f=l.advanceFrame();assertEquals(128,f.playerOneMask());assertEquals(ZapperInput.NEUTRAL,f.zapperState());}
    @Test void restartRejectsOldGunAndClearsQueue(){var l=new LockstepState();var p=UUID.randomUUID();l.restart();l.acceptZapper(p,1,100,ZapperInput.pack(1,1,false,true),false,false);l.restart();
        assertFalse(l.acceptZapper(p,1,101,ZapperInput.pack(1,1,false,true),false,false));assertEquals(ZapperInput.NEUTRAL,l.advanceFrame().zapperState());assertTrue(l.acceptZapper(p,2,0,ZapperInput.NEUTRAL,false,false));}
    @Test void timelineNeverMergesDifferentGunFramesAndSuffixRetainsThem(){var t=new LockstepTimeline();t.reset(1);var values=new ArrayList<Integer>();
        for(int i=1;i<=3600;i++){int s=ZapperInput.pack(i%256,(i/256)%240,false,(i&1)==0);values.add(s);t.record(new LockstepState.FrameStep(1,i,0,0,s));}
        assertFalse(t.canRecordFrames(1));assertEquals(3600,t.snapshot().size());var suffix=t.snapshotAfter(1770);assertEquals(values.subList(1770,3600),suffix.stream().map(LockstepInputRun::zapperState).toList());
        t.discardThrough(1770);assertEquals(suffix,t.snapshot());assertTrue(t.canRecordFrames(1770));}
    @Test void legacyOverloadsStayCanonicalNeutral(){assertEquals(ZapperInput.NEUTRAL,new LockstepInputRun(3,1,2).zapperState());assertEquals(ZapperInput.NEUTRAL,new LockstepState.FrameStep(1,1,1,2).zapperState());}
    @Test void variantsNeverShareStateNamespace(){assertNotEquals(NesCoreVariant.LEGACY.stateNamespace(),NesCoreVariant.ZAPPER_V1.stateNamespace());assertTrue(NesCoreVariant.ZAPPER_V1.stateNamespace().contains("b8b2a725"));assertThrows(IllegalArgumentException.class,()->NesCoreVariant.fromNetwork(5));}
}
