package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static cn.piq.fcarcade.client.ClientSourceContracts.*;

/** Final legacy Native host contracts; all checks read actual source, without MC or a native core. */
class NativeConfiguredStartSourceTest {
    private static String host() throws Exception { return read("piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java"); }

    @Test void queuedUseAndClosedEventsCannotCrossToCopiedWorldWithSamePlayerAndCabinetUuid() throws Exception {
        String source = host();
        for (String signature : new String[]{"public static void use(NativeCabinetUseEvent e)", "public static void closed(NativeCabinetClosedEvent e)"}) {
            String event = body(source, signature);
            assertTrue(event.contains("varsourceServer=e.level().getServer();varsourceConnection=mc.getConnection();"));
            assertTrue(event.contains("mc.execute(()->{if(mc.getSingleplayerServer()==sourceServer&&mc.getConnection()==sourceConnection&&"));
            assertTrue(event.indexOf("varsourceConnection=mc.getConnection();") < event.indexOf("mc.execute("));
        }
    }

    @Test void ordinaryRightClickLoadsBoundGameAndOnlyShiftEmptyHandRequestsPicker() throws Exception {
        String source = host();
        assertEquals("openAt(pos,id,false);", body(source, "public static void openAt(BlockPos pos,UUID id)"));
        String use = body(source, "public static void use(NativeCabinetUseEvent e)");
        assertTrue(use.contains("booleanconfigure=e.player().isShiftKeyDown()&&e.player().getMainHandItem().isEmpty();"));
        assertTrue(use.contains("openAt(e.anchor(),e.assemblyId(),configure)"));
        String open = body(source, "private static void openAt(BlockPos pos,UUID id,boolean configure)");
        assertTrue(open.contains("if(configure)mc.setScreen(newNativeArcadeSetupScreen());elsestartGame(null,false);"));
        assertTrue(open.indexOf("if(!matches(pos,id))") < open.indexOf("if(playing)"));
        assertTrue(open.indexOf("CabinetClientOwner.acquire(INPUT_OWNER)") < open.indexOf("selectionKey="));
    }

    @Test void selectionKeyContainsWorldDimensionDeviceAndBackendAndCurrentPinsConnection() throws Exception {
        String source = host();
        String open = body(source, "private static void openAt(BlockPos pos,UUID id,boolean configure)");
        assertTrue(open.contains("CabinetGameSelection.key(dimension,id,cn.piq.nativearcade.NativeArcadeMod.BACKEND_ID)"));
        assertTrue(open.contains("sessionConnection=mc.getConnection();configureSelection=configure;"));
        assertTrue(open.contains("if(selectionKey==null){stop("));
        String current = body(source, "static boolean current()");
        assertTrue(current.contains("mc.getConnection()==sessionConnection"));
        assertTrue(current.contains("mc.level.dimension().location().equals(dimension)&&matches(anchor,identity)"));
        String matches = body(source, "private static boolean matches(");
        assertTrue(matches.contains("id.equals(be.assemblyId())"));
        assertTrue(matches.contains("NativeCabinetStructure.complete(mc.level,pos)"));
        String selection = read("piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetGameSelection.java");
        assertTrue(selection.contains("getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()"));
        assertTrue(compact(selection).contains("newCabinetRomBindings.Key(context,dimension.toString(),device,backend.toString())"));
    }

    @Test void explicitPickerConfirmationAloneMayRememberAndAllLoadsAreWorkerValidated() throws Exception {
        String source = host();
        String confirm = body(source, "static void start(Path rom)");
        assertTrue(confirm.contains("if(!configureSelection||!(Minecraft.getInstance().screeninstanceofNativeArcadeSetupScreen))return;"));
        assertTrue(confirm.endsWith("startGame(rom,true);"));
        String start = body(source, "private static void startGame(Path chosen,boolean remember)");
        assertTrue(start.contains("if(launching||session!=null||!current())return;"));
        assertTrue(start.indexOf("STARTER.execute(") < start.indexOf("CabinetGameSelection.load(key)"));
        assertTrue(start.contains("Pathrom=remember?chosen:CabinetGameSelection.load(key)"));
        assertTrue(start.contains("CabinetGameSelection.validate(rom,Set.of(\".zip\"),Set.of(\"neogeo.zip\",\"qsound_hle.zip\"));"));
        assertTrue(start.contains("if(shuttingDown||token!=generation)return;if(remember)CabinetGameSelection.remember(key,rom);"));
        assertTrue(start.indexOf("CabinetGameSelection.validate(") < start.indexOf("newNativeJniMediaSession("));
    }

    @Test void cancelOrNewConnectionClosesLateCoreBeforeItCanBecomeActive() throws Exception {
        String start = body(host(), "private static void startGame(Path chosen,boolean remember)");
        assertTrue(start.contains("inttoken=++generation;launching=true;configureSelection=false;"));
        assertTrue(start.contains("if(shuttingDown||token!=generation){ready.close();return;}PENDING.set(ready);"));
        assertTrue(start.contains("if(shuttingDown||token!=generation){PENDING.compareAndSet(ready,null);ready.close();return;}"));
        assertTrue(start.contains("mc.execute(()->{PENDING.compareAndSet(ready,null);if(token!=generation||!current()||!playing){ready.close();return;}session=ready;"));
        assertTrue(start.contains("catch(Exceptionfailure){if(opened!=null)opened.close();"));
        assertTrue(start.contains("if(token==generation)fail("));
        assertTrue(start.contains("catch(RejectedExecutionExceptionfailure){fail("));
        // The notice adapter preserves the exact same cleanup path after recording the original Throwable.
        String fail = body(host(), "private static void fail(String reason,Throwable failure)");
        assertTrue(fail.contains("DeviceNotices.record(\"街机\",reason,failure);stop(null);"));
    }

    @Test void stopInvalidatesOwnershipBindingsAndAnyPendingNativeProcess() throws Exception {
        String stop = body(host(), "static void stop(String reason)");
        assertTrue(stop.startsWith("generation++;"));
        assertTrue(stop.contains("varold=session;session=null;if(old!=null){old.clearInput();old.close();}"));
        assertTrue(stop.contains("GamepadInput.release(INPUT_OWNER);mixedInputMask=0;"));
        assertTrue(stop.contains("CabinetClientOwner.release(INPUT_OWNER);"));
        assertTrue(stop.contains("varpending=PENDING.getAndSet(null);if(pending!=null)pending.close();"));
        assertTrue(stop.contains("selectionKey=null;sessionConnection=null;configureSelection=false;"));
    }

    @Test void pickerCancelAndReplacedScreenExitThroughHostCleanup() throws Exception {
        String picker = compact(read("piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeSetupScreen.java"));
        assertTrue(picker.contains("NativeArcadeClient::start,()->NativeArcadeClient.stop(null)"));
        String tick = body(host(), "public static void tick(ClientTickEvent.Post event)");
        assertTrue(tick.contains("if(anchor!=null&&(!current()||(!playing&&!(mc.screeninstanceofNativeArcadeSetupScreen)))){stop("));
        String input = body(host(), "private static void syncInput()");
        assertTrue(input.contains("booleanactive=playing&&current()&&running()&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused();"));
        assertTrue(input.contains("if(!active){GamepadInput.pause(INPUT_OWNER);mixedInputMask=0;}"));
    }
}
