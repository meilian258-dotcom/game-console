package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeStartupStateTest {
    private static Path root() { return Path.of("runtime-startup-fixture-" + UUID.randomUUID()); }
    private static RuntimeInstaller.Report report(RuntimeInstaller.Outcome outcome) {
        return new RuntimeInstaller.Report(outcome, "inert report", List.of(), RuntimeInstaller.PackState.MISSING, List.of(), 0, 0);
    }
    private static RuntimeInstaller.Report report(RuntimeInstaller.Outcome outcome, RuntimeCatalog.RuntimeId id) {
        var state = outcome == RuntimeInstaller.Outcome.READY || outcome == RuntimeInstaller.Outcome.INSTALLED
                ? RuntimeInstaller.FileState.READY : RuntimeInstaller.FileState.MISSING;
        return new RuntimeInstaller.Report(outcome, id + " fixture", List.of(new RuntimeInstaller.RuntimeStatus(id, state, "fixture", List.of())),
                RuntimeInstaller.PackState.MISSING, List.of(id + " detail"), 0, 0);
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
        Path root = root(); RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.BLOCKED, RuntimeCatalog.RuntimeId.MAME));
        assertTrue(RuntimeStartupState.failure(root).isPresent());
        for (var outcome : List.of(RuntimeInstaller.Outcome.READY, RuntimeInstaller.Outcome.INSTALLED)) {
            var ready = report(outcome, RuntimeCatalog.RuntimeId.MAME); RuntimeStartupState.recordReport(root, ready);
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

    @Test void everyNonReadyOutcomeIsAvailableToDiagnostics() {
        for (var outcome : RuntimeInstaller.Outcome.values()) {
            Path root = root();
            RuntimeStartupState.recordReport(root, report(outcome));
            assertEquals(outcome != RuntimeInstaller.Outcome.READY && outcome != RuntimeInstaller.Outcome.INSTALLED,
                    RuntimeStartupState.failure(root).isPresent(), outcome.name());
        }
    }

    @Test void failedNativeThenReadyGbaDoesNotHideFailureAndLastReportRemainsCompatible() {
        Path root = root(); var failed = report(RuntimeInstaller.Outcome.FAILED);
        RuntimeStartupState.recordReport(root, failed, Set.of(RuntimeCatalog.RuntimeId.MAME, RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT));
        var gba = report(RuntimeInstaller.Outcome.READY, RuntimeCatalog.RuntimeId.GBA);
        RuntimeStartupState.recordReport(root, gba);
        assertSame(failed, RuntimeStartupState.failure(root).orElseThrow());
        assertSame(gba, RuntimeStartupState.report(root).orElseThrow());
    }

    @Test void failedGbaThenReadyNativeDoesNotHideFailure() {
        Path root = root(); var failed = report(RuntimeInstaller.Outcome.FAILED, RuntimeCatalog.RuntimeId.GBA);
        RuntimeStartupState.recordReport(root, failed);
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY), Set.of(RuntimeCatalog.RuntimeId.MAME, RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT));
        assertSame(failed, RuntimeStartupState.failure(root).orElseThrow());
    }

    @Test void readyNativeThenFailedGbaAndReadyGbaThenFailedNativeBothRetainFailure() {
        for (var id : List.of(RuntimeCatalog.RuntimeId.MAME, RuntimeCatalog.RuntimeId.GBA)) {
            Path root = root(); var other = id == RuntimeCatalog.RuntimeId.MAME ? RuntimeCatalog.RuntimeId.GBA : RuntimeCatalog.RuntimeId.MAME;
            RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY, other));
            var failed = report(RuntimeInstaller.Outcome.FAILED, id); RuntimeStartupState.recordReport(root, failed);
            assertSame(failed, RuntimeStartupState.failure(root).orElseThrow());
        }
    }

    @Test void successfulComponentClearsOnlyItsOwnFailure() {
        Path root = root(); var nativeFailure = report(RuntimeInstaller.Outcome.FAILED, RuntimeCatalog.RuntimeId.MAME);
        var gbaFailure = report(RuntimeInstaller.Outcome.FAILED, RuntimeCatalog.RuntimeId.GBA);
        RuntimeStartupState.recordReport(root, nativeFailure); RuntimeStartupState.recordReport(root, gbaFailure);
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.INSTALLED, RuntimeCatalog.RuntimeId.MAME));
        assertSame(gbaFailure, RuntimeStartupState.failure(root).orElseThrow());
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY, RuntimeCatalog.RuntimeId.GBA));
        assertTrue(RuntimeStartupState.failure(root).isEmpty());
    }

    @Test void earlyScopedFailureCanBeClearedByCompleteNormalRuntimeStatuses() {
        Path root = root(); var scope = Set.of(RuntimeCatalog.RuntimeId.MAME, RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT);
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.FAILED), scope);
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY, RuntimeCatalog.RuntimeId.MAME));
        assertTrue(RuntimeStartupState.failure(root).isPresent());
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY, RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT));
        assertTrue(RuntimeStartupState.failure(root).isEmpty());
    }

    @Test void unknownLegacyFailureSurvivesOtherComponentsAndUnscopedSuccess() {
        Path root = root(); var unknown = report(RuntimeInstaller.Outcome.FAILED);
        RuntimeStartupState.recordReport(root, unknown);
        for (var id : RuntimeCatalog.RuntimeId.values()) RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY, id));
        RuntimeStartupState.recordReport(root, report(RuntimeInstaller.Outcome.READY));
        assertSame(unknown, RuntimeStartupState.failure(root).orElseThrow());
    }

    @Test void multipleDistinctFailuresAreAllKeptInOneDiagnosticWithoutDuplicatingNativeScope() {
        Path root = root(); var nativeFailure = report(RuntimeInstaller.Outcome.FAILED);
        RuntimeStartupState.recordReport(root, nativeFailure, Set.of(RuntimeCatalog.RuntimeId.MAME, RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT));
        var gbaFailure = report(RuntimeInstaller.Outcome.CANCELLED, RuntimeCatalog.RuntimeId.GBA);
        RuntimeStartupState.recordReport(root, gbaFailure);
        var result = RuntimeStartupState.failure(root).orElseThrow();
        assertFalse(result.ready()); assertTrue(result.summary().contains("2 项"));
        assertTrue(result.details().contains(nativeFailure.summary())); assertTrue(result.details().contains(gbaFailure.summary()));
        assertThrows(UnsupportedOperationException.class, () -> result.details().clear());
    }

    @Test void parallelAddonReportsCannotLoseOtherComponentsFailure() {
        Path root = root(); var failed = report(RuntimeInstaller.Outcome.FAILED, RuntimeCatalog.RuntimeId.MAME);
        var ready = report(RuntimeInstaller.Outcome.READY, RuntimeCatalog.RuntimeId.GBA);
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> {for (int i = 0; i < 100; i++) RuntimeStartupState.recordReport(root, failed);}),
                CompletableFuture.runAsync(() -> {for (int i = 0; i < 100; i++) RuntimeStartupState.recordReport(root, ready);})
        ).join();
        assertSame(failed, RuntimeStartupState.failure(root).orElseThrow());
    }
}
