package cn.piq.retro.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RuntimeWorkspaceTest {
    @TempDir Path base;
    private Path root() { return base.resolve("game-console/runtime-sessions"); }
    private Path stale(String kind) throws Exception {
        Path path = Files.createDirectories(root().resolve(kind + "-" + UUID.randomUUID()));
        Files.writeString(path.resolve("owner"), "PIQ-RUNTIME-WORKSPACE-1\n" + path.getFileName() + "\n9223372036854775807\n2000-01-01T00:00:00Z\n");
        Files.writeString(path.resolve("lease"), "");
        Files.writeString(path.resolve("core.dll"), "fake-copy");
        Files.createDirectory(path.resolve("session"));
        Files.writeString(path.resolve("session/rom.nes"), "fake-rom-copy"); return path;
    }
    @Test void instanceRootAndNormalCloseDoNotTouchOriginals() throws Exception {
        Path saves = Files.createDirectories(base.resolve("game-console/piq-fc/saves"));
        Path save = Files.writeString(saves.resolve("keep.sav"), "progress");
        var workspace = RuntimeWorkspace.create(base, "libretro", 10);
        assertEquals(root(), workspace.directory().getParent());
        Files.createDirectory(workspace.directory().resolve("session"));
        Files.writeString(workspace.directory().resolve("session/content"), "working-copy");
        workspace.close(); workspace.close();
        assertFalse(Files.exists(workspace.directory())); assertEquals("progress", Files.readString(save));
    }
    @Test void concurrentInstancesKeepLiveLeaseAndDeleteOnlyDeadMarkedDirectories() throws Exception {
        var one = RuntimeWorkspace.create(base, "netplay", 0);
        var two = RuntimeWorkspace.create(base, "libretro", 0);
        Path old = stale("netplay");
        Path unknown = Files.createDirectory(root().resolve("netplay-" + UUID.randomUUID()));
        Path unrelated = Files.createDirectory(root().resolve("player-saves"));
        try {
            assertEquals(1, RuntimeWorkspace.reap(base));
            assertFalse(Files.exists(old)); assertTrue(Files.exists(unknown)); assertTrue(Files.exists(unrelated));
            assertTrue(Files.exists(one.directory())); assertTrue(Files.exists(two.directory()));
            one.close(); assertTrue(Files.exists(two.directory()));
        } finally { one.close(); two.close(); }
    }
    @Test void foreignLockWinsEvenWhenOwnerIsDead() throws Exception {
        Path old = stale("libretro");
        try (var channel = FileChannel.open(old.resolve("lease"), StandardOpenOption.WRITE); var lock = channel.lock()) {
            assertEquals(0, RuntimeWorkspace.reap(base)); assertTrue(Files.exists(old.resolve("core.dll")));
        }
        assertEquals(1, RuntimeWorkspace.reap(base));
    }
    @Test void orphanNativeProcessAndAmbiguousLaunchAreProtected() throws Exception {
        Path orphan = stale("netplay");
        Files.writeString(orphan.resolve("launching"), "PIQ-RUNTIME-WORKSPACE-1");
        Files.writeString(orphan.resolve("child"), ProcessHandle.current().pid() + "\nunknown\n");
        Path ambiguous = stale("libretro"); Files.writeString(ambiguous.resolve("launching"), "PIQ-RUNTIME-WORKSPACE-1");
        assertEquals(0, RuntimeWorkspace.reap(base));
        assertTrue(Files.exists(orphan.resolve("core.dll"))); assertTrue(Files.exists(ambiguous.resolve("core.dll")));
        Files.writeString(orphan.resolve("child"), "9223372036854775807\n2000-01-01T00:00:00Z\n");
        assertEquals(1, RuntimeWorkspace.reap(base));
    }
    @Test void pidReuseDoesNotKeepDeadSessionForever() throws Exception {
        Path old = stale("fc-legacy");
        Files.writeString(old.resolve("owner"), "PIQ-RUNTIME-WORKSPACE-1\n" + old.getFileName() + "\n"
                + ProcessHandle.current().pid() + "\n2000-01-01T00:00:00Z\n");
        assumeTrue(ProcessHandle.current().info().startInstant().isPresent());
        assertEquals(1, RuntimeWorkspace.reap(base));
    }
    @Test void malformedOrMismatchedMarkerIsNeverDeleted() throws Exception {
        for (String marker : List.of("", "PIQ-RUNTIME-WORKSPACE-1\nwrong-name\n1\nunknown\n", "x".repeat(1100))) {
            Path old = stale("netplay"); Files.writeString(old.resolve("owner"), marker);
            assertEquals(0, RuntimeWorkspace.reap(base)); assertTrue(Files.exists(old.resolve("core.dll")));
        }
    }
    @Test void badChildIdentityIsConservativelyRetained() throws Exception {
        Path old = stale("netplay"); Files.writeString(old.resolve("launching"), "PIQ-RUNTIME-WORKSPACE-1");
        Files.writeString(old.resolve("child"), "0\ninvalid-time\n");
        assertEquals(0, RuntimeWorkspace.reap(base)); assertTrue(Files.exists(old.resolve("core.dll")));
    }
    @Test void rootMaintenanceLockPreventsRacingCreation() throws Exception {
        Files.createDirectories(root());
        try (var c = FileChannel.open(root().resolve("cleanup.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var l = c.lock()) {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> RuntimeWorkspace.create(base, "netplay", 0));
        }
        try (var workspace = RuntimeWorkspace.create(base, "netplay", 0)) { assertTrue(Files.exists(workspace.directory())); }
    }
    @Test void spaceErrorIsExplicitAndPrecedesMisleadingCoreDiagnosis() throws Exception {
        var error = assertThrows(IOException.class, () -> RuntimeWorkspace.requireSpace(root(), 100, 0));
        assertTrue(error.getMessage().contains(root().toString())); assertTrue(error.getMessage().contains("可用 0 MiB"));
        RuntimeWorkspace.requireSpace(root(), 100, 16L * 1024 * 1024 + 100);
        for (String message : List.of("磁盘空间不足。", "No space left on device", "There is not enough space on the disk", "Disk full", "ENOSPC")) assertTrue(RuntimeWorkspace.diskFull(message));
        assertFalse(RuntimeWorkspace.diskFull("missing core.dll"));
    }
    @Test void kindAndSizeCannotBeAbusedAsArbitraryPaths() {
        for (String kind : List.of("..", "../netplay", "saves", "", "/")) assertThrows(IllegalArgumentException.class, () -> RuntimeWorkspace.create(base, kind, 0));
        assertThrows(IllegalArgumentException.class, () -> RuntimeWorkspace.create(base, "netplay", -1));
    }
    @Test void launchFailureAndExtractFailureLeaveNoDirectory() throws Exception {
        var workspace = RuntimeWorkspace.create(base, "libretro", 0);
        Files.writeString(workspace.directory().resolve("partial.dll"), "partial");
        assertThrows(IOException.class, () -> workspace.start(new ProcessBuilder(base.resolve("missing-executable").toString())));
        workspace.close(); assertFalse(Files.exists(workspace.directory()));
    }
    @Test void closeDefersUntilRealChildExit() throws Exception {
        var workspace = RuntimeWorkspace.create(base, "libretro", 0);
        Process child = workspace.start(command(Sleeper.class));
        try {
            assertEquals("READY", child.inputReader().readLine());
            assertTrue(child.isAlive()); workspace.close();
            assertTrue(Files.exists(workspace.directory())); assertEquals(0, RuntimeWorkspace.reap(base));
        } finally { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (Files.exists(workspace.directory()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Files.exists(workspace.directory()));
    }
    private static ProcessBuilder command(Class<?> type, String... args) throws Exception {
        String javaCmd = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        if (!Files.exists(Path.of(javaCmd))) javaCmd = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()) + java.io.File.pathSeparator
                + Path.of(RuntimeWorkspace.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var values = new ArrayList<>(List.of(javaCmd, "-cp", cp, type.getName())); values.addAll(List.of(args));
        return new ProcessBuilder(values).redirectErrorStream(true);
    }
    public static class Sleeper { public static void main(String[] args) throws Exception { System.out.println("READY"); Thread.sleep(30000); } }
    public static class Crash {
        public static void main(String[] args) throws Exception {
            var workspace = RuntimeWorkspace.create(Path.of(args[0]), "libretro", 0);
            Files.writeString(workspace.directory().resolve("core.dll"), "working-copy");
            System.out.println(workspace.directory()); System.out.flush(); Runtime.getRuntime().halt(0);
        }
    }
    @Test void actualCrashedJvmLeavesRecoverableMarkerAndReleasedFileLock() throws Exception {
        Process child = command(Crash.class, base.toString()).start();
        try {
            String path = child.inputReader().readLine();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, child.exitValue());
            assertNotNull(path); assertTrue(Files.exists(Path.of(path).resolve("core.dll")));
            assertEquals(1, RuntimeWorkspace.reap(base)); assertFalse(Files.exists(Path.of(path)));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }
    @Test void transientMaintenanceContentionIsRetriedOnClose() throws Exception {
        var workspace = RuntimeWorkspace.create(base, "libretro", 0);
        try (var c = FileChannel.open(root().resolve("cleanup.lock"), StandardOpenOption.WRITE); var lock = c.lock()) {
            workspace.close(); assertTrue(Files.exists(workspace.directory()));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Files.exists(workspace.directory()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(Files.exists(workspace.directory()));
    }
    @Test void linksAreRejectedBeforeAnyOrdinaryFileIsRemoved() throws Exception {
        Path old = stale("libretro"); Path outside = Files.writeString(base.resolve("precious"), "keep");
        try { Files.createSymbolicLink(old.resolve("session/link"), outside); }
        catch (IOException | UnsupportedOperationException | SecurityException unsupported) { assumeTrue(false, "Symlink unavailable"); }
        assertEquals(0, RuntimeWorkspace.reap(base));
        assertEquals("keep", Files.readString(outside)); assertTrue(Files.exists(old.resolve("core.dll")));
    }
}
