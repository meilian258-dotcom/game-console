package cn.piq.retro.api;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetroRegistryTest {
    @Test void metadataIsIndependentOfFactoriesAndRegistrationOrderIsStable() {
        var registry = new RetroBackendRegistry();
        var factories = new RetroFactoryRegistry();
        assertEquals(List.of(), registry.entries());
        assertNull(registry.find("piq_fc_arcade:nes")); // no hardcoded default core
        registry.register("piq_sfc_home:sfc", "SFC / Super Famicom", true);
        var snapshot = registry.entries();
        registry.register("piq_native_arcade:mame", "MAME", true);
        assertNull(factories.find("piq_sfc_home:sfc"));
        assertEquals(1, snapshot.size());
        assertEquals(List.of("piq_sfc_home:sfc", "piq_native_arcade:mame"), registry.entries().stream().map(RetroBackendRegistry.Descriptor::id).toList());
        assertTrue(registry.find("piq_sfc_home:sfc").localOnly());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertNull(registry.find("missing:core"));
    }

    @Test void validatesIdentifiersAndMetadataAtConstructionAndRegistration() {
        var registry = new RetroBackendRegistry();
        for (String id : List.of("", "nes", ":nes", "piq:", "PIQ:nes", "piq:n es", "piq:nes:other", "piq:" + "a".repeat(125)))
            assertThrows(IllegalArgumentException.class, () -> registry.register(id, "NES", false));
        assertThrows(NullPointerException.class, () -> registry.register(null, "NES", false));
        for (String name : List.of("", "  ", "a\nb", "a\u0000b", "a".repeat(65)))
            assertThrows(IllegalArgumentException.class, () -> new RetroBackendRegistry.Descriptor("piq:nes", name, false));
        assertThrows(IllegalArgumentException.class, () -> registry.register("piq:nes", null, false));
        registry.register("piq:" + "a".repeat(124), "a".repeat(64), false);
        assertEquals(1, registry.entries().size());
    }

    @Test void boundedTablesRejectDuplicatesWithoutReplacingOriginals() {
        var registry = new RetroBackendRegistry();
        var factories = new RetroFactoryRegistry();
        RetroEmulatorFactory original = path -> null;
        for (int i = 0; i < 16; i++) {
            registry.register("piq:core" + i, "Core " + i, false);
            factories.register("piq:core" + i, original);
        }
        assertThrows(IllegalArgumentException.class, () -> registry.register("piq:core0", "Replacement", true));
        assertThrows(IllegalArgumentException.class, () -> factories.register("piq:core0", path -> null));
        assertThrows(IllegalStateException.class, () -> registry.register("piq:core16", "Overflow", false));
        assertThrows(IllegalStateException.class, () -> factories.register("piq:core16", original));
        assertEquals("Core 0", registry.find("piq:core0").displayName());
        assertSame(original, factories.find("piq:core0"));
        assertEquals(16, registry.entries().size());
        assertEquals(16, factories.ids().size());
    }

    @Test void factoryLookupNeverOpensCoreAndPreservesExactFileAndFailure() throws Exception {
        var registry = new RetroFactoryRegistry();
        var opened = new AtomicInteger();
        Path chosen = Path.of("chosen-game.sfc");
        Exception failure = new Exception("provider-specific validation failure");
        RetroEmulatorFactory factory = path -> { assertSame(chosen, path); opened.incrementAndGet(); throw failure; };
        assertThrows(NullPointerException.class, () -> registry.register("piq:sfc", null));
        assertThrows(IllegalArgumentException.class, () -> registry.register("unqualified", factory));
        registry.register("piq:sfc", factory);
        var snapshot = registry.ids();
        assertSame(factory, registry.find("piq:sfc"));
        assertEquals(0, opened.get());
        assertSame(failure, assertThrows(Exception.class, () -> registry.find("piq:sfc").open(chosen)));
        assertEquals(1, opened.get());
        registry.register("piq:nes", path -> null);
        assertEquals(List.of("piq:sfc"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("piq:other"));
        assertNull(registry.find("missing:core"));
    }

    @Test void independentRegistryInstancesNeverLeakState() {
        var first = new RetroBackendRegistry();
        var second = new RetroBackendRegistry();
        first.register("piq:nes", "NES", false);
        assertNull(second.find("piq:nes"));
        var firstFactories = new RetroFactoryRegistry();
        var secondFactories = new RetroFactoryRegistry();
        firstFactories.register("piq:nes", path -> null);
        assertNull(secondFactories.find("piq:nes"));
    }

    @Test void concurrentDuplicateHasExactlyOneWinner() throws Exception {
        var registry = new RetroBackendRegistry();
        var factories = new RetroFactoryRegistry();
        var start = new CountDownLatch(1);
        var winners = new AtomicInteger();
        var factoryWinners = new AtomicInteger();
        var failures = new AtomicInteger();
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    try { registry.register("piq:nes", "NES", false); winners.incrementAndGet(); }
                    catch (IllegalArgumentException duplicate) { failures.incrementAndGet(); }
                    try { factories.register("piq:nes", path -> null); factoryWinners.incrementAndGet(); }
                    catch (IllegalArgumentException duplicate) { failures.incrementAndGet(); }
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) { thread.join(5000); assertFalse(thread.isAlive()); }
        assertEquals(1, winners.get());
        assertEquals(1, factoryWinners.get());
        assertEquals(14, failures.get());
    }
}
