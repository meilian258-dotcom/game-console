package cn.piq.fcarcade.client.privateplay;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.libretro.LibretroNesCore;
import cn.piq.fcarcade.session.NesCoreVariant;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FcPrivateEngineTest {
    @TempDir Path temporary;
    private static final String MODULE = "a".repeat(64);
    private Path rom() throws IOException {
        Path rom = temporary.resolve("local.nes");
        if (!Files.exists(rom)) {
            byte[] bytes = new byte[16 + 16384 + 8192]; bytes[0] = 'N'; bytes[1] = 'E'; bytes[2] = 'S'; bytes[3] = 0x1a;
            bytes[4] = 1; bytes[5] = 1; Files.write(rom, bytes);
        }
        return rom;
    }
    private PrivateSaveStore store() { return new PrivateSaveStore(temporary.resolve("account-private")); }
    private PrivateSaveStore.Key key() throws IOException {
        return new PrivateSaveStore.Key("fc", NesCoreVariant.LIBRETRO_V1.stateNamespace() + "/" + MODULE, PrivateSaveStore.sha256(Files.readAllBytes(rom())));
    }
    private FcPrivateEngine engine(FakeCore core) throws IOException {
        return new FcPrivateEngine(rom(), store(), variant -> { core.claimOwner(); return core; }, MODULE);
    }
    private static PrivateEngine.SaveResult finish(FcPrivateEngine engine) throws Exception {
        return engine.stopAndSave().get(5, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> { while (!condition.getAsBoolean()) Thread.sleep(2); });
    }
    @Test void missingLocalRomFailsWithoutFactoryDownloadOrSave() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        var engine = new FcPrivateEngine(temporary.resolve("missing.nes"), store(), variant -> { opens.incrementAndGet(); return new FakeCore(); }, MODULE);
        await(() -> engine.error() != null);
        assertFalse(finish(engine).saved()); assertEquals(0, opens.get());
        assertFalse(Files.exists(temporary.resolve("account-private"))); assertFalse(engine.isReady());
    }
    @Test void invalidRomIsRejectedBeforeCoreAndDoesNotTouchOriginalFile() throws Exception {
        Path invalid = temporary.resolve("bad.nes"); byte[] bytes = new byte[32]; Files.write(invalid, bytes);
        AtomicInteger opens = new AtomicInteger();
        var engine = new FcPrivateEngine(invalid, store(), variant -> { opens.incrementAndGet(); return new FakeCore(); }, MODULE);
        await(() -> engine.error() != null);
        assertFalse(finish(engine).saved()); assertEquals(0, opens.get()); assertArrayEquals(bytes, Files.readAllBytes(invalid));
    }
    @Test void allCoreWorkIncludingRestoreSnapshotAndCloseUsesOwner() throws Exception {
        Thread caller = Thread.currentThread(); FakeCore first = new FakeCore(); var engine = engine(first);
        try { await(engine::isReady); await(() -> first.frames.get() >= 2); }
        finally { assertTrue(finish(engine).saved()); }
        assertNotSame(caller, first.owner); assertTrue(first.closed); assertEquals(1, first.snapshots.get());
        byte[] saved = store().load(key()).orElseThrow().state();
        FakeCore second = new FakeCore(); var resumed = engine(second);
        try { await(resumed::isReady); assertArrayEquals(saved, second.loadedState); }
        finally { assertTrue(finish(resumed).saved()); }
        assertTrue(second.closed);
    }
    @Test void pausedStopSavesWithoutWaitingForAnotherEmulatedFrame() throws Exception {
        FakeCore core = new FakeCore(); core.blockFirst = true; var engine = engine(core);
        try {
            assertTrue(core.entered.await(3, TimeUnit.SECONDS)); engine.paused(true);
            core.release.countDown(); await(() -> core.frames.get() == 1);
            assertTrue(finish(engine).saved()); assertEquals(1, core.frames.get()); assertEquals(1, core.snapshots.get());
            assertNull(engine.pollFrame()); assertTrue(core.closed);
        } finally { core.release.countDown(); finish(engine); }
    }
    @Test void constructionAndStopDoNotWaitForSlowWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeCore core = new FakeCore();
        var engine = new FcPrivateEngine(rom(), store(), variant -> {
            core.claimOwner(); entered.countDown();
            if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("test factory timeout");
            return core;
        }, MODULE);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var result = assertTimeout(Duration.ofMillis(300), engine::stopAndSave);
            assertFalse(result.isDone()); assertFalse(engine.isReady());
            assertSame(result, engine.stopAndSave()); release.countDown();
            result.get(5, TimeUnit.SECONDS); assertTrue(core.closed);
        } finally { release.countDown(); finish(engine); }
    }
    @Test void hungCoreDiagnosticIsVisibleButStopDoesNotPretendNativeTeardownCompleted()throws Exception{
        FakeCore core=new FakeCore();core.blockFirst=true;var running=engine(core);
        try{
            assertTrue(core.entered.await(3,TimeUnit.SECONDS));core.diagnostic="JNI运行超时，尚未退出";
            assertEquals(core.diagnostic,running.error());var pending=running.stopAndSave();
            assertFalse(pending.isDone());assertFalse(core.closed);
            core.release.countDown();pending.get(5,TimeUnit.SECONDS);assertTrue(core.closed);
        }finally{core.release.countDown();finish(running);}
    }
    @Test void pausedBeforeReadyStillInitializesAndSavesWithoutAnyGameplayFrameOrClientCallback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeCore core = new FakeCore();
        var engine = new FcPrivateEngine(rom(), store(), variant -> {
            core.claimOwner(); entered.countDown();
            if (!release.await(4, TimeUnit.SECONDS)) throw new IOException("test factory timeout");
            return core;
        }, MODULE);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS)); engine.paused(true); release.countDown();
            await(engine::isReady); Thread.sleep(40);
            assertEquals(0, core.frames.get()); assertNull(engine.pollFrame());
            engine.offerInput(256, 0);
            assertTrue(finish(engine).saved()); assertTrue(core.closed);
            assertEquals(0, core.frames.get()); assertEquals(1, core.snapshots.get());
            assertArrayEquals(envelope(PrivateSaveStore.sha256(Files.readAllBytes(rom())), new byte[]{0, 42}), store().load(key()).orElseThrow().state());
        } finally { release.countDown(); finish(engine); }
    }
    @Test void privateFcExposesOneControllerAndRejectsP2BeforeAnyQueuedPressCanRun() throws Exception {
        FakeCore core = new FakeCore(); core.blockFirst = true; var engine = engine(core);
        try {
            assertTrue(core.entered.await(3, TimeUnit.SECONDS));
            assertEquals(1, engine.maxPlayers()); assertEquals(1, engine.asRetro().maxPlayers());
            engine.offerInput(256, 0);
            assertThrows(IllegalArgumentException.class, () -> engine.offerInput(0, 1));
            assertThrows(IllegalArgumentException.class, () -> engine.offerInputs(0, 1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> engine.releasePort(1));
            assertThrows(IllegalArgumentException.class, () -> engine.releasePort(-1));
            assertThrows(IllegalArgumentException.class, () -> engine.offerInput(4096, 0));
            engine.offerInput(256, 0); core.release.countDown();
            await(() -> core.completed.size() >= 3);
            assertTrue(core.completed.stream().allMatch(value -> value == 0));
            engine.offerInput(0, 0); engine.offerInput(256, 0);
            await(() -> core.completed.contains(1));
        } finally { core.release.countDown(); finish(engine); }
    }
    @Test void coreFailureDoesNotOverwritePreviouslySavedProgress() throws Exception {
        var store = store(); store.save(key(), envelope(PrivateSaveStore.sha256(Files.readAllBytes(rom())), new byte[]{9, 8}), new byte[0]);
        Path saved = store.directory(key()).resolve("latest.zip"); byte[] before = Files.readAllBytes(saved);
        FakeCore core = new FakeCore(); core.failFrame = true; var engine = engine(core);
        await(() -> engine.error() != null);
        assertFalse(finish(engine).saved()); assertEquals(0, core.snapshots.get()); assertTrue(core.closed);
        assertArrayEquals(before, Files.readAllBytes(saved));
    }
    @Test void corruptRestoreIsNotReplacedByFreshGameOnExit() throws Exception {
        var store = store(); Files.createDirectories(store.directory(key()));
        Path saved = store.directory(key()).resolve("latest.zip"); Files.write(saved, new byte[]{1, 2, 3});
        FakeCore core = new FakeCore(); var engine = engine(core); await(() -> engine.error() != null);
        assertFalse(finish(engine).saved()); assertEquals(0, core.frames.get()); assertEquals(0, core.snapshots.get());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(saved));
    }
    @Test void migratedPrivateCoreNeverLoadsOrOverwritesPreviousWasmNamespace() throws Exception {
        var store = store();
        var oldKey = new PrivateSaveStore.Key("fc", "nes-legacy-v1/" + MODULE, PrivateSaveStore.sha256(Files.readAllBytes(rom())));
        store.save(oldKey, new byte[]{12, 34, 56}, new byte[0]);
        byte[] oldFile = Files.readAllBytes(store.directory(oldKey).resolve("latest.zip"));
        FakeCore core = new FakeCore(); var running = engine(core);
        try { await(running::isReady); assertNull(core.loadedState); }
        finally { assertTrue(finish(running).saved()); }
        assertArrayEquals(oldFile, Files.readAllBytes(store.directory(oldKey).resolve("latest.zip")));
        assertTrue(store.load(key()).isPresent());
    }
    @Test void diskFailureReturnsFailedSaveAndClosesCore() throws Exception {
        FakeCore core = new FakeCore();
        var failing = new PrivateSaveStore(temporary.resolve("account-private"), new PrivateSaveStore.Operations() {
            @Override public void replace(Path source, Path destination) throws IOException { throw new IOException("disk full test"); }
        });
        var engine = new FcPrivateEngine(rom(), failing, variant -> { core.claimOwner(); return core; }, MODULE);
        await(engine::isReady); var result = finish(engine);
        assertFalse(result.saved()); assertTrue(result.message().contains("disk full test")); assertTrue(core.closed);
        assertFalse(Files.exists(failing.directory(key()).resolve("latest.zip")));
    }
    @Test void jniTrialUsesIndependentKeyAndNeverImportsOrReplacesNormalPrivateSave() throws Exception {
        var store=store();var originalKey=key();
        byte[] original=envelope(originalKey.romSha256(),new byte[]{9,8});store.save(originalKey,original,new byte[0]);
        byte[] archive=Files.readAllBytes(store.directory(originalKey).resolve("latest.zip"));
        String prefix=FcPrivateEngine.namespacePrefix(cn.piq.retro.libretro.LibretroRuntimes.Backend.JNI_TRIAL);
        assertEquals("",FcPrivateEngine.namespacePrefix(cn.piq.retro.libretro.LibretroRuntimes.Backend.PROCESS));
        var trialKey=new PrivateSaveStore.Key("fc",prefix+originalKey.coreNamespace(),originalKey.romSha256());
        FakeCore first=new FakeCore();var running=new FcPrivateEngine(rom(),store,v->{first.claimOwner();return first;},MODULE,prefix);
        try{await(running::isReady);assertNull(first.loadedState);}finally{assertTrue(finish(running).saved());}
        assertArrayEquals(archive,Files.readAllBytes(store.directory(originalKey).resolve("latest.zip")));
        byte[] trial=store.load(trialKey).orElseThrow().state();FakeCore second=new FakeCore();
        var resumed=new FcPrivateEngine(rom(),store,v->{second.claimOwner();return second;},MODULE,prefix);
        try{await(resumed::isReady);assertArrayEquals(trial,second.loadedState);}finally{assertTrue(finish(resumed).saved());}
        assertArrayEquals(archive,Files.readAllBytes(store.directory(originalKey).resolve("latest.zip")));
    }
    @Test void boundedWorkerRejectsOverlapUntilFirstHasSavedAndClosed() throws Exception {
        FakeCore first = new FakeCore(); var running = engine(first); FakeCore rejected = new FakeCore();
        try {
            await(running::isReady); var overlapping = engine(rejected);
            assertFalse(overlapping.isReady()); assertFalse(finish(overlapping).saved()); assertNull(rejected.owner);
        } finally { finish(running); }
        FakeCore next = new FakeCore(); var later = engine(next);
        try { await(later::isReady); } finally { finish(later); }
        assertTrue(next.closed);
    }
    @Test void libretroKeysConvertToNesInDocumentedOrder() {
        int[] expected = {2, 0, 4, 8, 16, 32, 64, 128, 1, 0, 0, 0};
        for (int bit = 0; bit < 12; bit++) assertEquals(expected[bit], FcPrivateEngine.nesMask(1 << bit));
        assertEquals(255, FcPrivateEngine.nesMask(4095));
        assertThrows(IllegalArgumentException.class, () -> FcPrivateEngine.nesMask(-1));
        assertThrows(IllegalArgumentException.class, () -> FcPrivateEngine.nesMask(4096));
    }
    @Test void everyTapEdgeIsConsumedOnlyOnAnActualFrame() throws Exception {
        FakeCore core = new FakeCore(); core.blockFirst = true; var engine = engine(core);
        try {
            assertTrue(core.entered.await(3, TimeUnit.SECONDS));
            engine.offerInput(256, 0); engine.offerInput(0, 0); core.release.countDown();
            await(() -> core.completed.size() >= 4);
            assertEquals(List.of(0, 1, 0), List.copyOf(core.completed).subList(0, 3));
        } finally { core.release.countDown(); finish(engine); }
    }
    @Test void pauseDropsInFlightPresentationAndRequiresGenuineRelease() throws Exception {
        FakeCore core = new FakeCore(); core.blockFirst = true; var engine = engine(core);
        try {
            assertTrue(core.entered.await(3, TimeUnit.SECONDS));
            engine.paused(true); engine.paused(false); engine.offerInput(256, 0); core.release.countDown();
            await(() -> core.completed.size() >= 3);
            assertTrue(core.completed.stream().allMatch(value -> value == 0));
            engine.offerInput(0, 0); engine.offerInput(256, 0);
            await(() -> core.completed.contains(1));
            engine.paused(true); assertNull(engine.pollFrame());
        } finally { core.release.countDown(); finish(engine); }
    }
    @Test void engineAndStoreHaveNoGameNetworkingOrPublicSaveIntegration() throws Exception {
        Path root = Path.of("src/main/java/cn/piq/fcarcade/client/privateplay");
        for (String name : List.of("FcPrivateEngine.java", "PrivateSaveStore.java")) {
            String source = Files.readString(root.resolve(name));
            for (String forbidden : List.of("ClientArcadeSession", "ClientNesWorker", "PacketDistributor", "sendToServer(",
                    "WatchClient", "MediaTap", "ArcadeSaveStore", "CompoundTag", "ClientRomTransfers"))
                assertFalse(source.contains(forbidden), name + ": " + forbidden);
        }
    }
    private static byte[] envelope(String romSha, byte[] raw) {
        return ByteBuffer.allocate(80 + raw.length).putInt(0x504C5231).putInt(1).putInt(0)
                .put(HexFormat.of().parseHex(LibretroNesCore.PROFILE_SHA256))
                .put(HexFormat.of().parseHex(romSha)).putInt(raw.length).put(raw).array();
    }
    private static final class FakeCore implements NesCore {
        volatile Thread owner; volatile boolean closed, blockFirst, failFrame;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger frames = new AtomicInteger(), snapshots = new AtomicInteger();
        final CopyOnWriteArrayList<Integer> completed = new CopyOnWriteArrayList<>();
        volatile byte[] loadedState;volatile String diagnostic="";
        private int one;
        private String romSha;
        void claimOwner() { owner = Thread.currentThread(); }
        void check() { assertSame(owner, Thread.currentThread(), "core method escaped owner worker"); }
        @Override public String stateNamespace() { check(); return NesCoreVariant.LIBRETRO_V1.stateNamespace(); }
        @Override public String diagnosticError(){return diagnostic;}
        @Override public void loadRom(byte[] bytes) { check(); assertTrue(bytes.length > 16); romSha=PrivateSaveStore.sha256(bytes); }
        @Override public void reset() { check(); }
        @Override public void setControllerState(int port, int mask) { check(); if (port == 0) one = mask; else assertEquals(0, mask, "Private FC P2 must stay neutral"); }
        @Override public void runFrame() {
            check();
            if (failFrame) throw new IllegalStateException("injected core failure");
            if (blockFirst && frames.get() == 0) {
                entered.countDown();
                try { if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("test frame timeout"); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
            }
            completed.add(one); frames.incrementAndGet();
        }
        @Override public void copyFrameRgba(byte[] out) { check(); Arrays.fill(out, (byte) 42); }
        @Override public int copyAudioSamples(float[] out) { check(); out[0] = .1F; return 1; }
        @Override public void copyCpuRam(byte[] out) { check(); }
        @Override public byte[] saveTransientState() { check(); snapshots.incrementAndGet(); return envelope(romSha, new byte[]{(byte) frames.get(), 42}); }
        @Override public void loadTransientState(byte[] state) { check(); loadedState = state.clone(); }
        @Override public void close() { check(); closed = true; }
    }
}
