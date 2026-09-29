package cn.piq.fcarcade.client.cabinet;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure lifecycle regressions using actual game-key state, not source strings. */
class CabinetInputLifecycleTest {
    @Test void initialHeldGameKeyMustBeReleasedBeforeFirstPress(){
        var input=new CabinetImmersiveInput();var held=new HashSet<Integer>();held.add(74);
        assertFalse(input.activate(true,held::contains));assertFalse(input.armed());
        assertFalse(input.key(74,1));assertFalse(input.key(75,1));assertEquals(0,input.mask());
        held.clear();input.activate(true,held::contains);assertTrue(input.armed());
        assertTrue(input.key(74,1));assertEquals(1,input.mask());assertTrue(input.key(74,0));assertEquals(0,input.mask());
    }
    @Test void menuOpeningFlushesOnceAndClosingEnterCannotStartGame(){
        var input=new CabinetImmersiveInput();input.activate(true,k->false);input.key(74,1);
        assertTrue(input.activate(false,k->{throw new AssertionError("Inactive sampling");}));
        assertFalse(input.activate(false,k->true));assertFalse(input.armed());assertEquals(0,input.mask());
        input.activate(true,k->k==257);assertFalse(input.key(257,1));assertFalse(input.key(74,1));assertFalse(input.armed());
        input.activate(true,k->false);assertTrue(input.armed());assertTrue(input.key(257,1));assertEquals(8,input.mask());
    }
    @Test void focusLossRejectsRepeatAndReplayedPressUntilAllGameKeysUp(){
        var input=new CabinetImmersiveInput();input.activate(true,k->false);input.key(265,1);input.key(74,1);
        assertTrue(input.activate(false,k->false));input.activate(true,k->k==74||k==265);
        assertFalse(input.key(74,2));assertFalse(input.key(265,1));assertEquals(0,input.mask());
        input.activate(true,k->k==74);assertFalse(input.armed());
        input.activate(true,k->false);assertTrue(input.armed());assertTrue(input.key(265,1));assertEquals(16,input.mask());
    }
    @Test void wasdMouseMovementAndModifierKeysDoNotBlockRearming(){
        Set<Integer> movement=Set.of(87,83,65,68,32,340,0,1);
        var input=new CabinetImmersiveInput();input.activate(true,movement::contains);assertTrue(input.armed());
        for(int key:movement)assertFalse(input.key(key,1));assertEquals(0,input.mask());assertTrue(input.key(74,1));
    }
    @Test void armedFastTapDeliveryIsStillEventBasedBetweenTicks(){
        var input=new CabinetImmersiveInput();input.activate(true,k->false);
        for(int i=0;i<200;i++){
            assertTrue(input.key(74,1));assertEquals(1,input.mask());assertFalse(input.key(74,2));
            assertTrue(input.key(74,0));assertEquals(0,input.mask());
        }
        input.activate(true,k->{throw new AssertionError("Armed path must not poll or replace captured edges");});
        assertTrue(input.armed());
    }
    @Test void resetCannotInheritHeldKeysOrOldMask(){
        var input=new CabinetImmersiveInput();input.activate(true,k->false);input.key(80,1);input.reset();
        assertFalse(input.armed());assertEquals(0,input.mask());assertFalse(input.key(80,1));
        input.activate(true,k->k==80);assertFalse(input.armed());assertFalse(input.key(80,2));
        input.activate(true,k->false);assertTrue(input.armed());assertTrue(input.key(80,1));assertEquals(2048,input.mask());
    }
    @Test void everyGameKeyIndividuallyBlocksRecoveryButNoHiddenKeyDoes(){
        for(int held:new int[]{74,85,259,257,265,264,263,262,75,73,79,80}){
            var input=new CabinetImmersiveInput();input.activate(true,k->k==held);assertFalse(input.armed());
            assertFalse(input.key(held,1));input.activate(true,k->false);assertTrue(input.armed());assertTrue(input.key(held,1));
        }
    }
    @Test void physicalPollNeverInventsPressAfterRecovery(){
        var input=new CabinetImmersiveInput();input.activate(true,k->false);assertEquals(0,input.mask());
        input.activate(true,k->k==74);assertEquals(0,input.mask());assertFalse(input.key(74,2));
        assertTrue(input.key(74,1));assertEquals(1,input.mask());
    }
    @Test void oldActivationApiKeepsImmediateEdgeContract(){
        var input=new CabinetImmersiveInput();input.activate(true);assertTrue(input.armed());assertTrue(input.key(74,1));
        assertTrue(input.activate(false));input.activate(true);assertTrue(input.key(75,1));assertEquals(256,input.mask());
    }
}
