package cn.piq.fcarcade.home;

import cn.piq.fcarcade.session.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeGunKeyboardFallbackTest {
    private final UUID host=UUID.randomUUID(),guest=UUID.randomUUID(),gun=UUID.randomUUID(),pad=UUID.randomUUID();
    private final Object connection=new Object(),other=new Object();
    private HomeRuntimeAuthority<Object> runtime(){var r=new HomeRuntimeAuthority<Object>(host,connection,true);assertTrue(r.takeGun(host,connection,gun));return r;}
    @Test void emptyP1AllowsGunButtonsWithoutTakingPhysicalP1(){var r=runtime();assertEquals(0,r.buttonPort(host,connection,gun));assertNull(r.port(0));assertEquals(1,r.player(host).port());assertTrue(r.running());}
    @Test void otherPlayersP1AlwaysWins(){var r=runtime();assertTrue(r.take(guest,other,pad,0));assertEquals(-1,r.buttonPort(host,connection,gun));assertEquals(0,r.buttonPort(guest,other,pad));}
    @Test void samePlayersFormalP1LeaseMustBeUsed(){var r=runtime();r.take(host,connection,pad,0);assertEquals(-1,r.buttonPort(host,connection,gun));assertEquals(0,r.buttonPort(host,connection,pad));assertTrue(r.recordButtons(host,connection,pad,gun));}
    @Test void wrongPlayerConnectionOrLeaseCannotRoute(){var r=runtime();assertEquals(-1,r.buttonPort(guest,connection,gun));assertEquals(-1,r.buttonPort(host,other,gun));assertEquals(-1,r.buttonPort(host,connection,UUID.randomUUID()));}
    @Test void gunFallbackRequiresGunAsSource(){var r=runtime();assertFalse(r.recordButtons(host,connection,gun,null));assertFalse(r.recordButtons(host,connection,gun,pad));assertTrue(r.recordButtons(host,connection,gun,gun));}
    @Test void returningGunClearsOnlyItsLastInjectedButtons(){var r=runtime();r.recordButtons(host,connection,gun,gun);assertTrue(r.clearGunButtons(gun));assertFalse(r.clearGunButtons(gun));assertTrue(r.running());}
    @Test void otherP1TakingOverCannotBeClearedByOldGunSource(){var r=runtime();r.recordButtons(host,connection,gun,gun);r.take(guest,other,pad,0);assertFalse(r.clearGunButtons(gun));assertTrue(r.authorized(guest,other,pad,0));}
    @Test void sameP1UsingRealControllerSupersedesEarlierGunAssistance(){var r=runtime();r.take(host,connection,pad,0);assertTrue(r.recordButtons(host,connection,pad,gun));assertTrue(r.recordButtons(host,connection,pad,null));assertFalse(r.clearGunButtons(gun));}
    @Test void wrongGunCannotClearActiveSource(){var r=runtime();r.recordButtons(host,connection,gun,gun);assertFalse(r.clearGunButtons(UUID.randomUUID()));assertTrue(r.clearGunButtons(gun));}
    @Test void oldGunLeaseAfterReplacementCannotRecordOrClearNewGun(){var r=runtime();r.release(host,connection,gun,1);var next=UUID.randomUUID();r.takeGun(host,connection,next);assertTrue(r.recordButtons(host,connection,next,next));assertFalse(r.recordButtons(host,connection,gun,gun));assertFalse(r.clearGunButtons(gun));assertTrue(r.clearGunButtons(next));}
    @Test void resetPreservesLoansButDropsButtonSource(){var r=runtime();r.recordButtons(host,connection,gun,gun);r.reset();assertEquals(0,r.buttonPort(host,connection,gun));assertFalse(r.clearGunButtons(gun));r.close();assertEquals(-1,r.buttonPort(host,connection,gun));}
    @Test void ordinaryTwoControllerRoutingUnchanged(){var r=new HomeRuntimeAuthority<Object>(host,connection);r.take(host,connection,pad,0);r.take(guest,other,gun,1);assertEquals(0,r.buttonPort(host,connection,pad));assertEquals(1,r.buttonPort(guest,other,gun));}
    @Test void realFifoKeepsGunButtonTapAndAimingSequenceIndependent(){var r=runtime();var clock=new LockstepState();clock.restart();assertTrue(clock.acceptInput(gun,1,r.buttonPort(host,connection,gun),10,8));assertTrue(clock.acceptInput(gun,1,0,11,0));assertTrue(clock.acceptZapper(gun,1,1,ZapperInput.pack(100,100,false,true),false,false));r.recordButtons(host,connection,gun,gun);var first=clock.advanceFrame();assertEquals(8,first.playerOneMask());assertTrue(ZapperInput.trigger(first.zapperState()));assertEquals(0,clock.advanceFrame().playerOneMask());assertFalse(clock.acceptInput(gun,1,0,10,255));assertFalse(clock.acceptInput(gun,0,0,12,255));}
    @Test void gunReturnDoesNotTouchOtherP1Fifo(){var r=runtime();r.recordButtons(host,connection,gun,gun);r.take(guest,other,pad,0);var clock=new LockstepState();clock.restart();clock.acceptInput(pad,1,0,0,128);clock.acceptInput(pad,1,0,1,0);r.recordButtons(guest,other,pad,null);r.release(host,connection,gun,1);if(r.clearGunButtons(gun))clock.clearController(0);assertEquals(128,clock.advanceFrame().playerOneMask());assertEquals(0,clock.advanceFrame().playerOneMask());assertTrue(r.running());}
}
