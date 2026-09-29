package cn.piq.sfchome.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcInputSendPolicyTest {
    @Test void renderingCannotManufactureHeartbeatTicks() {
        for(int tick=0;tick<2;tick++)for(int render=0;render<10000;render++)
            assertFalse(SfcInputSendPolicy.shouldSend(false,1,1,tick));
        assertTrue(SfcInputSendPolicy.shouldSend(false,1,1,2));
    }
    @Test void fastPressAndReleaseAreNotDelayedUntilHeartbeat() {
        assertTrue(SfcInputSendPolicy.shouldSend(false,1,0,0));
        assertTrue(SfcInputSendPolicy.shouldSend(false,0,1,0));
        assertTrue(SfcInputSendPolicy.shouldSend(false,1,0,0));
        assertTrue(SfcInputSendPolicy.shouldSend(false,0,1,0));
        assertFalse(SfcInputSendPolicy.shouldSend(false,0,0,0));
    }
    @Test void forceReleaseAndInitialRefreshAlwaysSend() {
        assertTrue(SfcInputSendPolicy.shouldSend(true,0,0,0));
        assertTrue(SfcInputSendPolicy.shouldSend(false,0,-1,0));
        assertTrue(SfcInputSendPolicy.shouldSend(false,4095,-1,0));
    }
    @Test void idleHeartbeatIsTwoTicksRegardlessOfRenderCount() {
        int elapsed=0,sent=0;
        for(int tick=0;tick<1000;tick++) {
            elapsed++;
            if(SfcInputSendPolicy.shouldSend(false,0,0,elapsed)){sent++;elapsed=0;}
            for(int render=0;render<100;render++)assertFalse(SfcInputSendPolicy.shouldSend(false,0,0,elapsed));
        }
        assertEquals(500,sent);
    }
    @Test void longMainThreadPauseRequiresOneNeutralRecoverySample(){
        assertFalse(SfcInputSendPolicy.resumingAfterStall(0,2_000_000_000L));
        assertFalse(SfcInputSendPolicy.resumingAfterStall(1,500_000_000L));
        assertTrue(SfcInputSendPolicy.resumingAfterStall(1,500_000_001L));
        assertTrue(SfcInputSendPolicy.resumingAfterStall(1,3_000_000_001L));
        assertFalse(SfcInputSendPolicy.resumingAfterStall(3_000_000_001L,3_001_000_001L));
    }
    @Test void wiringUsesExistingLeaseBoundRouteAndTickOnlyCounter() throws Exception {
        String source=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcHomeClient.java"));
        String input=source.substring(source.indexOf("private static void sendInput"),source.indexOf("@SubscribeEvent public static void tick"));
        assertTrue(input.indexOf("KeyboardInput.poll(INPUT_OWNER,active?SfcHomeKeys.poll():0,active)")<input.indexOf("GamepadInput.mix(playback,GamepadInput.ProfileKind.SFC,mask,true)"));
        assertTrue(input.contains("active=active&&sample.enabled()&&sample.armed()"));
        assertTrue(input.contains("if(force||!active){mask=0;INPUT_FOCUS.suspend();KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);}"));
        assertTrue(input.contains("sequence++,mask,force"));
        assertTrue(input.indexOf("SfcInputSendPolicy.resumingAfterStall(lastInputSample,sampledAt)")<input.indexOf("boolean active=acceptsInput()"));
        assertTrue(input.contains("requestedRelease|=SfcInputSendPolicy.resumingAfterStall"));
        assertTrue(input.contains("new cn.piq.sfchome.net.SfcJoinNetwork.ControllerInput(controlLease,input)"));assertTrue(input.contains("controlLease==null||controlPort<0"));assertFalse(input.contains("ControllerInput(playback.session.controllerLease()"));
        assertFalse(input.contains("keepalive++"));
        assertTrue(source.contains("if(playback!=null){keepalive++;sendInput(false);}"));
        String release=source.substring(source.indexOf("private static void releaseControl()"),source.indexOf("@Override public void editor("));assertTrue(release.contains("GamepadInput.release(playback)"));assertTrue(release.contains("lastInputSample=0"));assertFalse(release.contains("playback.close()"));
        String close=source.substring(source.indexOf("private static void closeLocal()"),source.indexOf("private static void showStartup()"));assertTrue(close.indexOf("releaseControl()")<close.indexOf("playback.close()"));
        String render=source.substring(source.indexOf("@SubscribeEvent public static void render"));
        assertTrue(render.contains("sendInput(false)"));assertFalse(render.contains("PacketDistributor"));
        assertFalse(render.contains("keepalive++"));
    }
}
