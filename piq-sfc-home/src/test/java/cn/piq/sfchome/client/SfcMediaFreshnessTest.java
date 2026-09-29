package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcMediaFreshnessTest {
    @Test void firstFrameHasIndependentSixtySecondGrace(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);
        assertEquals(SfcMediaFreshness.Phase.WAITING,health.status().phase());assertTrue(health.status().paused());
        clock.set(SfcMediaFreshness.STARTUP_NANOS-1);assertEquals(SfcMediaFreshness.Phase.WAITING,health.status().phase());
        assertTrue(health.decodedVideo());assertEquals(SfcMediaFreshness.Phase.LIVE,health.status().phase());
    }
    @Test void missingFirstFrameExpiresEvenIfCallerKeepsPolling(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);
        for(int seconds=0;seconds<60;seconds++){clock.set(seconds*1_000_000_000L);assertEquals(SfcMediaFreshness.Phase.WAITING,health.status().phase());}
        clock.set(SfcMediaFreshness.STARTUP_NANOS);assertEquals(SfcMediaFreshness.Phase.TIMED_OUT,health.status().phase());assertFalse(health.decodedVideo());
    }
    @Test void decodedPictureStallsAtThreeAndExpiresAtFifteenSeconds(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);assertTrue(health.decodedVideo());
        clock.set(SfcMediaFreshness.STALL_NANOS-1);assertFalse(health.status().paused());
        clock.incrementAndGet();assertEquals(SfcMediaFreshness.Phase.STALLED,health.status().phase());assertEquals(1,health.status().interruption());
        clock.set(SfcMediaFreshness.TIMEOUT_NANOS);assertEquals(SfcMediaFreshness.Phase.TIMED_OUT,health.status().phase());assertEquals(1,health.status().interruption());
    }
    @Test void lateAudioOrFragmentPollingNeverRefreshesVideo(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);health.decodedVideo();
        for(int step=1;step<=300;step++){clock.set(step*50_000_000L);health.status();}
        assertEquals(SfcMediaFreshness.Phase.TIMED_OUT,health.status().phase());
    }
    @Test void recoveryLatchesInterruptionEvenBetweenClientPolls(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);health.decodedVideo();long before=health.status().interruption();
        clock.set(4_000_000_000L);assertTrue(health.decodedVideo());
        assertFalse(health.status().paused());assertEquals(before+1,health.status().interruption());
        // The caller must force-release on that revision, even though video recovered already.
        var focus=new SfcInputFocus();assertEquals(1,focus.sample(1));focus.suspend();assertEquals(0,focus.sample(1));assertEquals(0,focus.sample(0));assertEquals(1,focus.sample(1));
    }
    @Test void repeatedStallSamplesDoNotRepeatInterruption(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);health.decodedVideo();clock.set(4_000_000_000L);
        for(int i=0;i<100;i++)assertEquals(1,health.status().interruption());
        health.decodedVideo();clock.set(8_000_000_000L);assertEquals(2,health.status().interruption());
    }
    @Test void timedOutSourceCannotBeRevivedByLatePicture(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);health.decodedVideo();clock.set(SfcMediaFreshness.TIMEOUT_NANOS);
        assertFalse(health.decodedVideo());assertTrue(health.status().paused());
        health.reset();long revision=health.status().interruption();assertEquals(SfcMediaFreshness.Phase.WAITING,health.status().phase());assertTrue(health.decodedVideo());assertEquals(revision,health.status().interruption());
    }
    @Test void resetRequiresFreshVideoAndKeepsItsOwnLoadingDeadline(){
        var clock=new AtomicLong();var health=new SfcMediaFreshness(clock::get);health.decodedVideo();clock.set(2_000_000_000L);health.reset();
        assertTrue(health.status().paused());assertEquals(1,health.status().interruption());clock.set(61_000_000_000L);assertEquals(SfcMediaFreshness.Phase.WAITING,health.status().phase());
        clock.set(62_000_000_000L);assertEquals(SfcMediaFreshness.Phase.TIMED_OUT,health.status().phase());
    }
    @Test void receiverChecksVideoOnBothSidesOfPollAndAfterSuccessfulDecode()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcPlayback.java"));
        String body=source.substring(source.indexOf("private void runHosted()"),source.indexOf("void resetMedia()"));
        assertTrue(body.indexOf("checkMediaFreshness();")<body.indexOf("mediaInput.poll"));
        assertTrue(body.indexOf("checkMediaFreshness();",body.indexOf("mediaInput.poll"))>=0);
        assertTrue(body.indexOf("mediaFreshness.decodedVideo()")>body.indexOf("CabinetMediaCodec.decodeVideo"));
        assertTrue(body.indexOf("mediaFreshness.decodedVideo()")>body.indexOf("SFC home rotation must be zero"));
        assertFalse(body.contains("lastMedia"));
    }
    @Test void inputUsesPauseAndLatchedRevisionWithoutRemovingLeaseChecks()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        assertTrue(source.contains("playback.started()&&!playback.mediaInputPaused()&&!SfcRepairClient.suspended(playback)"));
        assertTrue(source.contains("long mediaRevision=playback.mediaInterruption();"));
        assertTrue(source.contains("mediaRevision!=lastMediaInterruption"));
        assertTrue(source.contains("requestedRelease=true;"));
        assertTrue(source.contains("if(force||!active){mask=0;INPUT_FOCUS.suspend();KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);}"));
    }
    @Test void audioFailureIsSurfacedWithoutChangingCoreOrMediaError()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcPlayback.java"));
        assertTrue(source.contains("player.failureMessage()"));
        String body=source.substring(source.indexOf("private void observeAudioFailure("),source.indexOf("void resetMedia()"));
        assertTrue(body.contains("audioNotice.compareAndSet(null"));assertTrue(body.contains("audioWarningShown=true"));
        assertFalse(body.contains("running=false"));assertFalse(body.contains("error="));assertFalse(body.contains("throw "));
    }
    @Test void mediaRecoveryAndAudioWarningsKeepTheirReadableNonBlockingText()throws Exception{
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        assertTrue(source.contains("if(mediaNotice!=null)cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(mediaNotice);"));
        assertTrue(source.contains("if(audioNotice!=null)cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(audioNotice);"));
        assertFalse(source.contains("if(mediaNotice!=null)toast(mediaNotice)"));
        assertFalse(source.contains("if(audioNotice!=null)toast(audioNotice)"));
    }
}
