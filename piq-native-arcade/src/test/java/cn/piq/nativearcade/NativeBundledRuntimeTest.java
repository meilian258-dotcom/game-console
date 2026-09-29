package cn.piq.nativearcade;

import cn.piq.fcarcade.runtime.RuntimeInstaller;
import cn.piq.fcarcade.runtime.RuntimeStartupState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** No production prepare(), resources, DLLs, installation or core processes are invoked. */
class NativeBundledRuntimeTest {
    private static Path root() { return Path.of("native-bundle-inert-" + UUID.randomUUID()); }
    private static RuntimeInstaller.Report result(RuntimeInstaller.Outcome outcome) {
        return new RuntimeInstaller.Report(outcome, "fixture", List.of(), RuntimeInstaller.PackState.MISSING, List.of("fixture detail"), 0, 0);
    }

    @Test void platformAndOrdinaryBridgeArchitectureBothGuardTheInstaller() {
        var calls = new AtomicInteger();
        for (boolean supported : new boolean[]{false, true}) for (String architecture : new String[]{"amd64", "x86_64", "aarch64"}) {
            Path root = root();
            var report = NativeBundledRuntime.prepare(root, supported, architecture, () -> { calls.incrementAndGet(); return result(RuntimeInstaller.Outcome.READY); });
            assertEquals(supported && architecture.equals("amd64"), report.ready());
            assertSame(report, RuntimeStartupState.report(root).orElseThrow());
            assertFalse(java.nio.file.Files.exists(root));
        }
        assertEquals(1, calls.get());
    }

    @Test void successfulAndFailedInstallerReportsArePreservedExactly() {
        for (var outcome : List.of(RuntimeInstaller.Outcome.INSTALLED, RuntimeInstaller.Outcome.BLOCKED, RuntimeInstaller.Outcome.BUSY, RuntimeInstaller.Outcome.CANCELLED)) {
            Path root = root(); var value = result(outcome);
            assertSame(value, NativeBundledRuntime.prepare(root, true, "amd64", () -> value));
            assertSame(value, RuntimeStartupState.report(root).orElseThrow());
        }
    }

    @Test void ioFailureBecomesUsefulDiagnosticInsteadOfEscapingModLoading() {
        Path root = root();
        var report = NativeBundledRuntime.prepare(root, true, "amd64", () -> { throw new IOException("inert bad bundle"); });
        assertEquals(RuntimeInstaller.Outcome.FAILED, report.outcome());
        assertTrue(report.details().getFirst().contains("inert bad bundle"));
        assertSame(report, RuntimeStartupState.failure(root).orElseThrow());
    }

    @Test void linkageFailureAndMissingReportAlsoRemainNonFatal() {
        assertEquals(RuntimeInstaller.Outcome.FAILED, NativeBundledRuntime.prepare(root(), true, "amd64", () -> { throw new NoClassDefFoundError("inert fixture"); }).outcome());
        assertEquals(RuntimeInstaller.Outcome.FAILED, NativeBundledRuntime.prepare(root(), true, "amd64", () -> null).outcome());
    }

    @Test void interruptedStartupDoesNotCallInstallerAndKeepsInterrupt() {
        var calls = new AtomicInteger(); Thread.currentThread().interrupt();
        try {
            var report = NativeBundledRuntime.prepare(root(), true, "amd64", () -> { calls.incrementAndGet(); return result(RuntimeInstaller.Outcome.READY); });
            assertEquals(RuntimeInstaller.Outcome.CANCELLED, report.outcome());
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, calls.get());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedAndCancelledWorkAreNotReportedAsInstalled() {
        try {
            var report = NativeBundledRuntime.prepare(root(), true, "amd64", () -> { throw new InterruptedException("fixture"); });
            assertEquals(RuntimeInstaller.Outcome.CANCELLED, report.outcome()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(RuntimeInstaller.Outcome.CANCELLED, NativeBundledRuntime.prepare(root(), true, "amd64", () -> { throw new CancellationException(); }).outcome());
    }
}
