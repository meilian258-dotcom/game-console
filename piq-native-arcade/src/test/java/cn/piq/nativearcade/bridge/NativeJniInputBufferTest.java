package cn.piq.nativearcade.bridge;

import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeJniInputBufferTest {
    private static void offer(NativeJniInputBuffer q, int... pads) {
        assertTrue(q.offer(pads[0], pads[1], pads[2], pads[3]));
    }
    @Test void independentEdgesCombineIntoAnActuallySubmittedState() {
        var q = new NativeJniInputBuffer();
        offer(q, 1, 0, 0, 0); offer(q, 3, 0, 0, 0); offer(q, 11, 0, 0, 0);
        assertArrayEquals(new int[]{11, 0, 0, 0}, q.nextFrame());
        assertEquals(2, q.snapshot().mergedStates());
        assertEquals(0, q.snapshot().ports().getFirst().pending());
    }
    @Test void everyBitShortTapRetainsItsPressAndReleaseFrames() {
        for (int port = 0; port < 4; port++) for (int bit = 0; bit < 16; bit++) {
            var q = new NativeJniInputBuffer(); int[] pads = new int[4]; pads[port] = 1 << bit;
            offer(q, pads); offer(q, 0, 0, 0, 0);
            assertArrayEquals(pads, q.nextFrame()); assertArrayEquals(new int[4], q.nextFrame());
        }
    }
    @Test void pressReleasePressNeverCollapsesOrSynthesizesAChord() {
        var q = new NativeJniInputBuffer();
        offer(q, 1, 0, 0, 0); offer(q, 0, 0, 0, 0); offer(q, 2, 0, 0, 0); offer(q, 3, 0, 0, 0);
        assertEquals(1, q.nextFrame()[0]);
        assertEquals(2, q.nextFrame()[0]); // Release A and press B, not an OR-merged A+B.
        assertEquals(3, q.nextFrame()[0]);
        assertEquals(3, q.nextFrame()[0]);
    }
    @Test void heldButtonOverlapIsVisibleBeforeItsRelease() {
        var q = new NativeJniInputBuffer(); offer(q, 1, 0, 0, 0);
        assertEquals(1, q.nextFrame()[0]);
        offer(q, 3, 0, 0, 0); offer(q, 2, 0, 0, 0);
        assertEquals(3, q.nextFrame()[0]); assertEquals(2, q.nextFrame()[0]);
    }
    @Test void neutralToAThenABThenBPreservesTheOverlappingChord() {
        var q = new NativeJniInputBuffer();
        offer(q, 1, 0, 0, 0); offer(q, 3, 0, 0, 0); offer(q, 2, 0, 0, 0);
        assertEquals(3, q.nextFrame()[0]); assertEquals(2, q.nextFrame()[0]);
        assertEquals(1, q.snapshot().mergedStates());
    }
    @Test void coinAndAttackOverlapAndBothCoinPulsesRemainVisible() {
        var q = new NativeJniInputBuffer(); offer(q, 1, 0, 0, 0); q.nextFrame();
        offer(q, 5, 0, 0, 0); offer(q, 4, 0, 0, 0); offer(q, 0, 0, 0, 0);
        offer(q, 4, 0, 0, 0); offer(q, 5, 0, 0, 0); offer(q, 1, 0, 0, 0);
        assertEquals(5, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
        assertEquals(5, q.nextFrame()[0]); assertEquals(1, q.nextFrame()[0]);
    }
    @Test void realCabinetCoinPulsesInterleavedWithAttackAndStartStayDistinctOnEveryPort() {
        for (int port = 0; port < 4; port++) {
            var q = new NativeJniInputBuffer(); int[] pads = {16, 32, 64, 128}; pads[port] = 1;
            offer(q, pads); q.nextFrame();
            for (int[] pulse : cn.piq.fcarcade.cabinet.CabinetCoinPolicy.pulse(pads, port)) offer(q, pulse);
            pads[port] = 9; offer(q, pads);
            for (int[] pulse : cn.piq.fcarcade.cabinet.CabinetCoinPolicy.pulse(pads, port)) offer(q, pulse);
            pads[port] = 8; offer(q, pads); pads[port] = 0; offer(q, pads);
            for (int expected : new int[]{5, 9, 13, 0}) {
                int[] actual = q.nextFrame(); assertEquals(expected, actual[port]);
                for (int p = 0; p < 4; p++) if (p != port) assertEquals(pads[p], actual[p]);
            }
            assertArrayEquals(pads, q.nextFrame());
        }
    }
    @Test void quarterCircleKeepsDownDiagonalAndRightAsThreeDirectionSamples() {
        var q = new NativeJniInputBuffer();
        offer(q, 32, 0, 0, 0); offer(q, 160, 0, 0, 0); offer(q, 128, 0, 0, 0);
        assertEquals(32, q.nextFrame()[0]); assertEquals(160, q.nextFrame()[0]); assertEquals(128, q.nextFrame()[0]);
    }
    @Test void directionalReversalKeepsItsCapturedNeutralFrame() {
        var q = new NativeJniInputBuffer();
        offer(q, 64, 0, 0, 0); offer(q, 0, 0, 0, 0); offer(q, 128, 0, 0, 0);
        assertEquals(64, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]); assertEquals(128, q.nextFrame()[0]);
    }
    @Test void pureButtonsMayCombineWithOneDirectionButNeverSwallowTheNextDiagonal() {
        var q = new NativeJniInputBuffer();
        offer(q, 32, 0, 0, 0); offer(q, 33, 0, 0, 0); offer(q, 161, 0, 0, 0); offer(q, 163, 0, 0, 0); offer(q, 131, 0, 0, 0);
        assertEquals(33, q.nextFrame()[0]); assertEquals(163, q.nextFrame()[0]); assertEquals(131, q.nextFrame()[0]);
        assertEquals(2, q.snapshot().mergedStates());
    }
    @Test void directlyCapturedDiagonalDoesNotInventIntermediateDirections() {
        var q = new NativeJniInputBuffer();
        offer(q, 160, 0, 0, 0); offer(q, 96, 0, 0, 0); offer(q, 0, 0, 0, 0);
        assertEquals(160, q.nextFrame()[0]); assertEquals(96, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
    }
    @Test void allFourPortsProgressIndependently() {
        var q = new NativeJniInputBuffer();
        offer(q, 1, 2, 4, 8); offer(q, 0, 2, 4, 8); offer(q, 0, 3, 12, 24);
        assertArrayEquals(new int[]{1, 3, 12, 24}, q.nextFrame());
        assertArrayEquals(new int[]{0, 3, 12, 24}, q.nextFrame());
    }
    @Test void repeatedMasksDoNotFillTheQueue() {
        var q = new NativeJniInputBuffer();
        for (int i = 0; i < 10_000; i++) offer(q, 1, 2, 3, 4);
        for (var port : q.snapshot().ports()) assertEquals(1, port.pending());
    }
    @Test void overflowingOnePortRejectsAllPortsAndLatestStatesAtomically() {
        var q = new NativeJniInputBuffer();
        for (int i = 0; i < 128; i++) offer(q, (i & 1) == 0 ? 1 : 0, 0, 0, 0);
        assertFalse(q.offer(1, 2, 4, 8));
        assertEquals(128, q.snapshot().ports().get(0).pending());
        for (int p = 1; p < 4; p++) assertEquals(0, q.snapshot().ports().get(p).pending());
        assertArrayEquals(new int[]{1, 0, 0, 0}, q.nextFrame());
        offer(q, 1, 2, 4, 8);
        assertEquals(128, q.snapshot().ports().get(0).pending());
        assertArrayEquals(new int[]{0, 2, 4, 8}, q.nextFrame());
    }
    @Test void invalidMasksAndPortsDoNotMutateState() {
        var q = new NativeJniInputBuffer(); offer(q, 1, 2, 4, 8);
        assertThrows(IllegalArgumentException.class, () -> q.offer(0, 0, 65536, 0));
        assertThrows(IllegalArgumentException.class, () -> q.offer(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> q.releasePort(-1));
        assertThrows(IllegalArgumentException.class, () -> q.releaseGameplayPortKeepingCoin(4));
        assertArrayEquals(new int[]{1, 2, 4, 8}, q.nextFrame());
    }
    @Test void hardReleaseInvalidatesOnlyTheSelectedPort() {
        for (int port = 0; port < 4; port++) {
            var q = new NativeJniInputBuffer(); offer(q, 1, 2, 4, 8); q.nextFrame();
            offer(q, 0, 0, 0, 0); offer(q, 1, 2, 4, 8); q.releasePort(port);
            assertArrayEquals(new int[4], q.nextFrame());
            int[] expected = {1, 2, 4, 8}; expected[port] = 0;
            assertArrayEquals(expected, q.nextFrame());
        }
    }
    @Test void focusReleaseRetainsEveryAcceptedCoinPulseButNoGameplay() {
        var q = new NativeJniInputBuffer();
        offer(q, 1, 32, 0, 0); q.nextFrame();
        offer(q, 5, 32, 0, 0); offer(q, 7, 32, 0, 0); offer(q, 2, 32, 0, 0);
        offer(q, 6, 32, 0, 0); offer(q, 0, 32, 0, 0);
        q.releaseGameplayPortKeepingCoin(0);
        for (int expected : new int[]{4, 0, 4, 0}) assertArrayEquals(new int[]{expected, 32, 0, 0}, q.nextFrame());
        assertArrayEquals(new int[]{0, 32, 0, 0}, q.nextFrame());
    }
    @Test void heldCoinReleaseAndSubsequentCoinAreNotReplayedOrLost() {
        var q = new NativeJniInputBuffer(); offer(q, 5, 0, 0, 0); q.nextFrame();
        offer(q, 1, 0, 0, 0); offer(q, 5, 0, 0, 0); offer(q, 1, 0, 0, 0);
        q.releaseGameplayPortKeepingCoin(0);
        assertEquals(0, q.nextFrame()[0]); assertEquals(4, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
    }
    @Test void focusReleaseIsIdempotentAndCoinSafeForEveryPort() {
        for (int port = 0; port < 4; port++) {
            var q = new NativeJniInputBuffer(); int[] masks = {16, 32, 64, 128};
            for (int coin : new int[]{4, 0, 4, 0}) { masks[port] = 65531 | coin; offer(q, masks); }
            q.releaseGameplayPortKeepingCoin(port); q.releaseGameplayPortKeepingCoin(port);
            for (int coin : new int[]{4, 0, 4, 0}) {
                int[] actual = q.nextFrame(); assertEquals(coin, actual[port]);
                for (int p = 0; p < 4; p++) if (p != port) assertEquals(masks[p], actual[p]);
            }
        }
    }
    @Test void clearStartsNeutralAndKeepsHistoricalBacklogVisible() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        offer(q, 1, 2, 4, 8); offer(q, 0, 0, 0, 0);
        clock.set(6_000_000_000L); q.clear();
        assertArrayEquals(new int[4], q.nextFrame());
        for (var p : q.snapshot().ports()) {
            assertEquals(0, p.pending()); assertEquals(0, p.oldestWaitMillis());
            assertEquals(2, p.peakPending()); assertEquals(6000, p.maximumWaitMillis());
        }
        offer(q, 16, 0, 0, 0); assertEquals(16, q.nextFrame()[0]);
    }
    @Test void coinProjectionKeepsTheOriginalCoinTimestamp() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        offer(q, 1, 0, 0, 0); clock.set(100_000_000L); offer(q, 5, 0, 0, 0);
        clock.set(200_000_000L); offer(q, 1, 0, 0, 0); clock.set(300_000_000L);
        q.releaseGameplayPortKeepingCoin(0);
        assertEquals(200, q.snapshot().ports().getFirst().oldestWaitMillis());
        assertEquals(4, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
    }
    @Test void diagnosticReadsAndReturnedFramesCannotChangeInputs() {
        var clock = new AtomicLong(); var q = new NativeJniInputBuffer(clock::get);
        offer(q, 1, 0, 0, 0); offer(q, 0, 0, 0, 0); clock.set(300_000_000L);
        var before = q.snapshot();
        for (int i = 0; i < 100; i++) assertEquals(before, q.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> before.ports().clear());
        int[] out = q.nextFrame(); assertEquals(1, out[0]); out[0] = 65535;
        assertEquals(0, q.nextFrame()[0]); assertEquals(0, q.nextFrame()[0]);
    }
    @Test void slowConsumerAvoidsSixSecondCrossButtonSerializationWithoutDroppingEdges() {
        var clock = new AtomicLong(); var legacy = new NativeInputPorts(); var q = new NativeJniInputBuffer(clock::get);
        int held = 0;
        for (int millis = 25; millis <= 6000; millis += 25) {
            clock.set(millis * 1_000_000L); held ^= 1 << ((millis / 25 - 1) % 4);
            assertTrue(legacy.offer(held, 0, 0, 0)); offer(q, held, 0, 0, 0);
            if (millis % 50 == 0) { legacy.nextFrame(); q.nextFrame(); }
        }
        assertEquals(120, legacy.pending(0)); assertEquals(0, q.snapshot().ports().getFirst().pending());
        assertTrue(legacy.offer(held | 32768, 0, 0, 0)); offer(q, held | 32768, 0, 0, 0);
        int oldFrames = 0;
        while ((legacy.nextFrame()[0] & 32768) == 0) oldFrames++;
        assertEquals(121, oldFrames + 1); // 6.05 seconds at 20 fps.
        assertEquals(32768, q.nextFrame()[0] & 32768); // The next emulated frame, not a claim of a faster core.
        assertEquals(120, q.snapshot().mergedStates());
    }
    @Test void repeatedSameBitOverloadIsNotHiddenByDroppingShortTaps() {
        var q = new NativeJniInputBuffer();
        for (int i = 0; i < 128; i++) offer(q, (i & 1) == 0 ? 1 : 0, 0, 0, 0);
        for (int i = 0; i < 128; i++) assertEquals((i & 1) == 0 ? 1 : 0, q.nextFrame()[0]);
        assertEquals(0, q.snapshot().mergedStates());
    }
    @Test void normalSixtyFpsConsumptionMatchesLegacyFrameForFrame() {
        var legacy = new NativeInputPorts(); var q = new NativeJniInputBuffer(); int mask = 0;
        // One tick is 1/120 s: input changes at 40 Hz, the core samples at 60 Hz.
        for (int tick = 1; tick <= 720; tick++) {
            if (tick % 3 == 0) {
                mask ^= 1 << ((tick / 3 - 1) % 4);
                assertTrue(legacy.offer(mask, mask << 4, mask << 8, mask << 12));
                offer(q, mask, mask << 4, mask << 8, mask << 12);
            }
            if (tick % 2 == 0) assertArrayEquals(legacy.nextFrame(), q.nextFrame());
        }
        assertEquals(0, q.snapshot().mergedStates());
    }
    @Test void randomizedTraceRetainsEveryBitsTransitionCountAndFinalState() {
        var random = new Random(72503);
        for (int round = 0; round < 100; round++) {
            var q = new NativeJniInputBuffer(); int[] last = new int[4];
            int[][] expected = new int[4][16], observed = new int[4][16];
            var submitted = new ArrayList<int[]>();
            for (int event = 0; event < 120; event++) {
                int[] masks = last.clone(); int p = random.nextInt(4); masks[p] ^= 1 << random.nextInt(16);
                count(expected, last, masks); offer(q, masks); submitted.add(masks); last = masks;
            }
            int[] applied = new int[4]; int frames = 0;
            while (q.snapshot().ports().stream().anyMatch(p -> p.pending() > 0)) {
                int[] next = q.nextFrame(); count(observed, applied, next);
                for (int p = 0; p < 4; p++) {
                    final int port = p, mask = next[p];
                    assertTrue(mask == 0 || submitted.stream().anyMatch(s -> s[port] == mask));
                }
                applied = next; assertTrue(++frames <= 120);
            }
            for (int p = 0; p < 4; p++) assertArrayEquals(expected[p], observed[p]);
            assertArrayEquals(last, applied);
        }
    }
    private static void count(int[][] counts, int[] before, int[] after) {
        for (int p = 0; p < 4; p++) for (int bit = 0; bit < 16; bit++)
            if (((before[p] ^ after[p]) & (1 << bit)) != 0) counts[p][bit]++;
    }
}
