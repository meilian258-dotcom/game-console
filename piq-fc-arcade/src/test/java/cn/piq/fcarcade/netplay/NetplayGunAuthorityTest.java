package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.home.HomeRuntimeAuthority;
import cn.piq.fcarcade.session.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production physical-lease -> FIFO -> mailbox boundary without a game world. */
class NetplayGunAuthorityTest {
    final UUID host=UUID.randomUUID(),guest=UUID.randomUUID(),pad=UUID.randomUUID(),gun=UUID.randomUUID();
    final Object hostConnection=new Object(),guestConnection=new Object();
    final HomeRuntimeAuthority<Object> authority=new HomeRuntimeAuthority<>(host,hostConnection,true);
    final LockstepState clock=new LockstepState();
    final NetplayGunMailbox mailbox=new NetplayGunMailbox();
    NetplayGunAuthorityTest(){clock.restart();}
    @Test void settingsNoLongerExcludeGunSessions(){var hint=cn.piq.fcarcade.home.HomeSyncSaveHints.modeSaved("FC",3);assertTrue(hint.contains("手柄或光枪"));assertTrue(hint.contains("按卡带设置保存"));assertFalse(hint.contains("仅普通"));}
    NetplayGunMailbox.Sample transfer(){var f=clock.advanceFrame();mailbox.offer(clock.releaseRevision(),f.targetFrame(),f.playerOneMask(),f.zapperState(),1);return mailbox.next(2);}
    @Test void hostCanOwnBothPhysicalSocketsWithoutGivingNativeControlToPeers(){assertTrue(authority.take(host,hostConnection,pad,0));assertTrue(authority.takeGun(host,hostConnection,gun));assertEquals(2,authority.controls(host).size());assertEquals(0,authority.player(host).port());assertEquals(-1,authority.buttonPort(host,hostConnection,gun));assertEquals(0,authority.buttonPort(host,hostConnection,pad));}
    @Test void guestGunAndHostP1HaveIndependentLeasesAndRelease(){assertTrue(authority.take(host,hostConnection,pad,0));assertTrue(authority.takeGun(guest,guestConnection,gun));assertEquals(-1,authority.buttonPort(guest,guestConnection,gun));clock.acceptInput(pad,1,0,1,8);clock.acceptZapper(gun,1,1,ZapperInput.pack(100,100,false,true),false,false);var first=transfer();assertEquals(8,first.buttons());assertTrue(ZapperInput.trigger(first.aim()));authority.release(guest,guestConnection,gun,1);clock.clearZapper();var next=transfer();assertEquals(8,next.buttons());assertEquals(ZapperInput.NEUTRAL,next.aim());assertTrue(authority.authorized(host,hostConnection,pad,0));}
    @Test void hostGunAloneCanStartGameWithoutFabricatingPhysicalP1(){authority.takeGun(host,hostConnection,gun);assertNull(authority.port(0));int port=authority.buttonPort(host,hostConnection,gun);assertTrue(clock.acceptInput(gun,1,port,1,8));assertTrue(authority.recordButtons(host,hostConnection,gun,gun));assertEquals(8,transfer().buttons());authority.release(host,hostConnection,gun,1);if(authority.clearGunButtons(gun))clock.clearController(0);clock.clearZapper();assertEquals(NetplayGunMailbox.NEUTRAL,transfer());}
    @Test void revokedGunCannotAuthenticateWithReplacementLeaseOrConnection(){authority.takeGun(guest,guestConnection,gun);authority.release(guest,guestConnection,gun,1);UUID replacement=UUID.randomUUID();authority.takeGun(host,hostConnection,replacement);assertFalse(authority.authorized(guest,guestConnection,gun,1));assertFalse(authority.authorized(host,new Object(),replacement,1));assertTrue(authority.authorized(host,hostConnection,replacement,1));}
    @Test void forceReleaseBarrierDiscardsBufferedPressButNotNewerState(){clock.acceptZapper(gun,1,1,ZapperInput.pack(100,100,false,true),false,false);var old=clock.advanceFrame();mailbox.offer(clock.releaseRevision(),old.targetFrame(),0,old.zapperState(),0);long revision=clock.releaseRevision();assertTrue(clock.acceptZapper(gun,1,2,ZapperInput.NEUTRAL,true,false));assertEquals(NetplayGunMailbox.NEUTRAL,transfer());assertFalse(mailbox.offer(revision,999,255,old.zapperState(),3));assertEquals(NetplayGunMailbox.NEUTRAL,mailbox.next(4));}
}
