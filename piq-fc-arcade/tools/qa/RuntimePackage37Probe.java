import cn.piq.fcarcade.runtime.RuntimeCatalog;
import cn.piq.fcarcade.runtime.RuntimeInstaller;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Exercises the real production pack only inside a caller-created disposable sandbox. */
public final class RuntimePackage37Probe {
    private static int assertions;
    private static void check(boolean result, String detail) { assertions++; if (!result) throw new AssertionError(detail); }
    private static Map<String, FileTime> modified(Path root) throws Exception {
        Map<String, FileTime> result = new LinkedHashMap<>();
        for (var c : RuntimeCatalog.standard()) for (var f : c.files()) result.put(f.relativePath(), Files.getLastModifiedTime(root.resolve(f.relativePath())));
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path expected = Path.of(args[0]).toRealPath(), root = Path.of(args[1]).toRealPath();
        check(Path.of(RuntimeInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(expected), "Actual final-JAR installer");
        check(root.getFileName().toString().equals("game-fixture"), "Explicit disposable sandbox child required");
        var all = EnumSet.allOf(RuntimeCatalog.RuntimeId.class); var installer = new RuntimeInstaller(root);
        var missing = installer.inspect(all, () -> false, p -> {});
        check(missing.outcome() == RuntimeInstaller.Outcome.AVAILABLE, "Exact production pack is structurally valid: " + missing.details());
        var first = installer.install(all, () -> false, p -> {});
        check(first.outcome() == RuntimeInstaller.Outcome.INSTALLED && first.installed() == 9 && first.skipped() == 0, "Nine actual files installed and checked: " + first.details());
        check(first.runtimes().stream().allMatch(r -> r.state() == RuntimeInstaller.FileState.READY), "All actual hashes match pins");
        var times = modified(root);
        var repeat = installer.install(all, () -> false, p -> {});
        check(repeat.outcome() == RuntimeInstaller.Outcome.READY && repeat.installed() == 0 && repeat.skipped() == 9, "Second install fully reuses nine files");
        check(times.equals(modified(root)), "Reuse preserves all runtime mtimes");
        String one = "piq-gba/runtime/piq-gba-helper.jar"; Path target = root.resolve(one);
        Files.delete(target); // This file was created above in this test's own disposable tree only.
        var repair = installer.install(all, () -> false, p -> {});
        check(repair.outcome() == RuntimeInstaller.Outcome.INSTALLED && repair.installed() == 1 && repair.skipped() == 8, "Only one missing helper is restored");
        for (var entry : times.entrySet()) if (!entry.getKey().equals(one)) check(entry.getValue().equals(Files.getLastModifiedTime(root.resolve(entry.getKey()))), "Other eight are untouched");
        byte[] foreign = "inert wrong-version fixture".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(target, foreign); var beforeConflict = modified(root);
        var blocked = installer.install(all, () -> false, p -> {});
        check(blocked.outcome() == RuntimeInstaller.Outcome.BLOCKED && blocked.installed() == 0, "Wrong existing file blocks installation");
        check(java.util.Arrays.equals(foreign, Files.readAllBytes(target)), "Wrong existing file is preserved");
        check(beforeConflict.equals(modified(root)), "Conflict does not rewrite any runtime");
        try (var paths = Files.walk(root)) { check(paths.noneMatch(p -> p.getFileName().toString().startsWith(".install-")), "No stage residue"); }
        System.out.println("{\"ok\":true,\"assertions\":" + assertions + ",\"first_installed\":9,\"second_installed\":0,\"repair_installed\":1,\"conflict_preserved\":true,\"native_library_loaded\":false,\"minecraft_started\":false}");
    }
}
