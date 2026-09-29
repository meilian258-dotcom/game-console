package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomRepository;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeTransferTest {
    @Test void acceptsAccumulatedLowTpsBurstWithoutAnArrivalTickQuota() {
        int chunks = 256;
        CartridgeTransfer transfer = new CartridgeTransfer(chunks * CartridgeLimits.CHUNK_BYTES, 10);
        byte[] chunk = new byte[CartridgeLimits.CHUNK_BYTES]; chunk[0] = 19;
        for (int i = 0; i < chunks; i++) transfer.append(i * chunk.length, chunk);
        byte[] done = transfer.finish(); assertEquals(chunks * chunk.length, done.length);
        for (int i = 0; i < chunks; i++) assertEquals(19, done[i * chunk.length]);
    }
    @Test void rejectsEmptyOversizedAndNonSequentialPartsBeforeCopying() {
        assertThrows(IllegalArgumentException.class, () -> new CartridgeTransfer(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CartridgeTransfer(RomRepository.MAX_ROM_BYTES + 1, 0));
        CartridgeTransfer transfer = new CartridgeTransfer(3, 0);
        assertThrows(IllegalArgumentException.class, () -> transfer.append(1, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> transfer.append(0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> transfer.append(0, new byte[CartridgeLimits.CHUNK_BYTES + 1]));
        transfer.append(0, new byte[]{1, 2});
        assertThrows(IllegalArgumentException.class, () -> transfer.append(0, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> transfer.append(2, new byte[]{3, 4}));
        assertEquals(2, transfer.received());
    }
    @Test void partsAreCopiedAndCompletedOwnershipCannotBeReused() {
        CartridgeTransfer transfer = new CartridgeTransfer(2, 0); byte[] input = {5, 6}; transfer.append(0, input); input[0] = 99;
        assertArrayEquals(new byte[]{5, 6}, transfer.finish());
        assertThrows(IllegalStateException.class, transfer::finish);
        assertThrows(IllegalArgumentException.class, () -> transfer.append(2, new byte[]{7}));
    }
    @Test void timeoutIsAbsoluteAndDoesNotDependOnTicksOrKeepaliveChunks() {
        CartridgeTransfer transfer = new CartridgeTransfer(2, 100);
        transfer.append(0, new byte[]{1});
        assertFalse(transfer.expired(100 + CartridgeLimits.TRANSFER_TIMEOUT_NANOS));
        assertTrue(transfer.expired(101 + CartridgeLimits.TRANSFER_TIMEOUT_NANOS));
        assertThrows(IllegalStateException.class, transfer::finish);
    }
    @Test void budgetEnforcesCountBytesDuplicateReservationAndIdempotentRelease() {
        CartridgeTransferBudget budget = new CartridgeTransferBudget(); UUID first = UUID.randomUUID();
        assertTrue(budget.reserve(first, 1)); assertFalse(budget.reserve(first, 1));
        for (int i = 1; i < CartridgeLimits.MAX_TRANSFERS; i++) assertTrue(budget.reserve(UUID.randomUUID(), 1));
        assertFalse(budget.reserve(UUID.randomUUID(), 1)); budget.release(first); budget.release(first);
        assertEquals(CartridgeLimits.MAX_TRANSFERS - 1, budget.reservedBytes());
    }
    @Test void concurrentReservationsNeverExceed64MiBAndCanBeFullyReleased() throws Exception {
        CartridgeTransferBudget budget = new CartridgeTransferBudget(); CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(20);
        AtomicInteger accepted = new AtomicInteger(); List<UUID> ids = new ArrayList<>(); List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID(); ids.add(id);
            Thread thread = new Thread(() -> { try { start.await(); if (budget.reserve(id, 32 * 1024 * 1024)) accepted.incrementAndGet(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
            threads.add(thread); thread.start();
        }
        start.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS));
        for (Thread thread : threads) thread.join();
        assertEquals(2, accepted.get()); assertEquals(CartridgeLimits.MAX_RESERVED_BYTES, budget.reservedBytes());
        ids.forEach(budget::release); assertEquals(0, budget.reservedBytes()); assertEquals(0, budget.count());
    }
}
