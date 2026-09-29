package cn.piq.sfchome.client;

import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.client.KeyboardControlState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure shared-gate behavior plus explicit SFC wiring contracts; no Minecraft instance. */
class SfcUnifiedKeyboardTest {
    private static int[][] keys(KeyboardConfig.Preset preset) {
        return KeyboardConfig.presetKeys(KeyboardConfig.Profile.SFC,preset).stream()
                .map(code->new int[]{code}).toArray(int[][]::new);
    }
    private static String source(String name)throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/"+name+".java"));
    }

    @Test void everyPresetRetainsTheTwelveNativePortBits() {
        for(var preset:KeyboardConfig.Preset.values()) {
            int[][] keys=keys(preset);
            var state=new KeyboardControlState(preset,keys);
            if(preset==KeyboardConfig.Preset.WASD||preset==KeyboardConfig.Preset.CLASSIC)state.toggle();
            state.activate(true,key->false);
            for(int bit=0;bit<12;bit++) {
                assertTrue(state.key(keys[bit][0],1));
                assertEquals(1<<bit,state.mask(0),preset+" bit "+bit);
                assertTrue(state.key(keys[bit][0],0));
                assertEquals(0,state.mask(0));
            }
        }
    }

    @Test void wasdAutomaticallyCapturesFunctionsButMovementConflictsNeedPositionLock() {
        var state=new KeyboardControlState(KeyboardConfig.Preset.WASD,keys(KeyboardConfig.Preset.WASD));
        state.activate(true,key->key==87,key->key==87);
        assertTrue(state.enabled());assertTrue(state.armed());
        assertFalse(state.key(87,1,true));assertEquals(0,state.mask(4095,key->key==87));
        int function=keys(KeyboardConfig.Preset.WASD)[0][0];assertTrue(state.key(function,1));assertEquals(1,state.mask(0,key->key==87));
        state.toggle();state.activate(true,key->key==87,key->key==87);
        assertTrue(state.enabled());assertFalse(state.armed());
        assertEquals(0,state.mask(4095));
        state.activate(true,key->false);assertTrue(state.armed());
        assertTrue(state.key(87,1));assertEquals(1<<4,state.mask(0));
    }

    @Test void releasingOnePlayerModeLeavesAnotherPlayersStateUntouched() {
        var one=new KeyboardControlState(KeyboardConfig.Preset.NUMPAD,keys(KeyboardConfig.Preset.NUMPAD));
        var two=new KeyboardControlState(KeyboardConfig.Preset.NUMPAD,keys(KeyboardConfig.Preset.NUMPAD));
        one.activate(true,key->false);two.activate(true,key->false);
        one.key(321,1);two.key(322,1);
        one.toggle();one.toggle();
        assertEquals(KeyboardControlState.Mode.PARALLEL,one.mode());
        assertEquals(0,one.mask(4095));assertEquals(1<<8,two.mask(0));
        assertTrue(two.armed());
    }

    @Test void guiOrPutAwayRequiresAllKeysNeutralBeforeNewEdges() {
        int[][] keys=keys(KeyboardConfig.Preset.NUMPAD);
        var state=new KeyboardControlState(KeyboardConfig.Preset.NUMPAD,keys);
        state.activate(true,key->false);state.key(321,1);assertEquals(1,state.mask(0));
        state.pause();var focus=new SfcInputFocus();focus.suspend();
        for(int held:Set.of(321,322,265)) {
            state.activate(true,key->key==held);
            assertFalse(state.armed());assertEquals(0,focus.sample(state.mask(4095)));
        }
        state.activate(true,key->false);assertTrue(state.armed());
        assertEquals(0,focus.sample(state.mask(0)));
        state.key(322,1);assertEquals(256,focus.sample(state.mask(0)));
    }

    @Test void sharedEdgeCaptureDoesNotWaitForATickOrHeartbeat() {
        var state=new KeyboardControlState(KeyboardConfig.Preset.NUMPAD,keys(KeyboardConfig.Preset.NUMPAD));
        var focus=new SfcInputFocus();state.activate(true,key->false);
        for(int n=0;n<1000;n++) {
            state.key(321,1);assertEquals(1,focus.sample(state.mask(0)));
            assertTrue(SfcInputSendPolicy.shouldSend(false,1,0,0));
            state.key(321,0);assertEquals(0,focus.sample(state.mask(0)));
            assertTrue(SfcInputSendPolicy.shouldSend(false,0,1,0));
        }
    }

    @Test void attachUsesRealOwnerAndLiveControllerAuthorityNotGuiLifetime()throws Exception {
        String s=source("SfcHomeClient");
        assertTrue(s.contains("KeyboardInput.attach(INPUT_OWNER,KeyboardConfig.Profile.SFC"));
        assertTrue(s.contains("()->KeyboardInput.keys(SfcHomeKeys.KEYS),SfcHomeClient::presentController,SfcHomeClient::ownsController,()->sendInput(true),()->sendInput(false)"));
        String authority=s.substring(s.indexOf("private static boolean presentController()"),s.indexOf("static boolean acceptsInput()"));
        for(String part:new String[]{"isCurrent(playback)","mc.getConnection().getConnection().isConnected()","playback.started()","InputOwnership.owns(INPUT_OWNER)","localController(playback.session,true)"})assertTrue(authority.contains(part),part);
        assertFalse(authority.contains("mc.screen"));assertFalse(authority.contains("isWindowActive"));
        assertTrue(s.contains("ownsController()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()"));
    }

    @Test void physicalCaptureProviderNeverMintsRuntimeInputOrStartsCore()throws Exception {
        String s=source("SfcHomeClient");String provider=s.substring(s.indexOf("ControllerCapture.register(SYSTEM"),s.indexOf("@SubscribeEvent public static void keys("));
        assertTrue(provider.contains("SfcControllerData.isController(stack)"));assertTrue(provider.contains("controllerVisualPlayer(port)"));assertTrue(provider.contains("controllerVisualLease(port)"));assertTrue(provider.contains("ControllerCapturePolicy.receipt"));
        for(String denied:new String[]{"new SfcPlayback","CabinetClientOwner.acquire","ControllerInput(","controlLease="})assertFalse(provider.contains(denied),denied);
    }
    @Test void swappingHeldHandNeutralizesBothSourcesBeforeReattaching()throws Exception {
        String s=source("SfcHomeClient");String attach=s.substring(s.indexOf("private static boolean attachControls()"),s.indexOf("private static net.minecraft.world.item.ItemStack localController("));
        assertTrue(attach.contains("controlLease,controlPort,p.getMainHandItem()==held?0:1,false"));
        assertTrue(attach.indexOf("KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);sendInput(true)")<attach.indexOf("return KeyboardInput.attach"));
        assertTrue(s.contains("ControllerCapture.unique(p,found,controlLease"));
    }

    @Test void unlockedOrUnarmedCannotMixPadAndReleaseUsesExistingLeaseRoute()throws Exception {
        String s=source("SfcHomeClient");
        String send=s.substring(s.indexOf("private static void sendInput("),s.indexOf("@SubscribeEvent public static void tick"));
        assertTrue(send.contains("active=active&&sample.enabled()&&sample.armed()"));
        assertTrue(send.contains("if(force||!active){mask=0;INPUT_FOCUS.suspend();KeyboardInput.pause(INPUT_OWNER);GamepadInput.pause(playback);}"));
        assertTrue(send.contains("else {mask=GamepadInput.mix(playback,GamepadInput.ProfileKind.SFC,mask,true)"));
        assertTrue(send.contains("sequence++,mask,force"));
        assertTrue(send.contains("new cn.piq.sfchome.net.SfcJoinNetwork.ControllerInput(controlLease,input)"));assertFalse(send.contains("ControllerInput(playback.session.controllerLease()"));
        assertFalse(send.contains("leave("));assertFalse(send.contains("closeLocal("));
        String release=s.substring(s.indexOf("private static void releaseControl()"),s.indexOf("@Override public void editor("));
        assertTrue(release.indexOf("KeyboardInput.release(INPUT_OWNER)")<release.indexOf("CabinetClientOwner.release(INPUT_OWNER)"));
        assertTrue(release.contains("GamepadInput.release(playback)"));assertFalse(release.contains("playback.close()"));
        String close=s.substring(s.indexOf("private static void closeLocal()"),s.indexOf("private static void showStartup()"));assertTrue(close.indexOf("releaseControl()")<close.indexOf("playback.close()"));
    }

    @Test void regularGameplayNeverMutatesVanillaBindingsButOldJournalCanRecover()throws Exception {
        String s=source("SfcHomeKeys"),normal=s.substring(0,s.indexOf("private static void recover()"));
        for(String forbidden:new String[]{"SAVED","isolated","InputConstants.UNKNOWN","Files.create", "Files.move", "Files.newOutputStream", "options.save()", ".setKey("})assertFalse(normal.contains(forbidden),forbidden);
        assertTrue(s.contains("piq-sfc-home-key-recovery.properties"));
        String recovery=s.substring(s.indexOf("private static void recover()"));
        assertTrue(recovery.contains("Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)"));
        assertTrue(recovery.contains("original!=null&&k.isUnbound()"));
        assertTrue(recovery.contains("options.save();Files.deleteIfExists(p)"));
        assertTrue(recovery.contains("Keep recovery journal for next launch"));
    }

    @Test void mouseReturnAndWorldInteractionAreNotCancelledBySfc()throws Exception {
        String s=source("SfcHomeClient");
        assertTrue(s.contains("mouse(InputEvent.MouseButton.Pre event){if(playback!=null)sendInput(false);}"));
        assertFalse(s.contains("event.setCanceled(true)"));assertFalse(s.contains("event.setSwingHand(false)"));
        assertTrue(s.contains("screenOpening(ScreenEvent.Opening event)"));
        assertTrue(s.contains("INPUT_FOCUS.suspend();sendInput(true)"));
    }
}
