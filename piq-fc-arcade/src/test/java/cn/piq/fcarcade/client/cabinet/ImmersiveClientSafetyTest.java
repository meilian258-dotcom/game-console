package cn.piq.fcarcade.client.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual host source contracts plus the real shared ownership primitive; no mocked game events. */
class ImmersiveClientSafetyTest {
    private static String fc() throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetClientBackends.java"));
    }
    private static String nativeClient() throws Exception {
        return Files.readString(Path.of("../piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java"));
    }
    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
    private static String body(String source, String signature) {
        int start=source.indexOf(signature);
        assertTrue(start>=0,"Missing production method: "+signature);
        start=source.indexOf('{',start);
        int depth=0; char quote=0; boolean escape=false;
        for(int i=start;i<source.length();i++) {
            char c=source.charAt(i);
            if(quote!=0) {
                if(escape)escape=false;
                else if(c=='\\')escape=true;
                else if(c==quote)quote=0;
                continue;
            }
            if(c=='\"'||c=='\''){quote=c;continue;}
            if(c=='{')depth++;
            else if(c=='}'&&--depth==0)return compact(source.substring(start+1,i));
        }
        throw new AssertionError("Unterminated production method: "+signature);
    }

    @Test void bothStartPathsEnterWorldWithoutConstructingAPlayScreen() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String start=body(s,"private static void startGame(Path chosen,boolean remember)");
            assertTrue(start.contains("playing=true;announced=false;INPUT.reset();mc.setScreen(null);"));
            assertFalse(s.matches("(?s).*new\\s+(?:Cabinet|NativeArcade)PlayScreen\\s*\\(.*"));
            assertFalse(start.contains("instanceofCabinetPlayScreen"));
            assertFalse(start.contains("instanceofNativeArcadePlayScreen"));
        }
    }
    @Test void eventInputRequiresRunningCurrentFocusedUnpausedWorldWithNoGui() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String gate=body(s,"private static void syncInput()");
            String prefix=s.equals(fc())?"booleanactive=controlEnabled&&":"booleanactive=";
            assertTrue(gate.contains(prefix+"playing&&current()&&running()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();"));
            assertTrue(gate.contains("KeyboardInput.attach(INPUT_OWNER,"));
            assertTrue(gate.contains("()->playing&&current()&&running()&&cn.piq.retro.input.InputOwnership.owns(INPUT_OWNER)&&mc.getConnection()!=null&&mc.getConnection().getConnection().isConnected()"));
            assertTrue(gate.contains("KeyboardInput.poll(INPUT_OWNER,0,active)"));
            assertTrue(gate.contains("active=active&&keyboard.enabled()&&keyboard.armed();"),"Physical pads cannot bypass shared mode/neutral gates");
            assertTrue(gate.contains("keyboard.mask(),active)"));
            assertEquals("INPUT.reset();GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;clearInput();",body(s,"private static void releaseKeyboard()"));
            assertTrue(gate.contains("if(active&&mask!=mixedInputMask){mixedInputMask=mask;input(mask,0);}"));
            assertTrue(gate.contains("if(!active){GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;}"));
        }
    }
    @Test void keyboardEventsSendOnlyP1AndLeaveEscapeToMinecraft() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String key=body(s,"public static void key(InputEvent.Key event)");
            assertTrue(key.startsWith("if(!playing)return;syncInput();"));
            assertFalse(key.contains("GLFW_KEY_ESCAPE"));
            assertFalse(key.contains("stop("));
            assertFalse(key.contains("INPUT.key("),"Shared pre-vanilla callback owns edges; post event must not replay them");
            assertEquals("if(!playing)return;syncInput();",key);
            assertTrue(body(s,"private static void syncInput()").contains("input(mask,0)"));
            assertFalse(key.contains("setCanceled"),"InputEvent.Key is not cancellable");
        }
    }
    @Test void openingGuiClearsImmediatelyWithoutCancellingVanillaPause() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String opening=body(s,"public static void screenOpening(ScreenEvent.Opening event)");
            assertEquals("if(!playing||event.getNewScreen()==null)return;"+(s.contains("PgmStartSequence")?"PGM_START.cancel();":"")+"cn.piq.retro.client.KeyboardInput.pause(INPUT_OWNER);releaseKeyboard();",opening);
            assertEquals("INPUT.reset();GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;clearInput();",body(s,"private static void releaseKeyboard()"));
            assertFalse(opening.contains("stop("));
            assertFalse(opening.contains("setCanceled"));
        }
    }
    @Test void rightClickExitNeedsValidatedExactCabinetAndOwner() throws Exception {
        String server=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/cabinet/ServerCabinets.java"));
        String use=body(server,"public static boolean interact(");
        int validation=use.indexOf("if(!valid(player,binding,true))returntrue;");
        int toggle=use.indexOf("if(!menu&&active!=null&&active.owner().equals(player.getUUID()))");
        assertTrue(validation>=0&&toggle>validation);
        assertTrue(use.substring(toggle).startsWith("if(!menu&&active!=null&&active.owner().equals(player.getUUID())){close("));
        String nativeOpen=body(nativeClient(),"private static void openAt(BlockPos pos,UUID id,boolean configure)");
        assertEquals("openAt(pos,id,false);",body(nativeClient(),"public static void openAt(BlockPos pos,UUID id)"));
        assertTrue(nativeOpen.indexOf("if(!matches(pos,id))")<nativeOpen.indexOf("if(playing)"));
        assertTrue(nativeOpen.contains("pos.equals(anchor)&&id.equals(identity)&&mc.level.dimension().location().equals(dimension)"));
        assertTrue(nativeOpen.contains("CabinetUseGuard.suppressWhileHeld();stop("));
    }
    @Test void focusSyncRunsInTickAndVisualPathsAsWellAsKeyCallback() throws Exception {
        assertTrue(body(fc(),"public static void tick(ClientTickEvent.Post event)").contains("syncInput();"));
        assertTrue(body(fc(),"public static void render(RenderLevelStageEvent event)").contains("syncInput();"));
        assertTrue(body(nativeClient(),"public static void tick(ClientTickEvent.Post event)").contains("syncInput();"));
        assertTrue(body(nativeClient(),"private static void upload()").startsWith("syncInput();"));
    }
    @Test void backgroundModeDoesNotRebindMovementOrGrabMouse() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            for(String forbidden:new String[]{"KeyMapping.set", "syncOtherMappings", "mouseHandler.grabMouse", "mouseHandler.releaseMouse", "GLFW.glfwSetInputMode", "setKey("})
                assertFalse(s.contains(forbidden),"Unexpected input mutation: "+forbidden);
        }
    }
    @Test void staleFactoryCompletionsAreCheckedBeforeAndAfterClientHandoff() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String start=body(s,"private static void startGame(Path chosen,boolean remember)");
            assertTrue(start.contains("inttoken=++generation"));
            assertTrue(start.contains("if(shuttingDown||token!=generation)"));
            assertTrue(start.contains("PENDING.set(ready)"));
            assertTrue(start.contains("PENDING.compareAndSet(ready,null)"));
            assertTrue(start.contains("if(token!=generation||!current()||!playing)"));
            assertTrue(start.contains("catch(RejectedExecutionExceptionfailure)"));
        }
    }
    @Test void stoppingInvalidatesGenerationClearsKeysPendingCoreAndExactOwner() throws Exception {
        for(String s:new String[]{fc(),nativeClient()}) {
            String stop=body(s,s.contains("static void stop(String reason,boolean notifyServer)")
                    ? "static void stop(String reason,boolean notifyServer)" : "static void stop(String reason)");
            assertTrue(stop.startsWith("generation++;"));
            assertTrue(stop.contains("playing=false;announced=false;"));
            assertTrue(stop.indexOf("INPUT.reset();")>stop.indexOf("playing=false;"));
            assertTrue(stop.contains("CabinetClientOwner.release(INPUT_OWNER)"));
            assertTrue(stop.contains("GamepadInput.release(INPUT_OWNER)"));
            assertTrue(stop.contains("KeyboardInput.release(INPUT_OWNER)"));
            assertTrue(stop.contains("PENDING.getAndSet(null)"));
        }
    }
    @Test void bothHostsAcquireSharedOwnerBeforeOpeningTheirSetup() throws Exception {
        String generic=body(fc(),"public void openBackend(CabinetNetwork.Launch request)");
        String nativeOpen=body(nativeClient(),"private static void openAt(BlockPos pos,UUID id,boolean configure)");
        for(String s:new String[]{generic,nativeOpen}) {
            int acquire=s.indexOf("CabinetClientOwner.acquire(INPUT_OWNER)");
            assertTrue(acquire>=0);
            assertTrue(acquire<s.indexOf("mc.setScreen(new"));
            assertTrue(s.contains("ClientArcadeEvents.isControlling()"));
        }
        assertTrue(generic.contains("if(error!=null){release(request.lease());if(room!=null&&room.member().equals(request.lease()))stop(null,false);notice(error);return;}"));
        assertTrue(nativeOpen.contains("if(!CabinetClientOwner.acquire(INPUT_OWNER)){toast("));
    }
    @Test void setupAndPlayingLifetimeBothReleaseOnInvalidWorldOrReplacedSetup() throws Exception {
        String generic=body(fc(),"public static void tick(ClientTickEvent.Post event)");
        assertTrue(generic.contains("if(!current()||(!playing&&!(mc.screeninstanceofCabinetSetupScreen))){stop("));
        String legacy=body(nativeClient(),"public static void tick(ClientTickEvent.Post event)");
        assertTrue(legacy.contains("if(anchor!=null&&(!current()||(!playing&&!(mc.screeninstanceofNativeArcadeSetupScreen)))){stop("));
    }
    @Test void currentWorldGuardsRetainDeathSpectatorDistanceAndLocalOnlyRestrictions() throws Exception {
        String generic=body(fc(),"static boolean current()");
        assertTrue(generic.contains("!mc.player.isAlive()||mc.player.isSpectator()"));
        assertTrue(generic.contains("launch.target().matches(mc.level)"));
        assertTrue(generic.contains("meta.localOnly()||localWorld()"));
        assertTrue(generic.contains("distanceToSqr("));
        assertTrue(generic.contains("ClientArcadeEvents.isControlling()"));
        String legacy=body(nativeClient(),"static boolean supported()");
        assertTrue(legacy.contains("!server.isPublished()"));
        assertTrue(legacy.contains("mc.player.isAlive()&&!mc.player.isSpectator()"));
        assertTrue(body(nativeClient(),"static boolean current()").contains("matches(anchor,identity)"));
        assertTrue(body(nativeClient(),"static boolean current()").contains("ClientArcadeEvents.isControlling()"));
        assertTrue(body(nativeClient(),"private static boolean matches(").contains("NativeCabinetStructure.complete"));
    }
    @Test void serverLeaseStillHeartbeatsDuringBackgroundPlayAndReleasesOnStop() throws Exception {
        assertTrue(body(fc(),"public static void tick(ClientTickEvent.Post event)").contains("newCabinetNetwork.Heartbeat(launch.lease())"));
        String stop=body(fc(),"static void stop(String reason,boolean notifyServer)");
        assertTrue(stop.contains("if(notifyServer&&mc.getConnection()==previousConnection)"));
        assertTrue(stop.contains("if(previous!=null)release(previous.lease())"));
        assertTrue(stop.contains("elseif(previousRoom!=null)release(previousRoom.member())"));
        String failure=body(fc(),"private static void stop(String reason,boolean notifyServer,Throwable failure)");
        assertTrue(failure.contains("DeviceNotices.record(\"街机\",reason,failure);stop(null,notifyServer);"));
    }
    @Test void pickerCancelDelegatesToHostStopWhileSetupTypesStayRecognizable() throws Exception {
        String generic=compact(Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/CabinetSetupScreen.java")));
        String legacy=compact(Files.readString(Path.of("../piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeSetupScreen.java")));
        assertTrue(generic.contains("extendscn.piq.fcarcade.client.ui.DeviceScreen"));
        assertTrue(generic.contains("voidonClose(){if(closed)return;if(editing&&current()&&!busy()){backFromEditor();return;}closed=true;revision++;CabinetClientBackends.stop(null,true)"));
        assertTrue(generic.contains("voidbackFromEditor(){editing=false;editName=null;rebuildWidgets();}"));
        assertTrue(legacy.contains("extendsLocalRomPickerScreen"));
        assertTrue(legacy.contains("()->NativeArcadeClient.stop(null)"));
    }
    @Test void ownershipUsesIdentityAndOldHostCannotReleaseNewHost() {
        Object a=new String("same-value"),b=new String("same-value");
        try {
            assertThrows(IllegalArgumentException.class,()->CabinetClientOwner.acquire(null));
            assertTrue(CabinetClientOwner.acquire(a));
            assertTrue(CabinetClientOwner.acquire(a));
            assertFalse(CabinetClientOwner.acquire(b));
            CabinetClientOwner.release(b);
            assertFalse(CabinetClientOwner.acquire(b));
            CabinetClientOwner.release(a);
            assertTrue(CabinetClientOwner.acquire(b));
            CabinetClientOwner.release(a);
            assertFalse(CabinetClientOwner.acquire(a));
        } finally { CabinetClientOwner.release(a);CabinetClientOwner.release(b); }
    }
    @Test void concurrentHostsCannotBothAcquireInputOwnership() throws Exception {
        Object a=new Object(),b=new Object();
        CountDownLatch go=new CountDownLatch(1),done=new CountDownLatch(2);
        AtomicInteger successes=new AtomicInteger();
        Runnable first=()->attempt(a,go,done,successes),second=()->attempt(b,go,done,successes);
        Thread t1=new Thread(first,"cabinet-owner-test-a"),t2=new Thread(second,"cabinet-owner-test-b");
        try {
            t1.start();t2.start();go.countDown();
            assertTrue(done.await(5,TimeUnit.SECONDS));
            assertEquals(1,successes.get());
        } finally {
            go.countDown();t1.join(5000);t2.join(5000);
            CabinetClientOwner.release(a);CabinetClientOwner.release(b);
        }
    }
    private static void attempt(Object candidate,CountDownLatch go,CountDownLatch done,AtomicInteger successes) {
        try { go.await();if(CabinetClientOwner.acquire(candidate))successes.incrementAndGet(); }
        catch(InterruptedException failure){Thread.currentThread().interrupt();}
        finally{done.countDown();}
    }
}
