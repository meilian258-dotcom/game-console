package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStartupStateTest {
    private static Path root() { return Path.of("runtime-startup-fixture-" + UUID.randomUUID()); }
    private static RuntimeInstaller.Report report(RuntimeInstaller.Outcome outcome) {
        return new RuntimeInstaller.Report(outcome, "inert report", List.of(), RuntimeInstaller.PackState.MISSING, List.of(), 0, 0);
    }

    @Test void unknownRootIsEmptyWithoutCreatingIt() {
        Path root = root();
        assertTrue(RuntimeStartupState.report(root).isEmpty());
        assertTrue(RuntimeStartupState.failure(root).isEmpty());
        assertFalse(java.nio.file.Files.exists(root));
    }

    @Test void relativeAndLexicallyEquivalentPathsShareOnlyTheirOwnReport() {
        Path root = root(); var failed = report(RuntimeInstaller.Outcome.FAILED);
        RuntimeStartupState.recordReport(root.resolve("child/.."), failed);
        assertSame(failed, RuntimeStartupState.failure(root.toAbsolutePath()).orElseThrow());
        assertTrue(RuntimeStartupState.failure(root.resolve("other")).isEmpty());
        assertFalse(java.nio.file.Files.exists(root));
    }

    @Test void successfulReplacementClearsFailureWithoutDiscardingReport() {
        Path root = root(); RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.BLOCKED));
        assertTrue(RuntimeStartupState.failure(root).isPresent());
        for (var outcome : List.of(RuntimeInstaller.Outcome.READY, RuntimeInstaller.Outcome.INSTALLED)) {
            var ready = report(outcome); RuntimeStartupState.recordReport(root, ready);
            assertTrue(RuntimeStartupState.failure(root).isEmpty());
            assertSame(ready, RuntimeStartupState.report(root).orElseThrow());
        }
    }

    @Test void storedReportListsRemainImmutable() {
        Path root = root(); var details = new ArrayList<>(List.of("original"));
        var value = new RuntimeInstaller.Report(RuntimeInstaller.Outcome.BLOCKED, "blocked", List.of(), RuntimeInstaller.PackState.INVALID, details, 0, 0);
        RuntimeStartupState.recordReport(root, value); details.clear();
        var stored = RuntimeStartupState.failure(root).orElseThrow();
        assertEquals(List.of("original"), stored.details());
        assertThrows(UnsupportedOperationException.class, () -> stored.details().clear());
        assertThrows(UnsupportedOperationException.class, () -> stored.runtimes().clear());
    }

    @Test void everyNonReadyOutcomeIsAvailableToTheTitleDiagnostic() {
        Path root = root();
        for (var outcome : RuntimeInstaller.Outcome.values()) {
            RuntimeStartupState.recordReport(root, report(outcome));
            assertEquals(outcome != RuntimeInstaller.Outcome.READY && outcome != RuntimeInstaller.Outcome.INSTALLED,
                    RuntimeStartupState.failure(root).isPresent(), outcome.name());
        }
    }
}
