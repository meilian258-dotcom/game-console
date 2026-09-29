package cn.piq.fcarcade.client;

import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ClientNesWorkerTest {
    @Test void performanceReadsDoNotConsumeOutputOrAdvanceSimulation() throws Exception {
        FakeCore core=new FakeCore();
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",false);
        try {
            await(worker::isReady);worker.configure(true,false,true);worker.performanceEnabled(true);
            worker.enqueue(new ClientNesWorker.Input(3,1,2));
            await(()->worker.performance().audioBuffers()==3&&worker.performance().frame()==3);
            for(int i=0;i<100;i++){
                var p=worker.performance();assertEquals(3,p.audioBuffers());assertEquals(0,p.pendingFrames());assertEquals(3,p.frame());
            }
            assertEquals(3,core.frames.get());var output=worker.drain();assertEquals(3,output.audio().size());assertNotNull(output.picture());
            worker.performanceEnabled(false);assertFalse(worker.performance().sample().enabled());
            assertEquals(3,core.frames.get());
        } finally { worker.close(); } await(()->core.closed);
    }
    @Test void persistentCaptureAndRestoreAreDistinctFromSpectatorReplay()throws Exception{
        AtomicInteger persistentSaves=new AtomicInteger(),restores=new AtomicInteger();
        FakeCore core=new FakeCore(){
            @Override public byte[] savePersistentState(){assertSame(owner,Thread.currentThread());persistentSaves.incrementAndGet();return new byte[]{42};}
            @Override public void loadPersistentState(byte[] bytes){assertSame(owner,Thread.currentThread());assertArrayEquals(new byte[]{42},bytes);restores.incrementAndGet();}
        };
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",true);
        try{
            await(worker::isReady);worker.snapshot(0,new byte[]{42},true);await(()->restores.get()==1);
            assertEquals(0,core.loads.get());worker.snapshot(0,new byte[]{0},false);await(()->core.loads.get()==1);
            worker.requestSnapshot();await(()->persistentSaves.get()==1);assertEquals(1,restores.get());
        }finally{worker.close();}await(()->core.closed);
    }
    @Test void failedRequiredMediaTapIsNotLeftPublishingFrozenFrames()throws Exception{
        FakeCore core=new FakeCore();AtomicInteger failures=new AtomicInteger();
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",false);
        try{
            await(worker::isReady);worker.mediaTap(new ClientNesWorker.MediaTap(){
                public boolean enabled(){throw new IllegalStateException("capture failure");}
                public void frame(byte[] p,float[] a,int n){fail("disabled");}
                public void failed(Throwable failure){assertEquals("capture failure",failure.getMessage());failures.incrementAndGet();}
            });
            worker.enqueue(new ClientNesWorker.Input(3,0,0));await(()->core.frames.get()==3);
            assertEquals(1,failures.get());
        }finally{worker.close();}await(()->core.closed);
    }
    @Test void mediaTapUsesExistingCoreAndAudioEvenWhenHostLocallyMuted()throws Exception{
        FakeCore core=new FakeCore();AtomicInteger taps=new AtomicInteger(),factories=new AtomicInteger();
        var worker=new ClientNesWorker(()->{factories.incrementAndGet();core.claimOwner();return core;},true,false,"",false);
        try{
            await(worker::isReady);worker.configure(false,false,false);
            worker.mediaTap(new ClientNesWorker.MediaTap(){
                public boolean enabled(){return true;}
                public void frame(byte[] pixels,float[] mono,int count){
                    assertSame(core.owner,Thread.currentThread());assertEquals(1,count);
                    assertEquals((byte)mono[0],pixels[0]);taps.incrementAndGet();
                }
            });
            worker.enqueue(new ClientNesWorker.Input(3,1,2));await(()->taps.get()==3);
            assertEquals(1,factories.get());assertTrue(worker.drain().audio().isEmpty());
            worker.mediaTap(null);worker.enqueue(new ClientNesWorker.Input(6,0,0));await(()->core.frames.get()==6);
            assertEquals(3,taps.get());
        }finally{worker.close();}await(()->core.closed);
    }
    @Test void brokenMediaTapCannotKillGameOrRetryEveryFrame()throws Exception{
        FakeCore core=new FakeCore();AtomicInteger calls=new AtomicInteger();
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",false);
        try{
            await(worker::isReady);worker.mediaTap(new ClientNesWorker.MediaTap(){
                public boolean enabled(){return true;}
                public void frame(byte[] p,float[] a,int n){calls.incrementAndGet();throw new IllegalStateException("broken encoder");}
            });
            worker.enqueue(new ClientNesWorker.Input(6,0,0));await(()->core.frames.get()==6);
            assertEquals(1,calls.get());assertTrue(worker.isReady());
            assertTrue(worker.drain().events().stream().noneMatch(e->e.kind().equals("error")));
        }finally{worker.close();}await(()->core.closed);
    }
    @Test void frameResolutionHistoryExecutesEveryEdgeButDoesNotAnimateOldCatchupPulses() throws Exception {
        FakeCore core = new FakeCore();
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, true, true, "", true);
        try {
            await(worker::isReady); worker.snapshot(0, new byte[]{0}, false);
            await(() -> core.loads.get() == 1);
            assertTrue(worker.history(0, List.of(new ClientNesWorker.Input(1, 1, 0),
                    new ClientNesWorker.Input(2, 0, 0), new ClientNesWorker.Input(3, 1, 0),
                    new ClientNesWorker.Input(6, 0, 0))));
            await(() -> core.frames.get() == 6);
            assertEquals(List.of(1, 0, 1, 0, 0, 0), List.copyOf(core.completedMasks));
            worker.presentControllerFrame(); assertEquals(0, worker.presentedControllerMask(0));
            worker.enqueue(new ClientNesWorker.Input(7, 2, 0));
            await(() -> worker.appliedControllerMask(0) == 2);
            worker.presentControllerFrame(); assertEquals(2, worker.presentedControllerMask(0));
        } finally { worker.close(); }
        await(() -> core.closed);
    }
    @Test void resumingBeforeRawEdgeCapturePreservesTapBeforeNextRender() throws Exception {
        FakeCore core = new FakeCore(); core.blockFrame = true;
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, false, true, "", false);
        try {
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            worker.configure(true, true, false);
            // Session must synchronize the post-render Minecraft pause change
            // before forwarding next-loop raw events, not after forwarding them.
            worker.configure(true, false, false);
            worker.input(0, 1); worker.input(0, 0);
            core.releaseFrame.countDown(); await(() -> core.completedMasks.size() >= 3);
            assertEquals(List.of(0, 1, 0), List.copyOf(core.completedMasks).subList(0, 3));
        } finally { core.releaseFrame.countDown(); worker.close(); }
        await(() -> core.closed);
    }
    @Test void guiCanSuppressPresentationWithoutSkippingAuthoritativeCoreFrames() throws Exception {
        FakeCore core = new FakeCore(); core.blockFrame = true;
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, true, true, "", false);
        try {
            await(worker::isReady); worker.enqueue(new ClientNesWorker.Input(1, 1, 0));
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            worker.setControllerPresentationEnabled(false); core.releaseFrame.countDown();
            await(() -> core.frames.get() == 1);
            worker.enqueue(new ClientNesWorker.Input(2, 2, 0)); await(() -> core.frames.get() == 2);
            worker.presentControllerFrame(); assertEquals(0, worker.presentedControllerMask(0));
            assertEquals(List.of(1, 2), List.copyOf(core.completedMasks));
            worker.setControllerPresentationEnabled(true); worker.presentControllerFrame();
            assertEquals(0, worker.presentedControllerMask(0), "GUI-era pulses cannot replay on resume");
            worker.enqueue(new ClientNesWorker.Input(3, 4, 0)); await(() -> worker.appliedControllerMask(0) == 4);
            worker.presentControllerFrame(); assertEquals(4, worker.presentedControllerMask(0));
        } finally { core.releaseFrame.countDown(); worker.close(); }
        await(() -> core.closed);
    }
    @Test void twoStreamTapsBetweenCoreFramesRemainTwoActualPresses() throws Exception {
        FakeCore core = new FakeCore(); core.blockFrame = true;
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, false, true, "", false);
        try {
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            for (int mask : new int[]{1, 0, 1, 0}) worker.input(0, mask);
            assertEquals(0, worker.appliedControllerMask(0));
            core.releaseFrame.countDown();
            await(() -> core.completedMasks.size() >= 5);
            assertEquals(List.of(0, 1, 0, 1, 0), List.copyOf(core.completedMasks).subList(0, 5));
            worker.presentControllerFrame(); assertEquals(1, worker.presentedControllerMask(0));
            worker.presentControllerFrame(); assertEquals(0, worker.presentedControllerMask(0));
        } finally { core.releaseFrame.countDown(); worker.close(); }
        await(() -> core.closed);
    }
    @Test void streamLifecycleReleaseDropsPendingTapEvenIfLatestSubmittedMaskWasZero() throws Exception {
        FakeCore core = new FakeCore(); core.blockFrame = true;
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, false, true, "", false);
        try {
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            worker.input(0, 1); worker.input(0, 0); worker.clearInput(0);
            core.releaseFrame.countDown(); await(() -> core.completedMasks.size() >= 4);
            assertTrue(core.completedMasks.stream().allMatch(mask -> mask == 0));
            worker.presentControllerFrame(); assertEquals(0, worker.presentedControllerMask(0));
        } finally { core.releaseFrame.countDown(); worker.close(); }
        await(() -> core.closed);
    }
    @Test void streamPauseDropsUnexecutedEdgesAndCannotPublishInFlightPulse() throws Exception {
        FakeCore core = new FakeCore(); core.blockFrame = true;
        var worker = new ClientNesWorker(() -> { core.claimOwner(); return core; }, false, true, "", false);
        try {
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            worker.input(0, 1); worker.input(0, 0); worker.configure(true, true, false);
            worker.input(0, 2); core.releaseFrame.countDown(); await(() -> core.frames.get() >= 1);
            worker.configure(true, false, false); await(() -> core.completedMasks.size() >= 4);
            assertTrue(core.completedMasks.stream().allMatch(mask -> mask == 0));
        } finally { core.releaseFrame.countDown(); worker.close(); }
        await(() -> core.closed);
    }
    @Test void animationMaskPublishesOnlyCompletedCoreFramesAndClearsForPauseAndClose()throws Exception {
        FakeCore core=new FakeCore();core.blockFrame=true;
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",false);
        try {
            await(worker::isReady);worker.enqueue(new ClientNesWorker.Input(1,0x81,0x22));
            assertTrue(core.frameEntered.await(3,TimeUnit.SECONDS));
            assertEquals(0,worker.appliedControllerMask(0),"queued/half-executed input must not animate");
            core.releaseFrame.countDown();await(()->worker.appliedControllerMask(0)==0x81);
            assertEquals(0x22,worker.appliedControllerMask(1));assertEquals(0,worker.appliedControllerMask(2));
            worker.configure(true,true,false);assertEquals(0,worker.appliedControllerMask(0));
            worker.configure(true,false,false);assertEquals(0,worker.appliedControllerMask(0),"resume waits for a new actual frame");
        }finally {core.releaseFrame.countDown();worker.close();}
        assertEquals(0,worker.appliedControllerMask(1));await(()->core.closed);
    }
    @Test void resetDuringInFlightFrameCannotRepublishOldAnimationMasks()throws Exception {
        FakeCore core=new FakeCore();core.blockFrame=true;
        var worker=new ClientNesWorker(()->{core.claimOwner();return core;},true,true,"",false);
        try {
            await(worker::isReady);worker.enqueue(new ClientNesWorker.Input(1,255,255));
            assertTrue(core.frameEntered.await(3,TimeUnit.SECONDS));
            worker.snapshot(0,new byte[]{3},true);core.releaseFrame.countDown();await(()->core.loads.get()==1);
            assertEquals(0,worker.appliedControllerMask(0));assertEquals(0,worker.appliedControllerMask(1));
        }finally {core.releaseFrame.countDown();worker.close();}
        await(()->core.closed);
    }
    @Test
    void persistentSaveAcceptedDuringFirstFrameCannotBeSilentlySkipped() throws Exception {
        FakeCore core = new FakeCore();
        core.blockFrame = true;
        ClientNesWorker worker = new ClientNesWorker(() -> {
            core.claimOwner();
            core.loadRom(new byte[]{1});
            return core;
        }, true, true, "", false);
        try {
            await(worker::isReady);
            worker.enqueue(new ClientNesWorker.Input(3, 0, 0));
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            worker.snapshot(0, new byte[]{42}, true);
            core.releaseFrame.countDown();
            await(() -> core.loads.get() == 1);
            var result = awaitPicture(worker, 0);
            assertEquals(0, result.picture().frame());
            assertEquals(42, core.loadedByte);
            assertEquals(0, core.frames.get());
            assertNull(core.wrongThread.get());
        } finally {
            core.releaseFrame.countDown();
            worker.close();
        }
        await(() -> core.closed);
    }

    @Test
    void failingFactoryAfterSnapshotChangesGenerationIsReportedOnceWithoutRetryLoop() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        ClientNesWorker worker = new ClientNesWorker(() -> {
            attempts.incrementAndGet();
            entered.countDown();
            if (!fail.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
            throw new IllegalStateException("expected factory failure");
        }, true, true, "", false);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            worker.snapshot(0, new byte[]{42}, true);
            fail.countDown();
            var error = awaitEvent(worker, "error");
            assertTrue(error.message().contains("expected factory failure"));
            assertTrue(worker.diagnostic().startsWith("failed"));
            assertEquals(1, attempts.get());
            assertFalse(worker.requestSnapshot());
            assertFalse(worker.enqueue(new ClientNesWorker.Input(3, 0, 0)));
        } finally {
            fail.countDown();
            worker.close();
        }
    }

    @Test
    void heldStreamInputResampledAfterResetIsNotOverwrittenByDelayedReset() throws Exception {
        CopyOnWriteArrayList<FakeCore> cores = new CopyOnWriteArrayList<>();
        ClientNesWorker worker = new ClientNesWorker(() -> {
            FakeCore core = new FakeCore();
            core.claimOwner();
            core.blockFrame = cores.isEmpty();
            core.loadRom(new byte[]{1});
            cores.add(core);
            return core;
        }, false, true, "", false);
        try {
            await(() -> !cores.isEmpty());
            FakeCore original = cores.getFirst();
            assertTrue(original.frameEntered.await(3, TimeUnit.SECONDS));
            worker.reset();
            worker.input(0, 5);
            original.releaseFrame.countDown();
            await(() -> cores.size() == 2 && cores.get(1).frames.get() > 0);
            assertEquals(5, cores.get(1).oneMask);
        } finally {
            cores.forEach(core -> core.releaseFrame.countDown());
            worker.close();
        }
        await(() -> cores.stream().allMatch(core -> core.closed));
    }

    @Test
    void allCoreOperationsAndFactoriesShareOneBackgroundOwner() throws Exception {
        var first = new AtomicReference<FakeCore>();
        var second = new AtomicReference<FakeCore>();
        try (var a = worker(first, false); var b = worker(second, false)) {
            await(() -> a.isReady() && b.isReady());
            assertNotEquals(Thread.currentThread(), first.get().owner);
            assertSame(first.get().owner, second.get().owner);
            assertTrue(a.enqueue(new ClientNesWorker.Input(3, 1, 2)));
            assertTrue(b.enqueue(new ClientNesWorker.Input(3, 3, 4)));
            await(() -> first.get().frames.get() == 3 && second.get().frames.get() == 3);
            assertTrue(a.requestSnapshot());
            await(() -> first.get().saves.get() == 1);
            assertNull(first.get().wrongThread.get());
            assertNull(second.get().wrongThread.get());
        }
        await(() -> first.get().closed && second.get().closed);
        assertNull(first.get().wrongThread.get());
        assertNull(second.get().wrongThread.get());
    }

    @Test
    void closeDoesNotWaitForRunningFrameAndCancelsQueuedResetAndSnapshot() throws Exception {
        FakeCore core = new FakeCore();
        core.blockFrame = true;
        AtomicInteger constructions = new AtomicInteger();
        ClientNesWorker worker = new ClientNesWorker(() -> {
            constructions.incrementAndGet();
            core.claimOwner();
            core.loadRom(new byte[]{1});
            return core;
        }, true, true, "", false);
        try {
            await(worker::isReady);
            assertTrue(worker.enqueue(new ClientNesWorker.Input(3, 0, 0)));
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            assertTrue(worker.requestSnapshot());
            worker.reset();
            assertTimeout(Duration.ofMillis(100), worker::close);
            assertFalse(core.closed, "native close must wait on owner, not caller");
            core.releaseFrame.countDown();
            await(() -> core.closed);
            assertEquals(1, constructions.get());
            assertEquals(0, core.saves.get());
            assertEquals(1, core.frames.get());
            assertTrue(worker.drain().events().isEmpty());
            assertNull(core.wrongThread.get());
            assertFalse(core.usedAfterClose);
        } finally {
            core.releaseFrame.countDown();
            worker.close();
        }
    }

    @Test
    void resetRejectsOldGenerationFramesAudioAndSnapshotJobs() throws Exception {
        CopyOnWriteArrayList<FakeCore> cores = new CopyOnWriteArrayList<>();
        ClientNesWorker worker = new ClientNesWorker(() -> {
            FakeCore core = new FakeCore();
            core.claimOwner();
            core.blockFrame = cores.isEmpty();
            core.loadRom(new byte[]{1});
            cores.add(core);
            return core;
        }, true, true, "", false);
        try {
            await(worker::isReady);
            FakeCore original = cores.getFirst();
            worker.configure(true, false, true);
            worker.enqueue(new ClientNesWorker.Input(3, 0, 0));
            assertTrue(original.frameEntered.await(3, TimeUnit.SECONDS));
            worker.requestSnapshot();
            long before = worker.generation();
            worker.reset();
            assertTrue(worker.generation() > before);
            original.releaseFrame.countDown();
            await(() -> cores.size() == 2 && cores.getFirst().closed && worker.isReady());
            ClientNesWorker.Delivery result = awaitPicture(worker, 0);
            assertEquals(worker.generation(), result.picture().generation());
            assertEquals(0, result.picture().frame());
            assertTrue(result.audio().isEmpty());
            assertTrue(result.events().stream().noneMatch(e -> e.kind().equals("snapshot")));
            assertEquals(0, original.saves.get());
            assertSame(original.owner, cores.get(1).owner);
            assertFalse(original.usedAfterClose);
        } finally {
            cores.forEach(c -> c.releaseFrame.countDown());
            worker.close();
        }
        await(() -> cores.stream().allMatch(c -> c.closed));
    }

    @Test
    void snapshotLoadAndHistoryAreOrderedAndSaveUsesServerTickBoundary() throws Exception {
        var reference = new AtomicReference<FakeCore>();
        try (var worker = worker(reference, true)) {
            await(worker::isReady);
            assertTrue(worker.enqueue(new ClientNesWorker.Input(30, 0, 0)));
            Thread.sleep(15);
            assertEquals(0, reference.get().frames.get(), "awaiting viewer must not run unsynced input");
            worker.snapshot(300, new byte[]{42}, false);
            assertTrue(worker.history(300, List.of(
                    new ClientNesWorker.Input(303, 1, 2),
                    new ClientNesWorker.Input(306, 4, 8))));
            await(() -> reference.get().frames.get() == 6);
            assertEquals(1, reference.get().loads.get());
            assertEquals(42, reference.get().loadedByte);
            ClientNesWorker.Delivery result = awaitPicture(worker, 306);
            assertEquals(306, result.picture().silentUntil());
            assertTrue(result.audio().isEmpty(), "history is silent");
            assertTrue(worker.requestSnapshot());
            await(() -> reference.get().saves.get() == 1);
            var saved = awaitEvent(worker, "snapshot");
            assertEquals(306, saved.frame());
            assertEquals(0, saved.frame() % 3);
        }
        await(() -> reference.get().closed);
    }

    @Test
    void oneSlowHistoryDoesNotRunSixtyFramesBeforeOtherCore() throws Exception {
        var first = new AtomicReference<FakeCore>();
        var second = new AtomicReference<FakeCore>();
        try (var a = worker(first, false); var b = worker(second, false)) {
            await(() -> a.isReady() && b.isReady());
            first.get().delayMillis = 6;
            second.get().delayMillis = 6;
            a.history(0, List.of(new ClientNesWorker.Input(90, 0, 0)));
            b.history(0, List.of(new ClientNesWorker.Input(90, 0, 0)));
            await(() -> second.get().frames.get() >= 2);
            assertTrue(first.get().frames.get() < 60);
            assertTrue(first.get().frames.get() < 12, "time slices should yield after one slow frame");
        }
        await(() -> first.get().closed && second.get().closed);
    }

    @Test
    void pictureIsLatestOnlyAudioIsBoundedAndArraysAreImmutable() throws Exception {
        var reference = new AtomicReference<FakeCore>();
        try (var worker = worker(reference, false)) {
            await(worker::isReady);
            worker.configure(true, false, true);
            worker.enqueue(new ClientNesWorker.Input(90, 0, 0));
            var observedAudio = new java.util.ArrayList<ClientNesWorker.Audio>();
            var finalDelivery = new AtomicReference<ClientNesWorker.Delivery>();
            await(() -> {
                var delivered = worker.drain();
                assertTrue(delivered.audio().size() <= ClientNesWorker.MAX_AUDIO);
                observedAudio.addAll(delivered.audio());
                if (delivered.picture() == null || delivered.picture().frame() != 90) return false;
                finalDelivery.set(delivered);
                return true;
            });
            ClientNesWorker.Delivery result = finalDelivery.get();
            assertEquals(90, result.picture().frame());
            assertTrue(result.audio().size() <= ClientNesWorker.MAX_AUDIO);
            assertFalse(observedAudio.isEmpty());
            byte[] pixels = result.picture().rgba();
            pixels[0] = 0;
            assertEquals(90, Byte.toUnsignedInt(result.picture().rgba()[0]));
            float[] audio = observedAudio.getFirst().samples();
            audio[0] = -100;
            assertNotEquals(-100, observedAudio.getFirst().samples()[0]);
            assertNull(worker.drain().picture());
            assertTrue(worker.drain().audio().isEmpty());
        }
        await(() -> reference.get().closed);
    }

    @Test
    void commandsAndHistoryAreBoundedWhileOwnerIsBusy() throws Exception {
        FakeCore core = new FakeCore();
        core.blockFrame = true;
        ClientNesWorker worker = new ClientNesWorker(() -> {
            core.claimOwner();
            core.loadRom(new byte[]{1});
            return core;
        }, true, true, "", false);
        try {
            await(worker::isReady);
            worker.enqueue(new ClientNesWorker.Input(3, 0, 0));
            assertTrue(core.frameEntered.await(3, TimeUnit.SECONDS));
            int accepted = 0;
            for (int i = 0; i < 1000; i++) {
                if (worker.enqueue(new ClientNesWorker.Input(6L + i * 3L, 0, 0))) accepted++;
            }
            assertEquals(ClientNesWorker.MAX_COMMANDS, accepted);
            assertFalse(worker.history(0, java.util.Collections.nCopies(
                    ClientNesWorker.MAX_INPUTS + 1, new ClientNesWorker.Input(3, 0, 0))));
        } finally {
            worker.close();
            core.releaseFrame.countDown();
        }
        await(() -> core.closed);
        assertFalse(core.usedAfterClose);
    }

    private static ClientNesWorker worker(AtomicReference<FakeCore> reference, boolean awaiting) {
        return new ClientNesWorker(() -> {
            FakeCore core = new FakeCore();
            core.claimOwner();
            core.loadRom(new byte[]{1});
            reference.set(core);
            return core;
        }, true, true, "", awaiting);
    }

    private static ClientNesWorker.Delivery awaitPicture(ClientNesWorker worker, long frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            var result = worker.drain();
            if (result.picture() != null && result.picture().frame() == frame) return result;
            Thread.sleep(2);
        }
        fail("No picture at frame " + frame + ": " + worker.diagnostic());
        return null;
    }

    private static ClientNesWorker.Event awaitEvent(ClientNesWorker worker, String kind) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            for (var event : worker.drain().events()) if (event.kind().equals(kind)) return event;
            Thread.sleep(2);
        }
        fail("No event " + kind + ": " + worker.diagnostic());
        return null;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            // A mailbox poll consumes its delivery. Do not evaluate a successful
            // poll again in the assertion and accidentally discard that success.
            if (condition.getAsBoolean()) return;
            Thread.sleep(2);
        }
        fail("condition timed out");
    }

    private static class FakeCore implements NesCore {
        final AtomicInteger frames = new AtomicInteger();
        final CopyOnWriteArrayList<Integer> completedMasks = new CopyOnWriteArrayList<>();
        final AtomicInteger saves = new AtomicInteger();
        final AtomicInteger loads = new AtomicInteger();
        final AtomicReference<Thread> wrongThread = new AtomicReference<>();
        final CountDownLatch frameEntered = new CountDownLatch(1);
        final CountDownLatch releaseFrame = new CountDownLatch(1);
        volatile Thread owner;
        volatile boolean closed;
        volatile boolean usedAfterClose;
        volatile boolean blockFrame;
        volatile int delayMillis;
        volatile int loadedByte;
        volatile int oneMask;
        void claimOwner() { owner = Thread.currentThread(); }
        void check() {
            if (Thread.currentThread() != owner) wrongThread.set(Thread.currentThread());
            if (closed) usedAfterClose = true;
        }
        @Override public void loadRom(byte[] rom) { check(); }
        @Override public void reset() { check(); frames.set(0); }
        @Override public void setControllerState(int player, int mask) {
            check();
            if (player == 0) oneMask = mask;
        }
        @Override public void runFrame() {
            check();
            frameEntered.countDown();
            try {
                if (blockFrame) releaseFrame.await(4, TimeUnit.SECONDS);
                if (delayMillis > 0) Thread.sleep(delayMillis);
            } catch (InterruptedException error) { throw new RuntimeException(error); }
            completedMasks.add(oneMask);
            frames.incrementAndGet();
        }
        @Override public void copyFrameRgba(byte[] target) {
            check();
            java.util.Arrays.fill(target, (byte) frames.get());
        }
        @Override public int copyAudioSamples(float[] target) {
            check(); target[0] = frames.get(); return 1;
        }
        @Override public void copyCpuRam(byte[] target) { check(); }
        @Override public byte[] saveTransientState() {
            check(); saves.incrementAndGet(); return new byte[]{(byte) frames.get()};
        }
        @Override public void loadTransientState(byte[] state) {
            check(); loads.incrementAndGet(); loadedByte = Byte.toUnsignedInt(state[0]); frames.set(0);
        }
        @Override public void close() { check(); closed = true; }
    }
}
