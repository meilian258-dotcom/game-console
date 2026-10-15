// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Common-only startup diagnostics. Normalizes paths lexically; never touches the filesystem. */
public final class RuntimeStartupState {
    private static final String UNKNOWN = "legacy-unscoped";
    private record State(RuntimeInstaller.Report last, Map<String, RuntimeInstaller.Report> failures) {}
    private static final ConcurrentMap<Path, State> REPORTS = new ConcurrentHashMap<>();

    private RuntimeStartupState() {}

    public static void recordReport(Path gameRoot, RuntimeInstaller.Report report) {
        Objects.requireNonNull(report, "report");
        recordReport(gameRoot, report, report.runtimes().stream().map(RuntimeInstaller.RuntimeStatus::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /** Scope empty early-failure reports explicitly; success can clear only the same components. */
    public static void recordReport(Path gameRoot, RuntimeInstaller.Report report, Set<RuntimeCatalog.RuntimeId> scope) {
        Objects.requireNonNull(report, "report");
        Set<RuntimeCatalog.RuntimeId> owned = Set.copyOf(scope);
        List<String> components = owned.isEmpty() ? List.of(UNKNOWN)
                : owned.stream().sorted().map(Enum::name).toList();
        // The entire update is atomic: parallel addon setup cannot discard another component's failure.
        REPORTS.compute(key(gameRoot), (path, previous) -> {
            var failures = new LinkedHashMap<String, RuntimeInstaller.Report>();
            if (previous != null) failures.putAll(previous.failures());
            for (String component : components) {
                // An old unscoped success cannot prove which component an early failure belonged to.
                if (report.ready() && !component.equals(UNKNOWN)) failures.remove(component);
                else if (report.ready()) continue;
                else failures.put(component, report);
            }
            return new State(report, java.util.Collections.unmodifiableMap(failures));
        });
    }

    public static Optional<RuntimeInstaller.Report> report(Path gameRoot) {
        State state = REPORTS.get(key(gameRoot));
        return state == null ? Optional.empty() : Optional.of(state.last());
    }

    public static Optional<RuntimeInstaller.Report> failure(Path gameRoot) {
        State state = REPORTS.get(key(gameRoot));
        if (state == null || state.failures().isEmpty()) return Optional.empty();
        var failures = state.failures().values().stream().distinct().toList();
        if (failures.size() == 1) return Optional.of(failures.getFirst());
        return Optional.of(new RuntimeInstaller.Report(RuntimeInstaller.Outcome.FAILED,
                "运行环境仍有 " + failures.size() + " 项检查未通过；请打开运行环境诊断",
                failures.stream().flatMap(value -> value.runtimes().stream()).distinct().toList(),
                RuntimeInstaller.PackState.MISSING,
                failures.stream().flatMap(value -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(value.summary()), value.details().stream())).toList(), 0, 0));
    }

    private static Path key(Path gameRoot) {
        return Objects.requireNonNull(gameRoot, "gameRoot").toAbsolutePath().normalize();
    }
}
