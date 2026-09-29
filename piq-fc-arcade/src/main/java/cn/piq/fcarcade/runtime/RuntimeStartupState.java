package cn.piq.fcarcade.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Common-only startup diagnostics. Normalizes paths lexically; never touches the filesystem. */
public final class RuntimeStartupState {
    private static final ConcurrentMap<Path, RuntimeInstaller.Report> REPORTS = new ConcurrentHashMap<>();

    private RuntimeStartupState() {}

    public static void recordReport(Path gameRoot, RuntimeInstaller.Report report) {
        // Report and its nested records defensively copy their lists in their constructors.
        REPORTS.put(key(gameRoot), Objects.requireNonNull(report, "report"));
    }

    public static Optional<RuntimeInstaller.Report> report(Path gameRoot) {
        return Optional.ofNullable(REPORTS.get(key(gameRoot)));
    }

    public static Optional<RuntimeInstaller.Report> failure(Path gameRoot) {
        return report(gameRoot).filter(report -> !report.ready());
    }

    private static Path key(Path gameRoot) {
        return Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
    }
}
