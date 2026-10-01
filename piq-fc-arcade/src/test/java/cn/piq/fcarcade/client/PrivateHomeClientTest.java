package cn.piq.fcarcade.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The first three tests execute pure mask/receipt logic. Remaining tests are source wiring guards,
 * not live Minecraft inventory, keyboard, rendering, networking or shutdown integration tests.
 */
class PrivateHomeClientTest {
    private record Receipt(UUID lease, int count) {}

    @Test void all256NesMasksUseCanonicalP1BitsWithoutIntroducingOtherButtons() {
        int[] bits = {256, 1, 4, 8, 16, 32, 64, 128};
        for (int nes = 0; nes < 256; nes++) {
            int expected = 0;
            for (int bit = 0; bit < bits.length; bit++) if ((nes & (1 << bit)) != 0) expected |= bits[bit];
            assertEquals(expected, PrivateHomeClient.canonicalNes(nes), "NES mask " + nes);
        }
    }
    @Test void rawReceiptIdentityCountsInventoryAliasesOnceAndKeepsUnrelatedLoansSeparate() {
        UUID lease = UUID.randomUUID(); var held = new Receipt(lease, 1);
        assertTrue(ControllerCapturePolicy.uniqueHeld(held, lease,
                List.of(held, held, new Receipt(UUID.randomUUID(), 2)), Receipt::lease, Receipt::count));
        assertFalse(ControllerCapturePolicy.uniqueHeld(held, lease,
                List.of(new Receipt(lease, 1)), Receipt::lease, Receipt::count));
    }
    @Test void rawIdentityRejectsCountTwoDuplicateEvenWhenTheOnlyHeldReceiptIsStrictlyValid() {
        UUID lease = UUID.randomUUID(); var held = new Receipt(lease, 1);
        for (int count : new int[]{1, 2, 64})
            assertFalse(ControllerCapturePolicy.uniqueHeld(held, lease,
                    List.of(held, held, new Receipt(lease, count)), Receipt::lease, Receipt::count));
        var stackedHeld = new Receipt(lease, 2);
        assertFalse(ControllerCapturePolicy.uniqueHeld(stackedHeld, lease,
                List.of(stackedHeld), Receipt::lease, Receipt::count));
    }
    @Test void strictCandidateAndRawDuplicateIdentityRemainSeparateSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        assertTrue(source.contains("public UUID lease(ItemStack stack){var r=HomeControllerData.receipt(stack)"));
        assertTrue(source.contains("public UUID identity(ItemStack stack){return HomeControllerData.leaseId(stack);}"));
        assertTrue(source.contains("r!=null&&r.session()==0&&r.dimension().equals(player.level().dimension().location().toString())"));
        assertTrue(source.contains("!c.visualPowered()&&r.console().equals(c.hardwareId())"));
        assertTrue(source.contains("c.controllerVisualPlayer(r.port())"));
        assertTrue(source.contains("c.controllerVisualLease(r.port()),c.controllerDocked(r.port())"));
        assertTrue(source.contains("ControllerCapture.unique(mc.player,held,lease,p::identity)"));
        assertTrue(source.contains("ControllerCapture.unique(p,stack,t.lease,t.provider::identity)"));
        assertTrue(source.contains("ControllerCapture.unique(p,stack,lease,t.provider::identity)"));
        assertTrue(source.contains("t.provider.independentCartridgePower()?t.provider.matches(p,stack,t.console):t.lease.equals(lease)"));
        assertFalse(source.contains("ControllerCapture.unique(mc.player,held,lease,p::lease)"));
        assertFalse(source.contains("ControllerCapture.unique(p,stack,t.lease,t.provider::lease)"));
    }
    @Test void targetRevalidatesExactHardwareAndSnapshotPowerWithoutMutatingServerSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        String valid = between(source, "private static boolean valid(Target t)", "private static void tick(");
        for (String guard : new String[]{"mc.getConnection()!=t.connection", "mc.level!=t.level",
                "!mc.player.getUUID().equals(t.player)", "t.console.isRemoved()||t.tv.isRemoved()",
                "!mc.level.hasChunkAt(t.console.getBlockPos())", "!mc.level.hasChunkAt(t.tv.getBlockPos())",
                "mc.level.getBlockEntity(t.console.getBlockPos())!=t.console", "mc.level.getBlockEntity(t.tv.getBlockPos())!=t.tv",
                "!Objects.equals(t.hardware,hardware(t.console))", "!t.television.equals(t.tv.hardwareId())", "t.link==null",
                "!t.link.equals(link(t.console))", "!t.link.equals(t.tv.linkId())", "t.tv.signalPresent()",
                "t.tv.powered()!=t.tvPower", "HomeHardware.connected(mc.level,t.console,t.tv)"})
            assertTrue(valid.contains(guard), guard);
        assertTrue(source.contains("link(console),tv.powered())"));
        for (String mutation : new String[]{".setPower(", ".signal(", ".setChanged(", "HomeConsoleRuntime.powerOn("})
            assertFalse(source.contains(mutation), mutation);
    }
    @Test void everyProviderBusyAndFcPendingHostAreAdmissionBarriersSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        assertTrue(source.contains("PROVIDERS.values().stream().anyMatch(Provider::publicBusy)"));
        assertTrue(source.contains("ClientArcadeEvents.hasHomeParticipant()||ClientRomTransfers.hasPendingParticipant()"));
        String participants = source("client/ClientArcadeEvents");
        assertTrue(participants.contains("s->s.hasController()||s.isComputeHost()"));
        String transfers = source("client/ClientRomTransfers");
        assertTrue(transfers.contains("EXPECTED_SESSIONS.values().stream().anyMatch(p->p.active()&&(p.computeHost()||p.role().controllerIndex()>=0))"));
        String apply = between(transfers, "static void applySession(", "static void select(");
        assertTrue(apply.contains("if(payload.active()&&(payload.computeHost()||payload.role().controllerIndex()>=0))PrivateHomeClient.stop("));
        before(apply, "PrivateHomeClient.stop(", "ensureLocal(");
    }
    @Test void privateEntryDoesNotRouteGameDataThroughPublicTransfersOrGrantRightsSourceGuards() throws Exception {
        for (String name : List.of("client/PrivateHomeClient", "client/PrivateHomeScreen")) {
            String source = source(name);
            for (String forbidden : List.of("PacketDistributor", "sendToServer(", "FcNetwork.", "SfcHomeNetwork.",
                    "ArcadeSessionPayload", "ArcadeHomeInputPayload", "WatchPublisher", "WatchNetwork", "MediaTap",
                    "ClientRomTransfers.join(", "ClientRomTransfers.select(", "RomRequest", "ServerArcadeSessions",
                    "HomeControllerService.grant(", "HomeControllerData.bind(", "FcCartridgeData.commit("))
                assertFalse(source.contains(forbidden), name + " / " + forbidden);
        }
    }
    @Test void oneInputReservationAndCaptureRefreshAreInstalledBeforePlaySourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        assertTrue(source.contains("ControllerCapture.registerRuntime(PrivateHomeClient::refreshKeyboard)"));
        assertTrue(source.contains("Commands.literal(\"gameconsole-private\")"));
        assertTrue(source("client/ClientArcadeEvents").contains("PrivateHomeClient.install()"));
        String start = between(source, "static String start(", "private static boolean connected()");
        before(start, "!valid(target)||(!target.provider.cartridgePower()&&held(target)==null)", "CabinetClientOwner.acquire(reservation)");
        assertTrue(source.contains("default boolean cartridgePower(){return false;}"));
        assertTrue(source.contains("if(p.cartridgePower())continue;"));
        assertTrue(source.contains("!provider.matches(mc.player,item,console)||!ControllerCapture.unique(mc.player,item,lease,provider::identity)"));
        before(start, "CabinetClientOwner.acquire(reservation)", "new Run(target,reservation,rom,root,backend)");
        assertTrue(start.contains("if(current==null||current.owner!=reservation)CabinetClientOwner.release(reservation)"));
        assertTrue(source.contains("target.provider.cartridgePower()?target.provider.createCartridge(rom,saveRoot,backend,target.console):target.provider.create(rom,saveRoot,backend);engine.paused(true)"));
        assertTrue(source.contains("default boolean independentCartridgePower(){return false;}"));
        assertTrue(source.contains("t.lease.equals(t.provider.cartridgeSession(mc.player,t.console))"));
    }
    @Test void handSwitchAndFocusLossClearInputsBeforeNeutralRearmSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        String refresh = between(source, "private static void refreshKeyboard()", "private static void releaseKeys(");
        assertTrue(refresh.contains("getMainHandItem()==held?0:1"));
        assertTrue(refresh.contains("if(hand!=r.hand){r.hand=hand;KeyboardInput.pause(r.owner);releaseKeys(r);}"));
        assertTrue(refresh.contains("!InputOwnership.owns(r.owner)"));
        assertTrue(source.contains("r.engine.clearInput();r.lastMask=-1;GamepadInput.pause(r.owner)"));
        String sample = between(source, "private static void sample()", "static int canonicalNes(");
        assertTrue(sample.contains("held(r.target)!=null&&mc.screen==null&&mc.isWindowActive()&&!mc.isPaused()&&r.engine.isReady()"));
        assertTrue(sample.contains("r.engine.paused(r.paused);releaseKeys(r)"));
        assertTrue(sample.contains("if(!focused){KeyboardInput.pause(r.owner);return;}"));
        assertTrue(sample.contains("if(!k.armed())mask=0"));
    }
    @Test void samplingHasReentryGuardAndOnlyEmitsCanonicalP1SourceGuards() throws Exception {
        String sample = between(source("client/PrivateHomeClient"), "private static void sample()", "private static void update()");
        assertTrue(sample.contains("if(sampling)return;sampling=true;"));
        assertTrue(sample.contains("finally{sampling=false;}"));
        assertTrue(sample.contains("KeyboardInput.poll(r.owner,0,true)"));
        assertTrue(sample.contains("if(r.target.provider.profile()==KeyboardConfig.Profile.NES)mask=canonicalNes(mask)"));
        assertTrue(sample.contains("r.engine.offerInput(mask,0)"));
        assertFalse(sample.contains("offerInput(mask,mask)"));
    }
    @Test void stopKeepsRawSaveFutureAndShutdownWaitsAtMostEightSecondsWithoutInterruptingSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        String stop = between(source, "public static void stop(", "private static void shutdown()");
        assertTrue(stop.contains("current=null;closing=true"));
        before(stop, "pendingSave=r.engine.stopAndSave();", "pendingSave.whenComplete(");
        assertFalse(stop.contains("pendingSave=r.engine.stopAndSave().whenComplete"));
        assertTrue(stop.contains("KeyboardInput.release(r.owner);GamepadInput.release(r.owner);r.engine.clearInput()"));
        assertTrue(stop.contains("CabinetClientOwner.release(r.owner)"));
        assertTrue(source.contains("GameShuttingDownEvent e)->shutdown()"));
        String shutdown = between(source, "private static void shutdown()", "static boolean ownsDisplay(");
        assertTrue(shutdown.contains("var pending=pendingSave;if(pending==null)return"));
        assertTrue(shutdown.contains("pending.get(8,java.util.concurrent.TimeUnit.SECONDS)"));
        assertTrue(shutdown.contains("did not confirm before shutdown"));
        for (String forbidden : List.of(".cancel(", ".interrupt(", "Minecraft.getInstance().execute", "while(closing)"))
            assertFalse(shutdown.contains(forbidden), forbidden);
    }
    @Test void onlyOwnedDisplaySuppressesIdleToneAndLocallyOverridesIndicatorsSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        assertTrue(source.contains("current.target.tv==tv&&valid(current.target)"));
        assertTrue(source.contains("current.target.console==console&&valid(current.target)"));
        assertTrue(source("client/HomeApplianceClient").contains("if (PrivateHomeClient.ownsDisplay(tv)) return;"));
        assertTrue(source("client/TelevisionTone").contains("if (PrivateHomeClient.ownsDisplay(tv)) return false;"));
        String indicators = source("client/PowerIndicatorRenderer");
        assertTrue(indicators.contains("console.visualPowered()||PrivateHomeClient.ownsConsole(console)"));
        assertTrue(indicators.contains("tv.powered()||PrivateHomeClient.ownsDisplay(tv)"));
    }
    @Test void localPathsAndCapturedUiTargetStaySeparateFromReceiptsSourceGuards() throws Exception {
        String source = source("client/PrivateHomeClient");
        assertTrue(source.contains("rom=Path.of(filename.strip())"));
        assertTrue(source.contains("if(!rom.isAbsolute())"));
        assertTrue(source.contains("Backend.JNI_TRIAL?\"piq-private-home-jni-v1\":\"piq-private-home\""));
        assertTrue(source.contains("resolve(directory).resolve(target.player.toString()).resolve(key)"));
        assertTrue(source.contains("MessageDigest.getInstance(\"SHA-256\")"));
        assertTrue(source.contains("if(current!=null||closing)return"));
        String screen = source("client/PrivateHomeScreen");
        assertTrue(screen.contains("PrivateHomeClient.start(target,path.getValue(),backend)"));
        assertTrue(screen.contains("PrivateHomeClient.targetLabel(target)"));
        assertTrue(screen.contains("path.setMaxLength(2048)"));
        assertTrue(screen.contains("!PrivateHomeClient.active()&&!PrivateHomeClient.busy()"));
        String label = between(source, "static String targetLabel(", "static Target find()");
        assertFalse(label.contains("find()"));
    }
    @Test void adaptedPrivateUiDefaultsToJniButKeepsExplicitFallbackAndPublicFactorySourceGuard() throws Exception {
        String screen=source("client/PrivateHomeScreen"),client=source("client/PrivateHomeClient");
        assertTrue(screen.contains("backend=PrivateHomeClient.defaultBackend(target)"));
        assertTrue(screen.contains("new ConfirmScreen(accepted->"));
        assertTrue(screen.contains("if(accepted&&target==confirmedTarget){backend=LibretroRuntimes.Backend.JNI_TRIAL"));
        assertTrue(client.contains("default boolean supportsJniTrial(){return false;}"));
        assertTrue(client.contains("return start(target,filename,defaultBackend(target));"));
        assertTrue(client.contains("LibretroRuntimes.defaultBackend(target!=null&&target.provider.supportsJniTrial())"));
        assertTrue(client.contains("if(LibretroRuntimes.isJniBusy())return"));
        assertFalse(source("core/NesCores").contains("JNI_TRIAL"));
        assertFalse(source("client/ClientArcadeSession").contains("JNI_TRIAL"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/" + name + ".java")).replace("\r\n", "\n");
    }
    private static String between(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from + start.length());
        assertTrue(from >= 0 && to > from, start + " / " + end);
        return source.substring(from, to);
    }
    private static void before(String source, String first, String second) {
        int a = source.indexOf(first), b = source.indexOf(second);
        assertTrue(a >= 0 && b > a, first + " before " + second);
    }
}
