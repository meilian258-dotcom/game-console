package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId.*;
import static cn.piq.fcarcade.runtime.RuntimeInstaller.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only generated inert bytes under @TempDir; never native libraries or user game files. */
class RuntimeInstallerTest {
    @TempDir Path root;
    private final Map<String, byte[]> bytes = fixture();
    private static final Set<RuntimeCatalog.RuntimeId> ALL = EnumSet.allOf(RuntimeCatalog.RuntimeId.class);

    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (var component : RuntimeCatalog.standard()) for (var artifact : component.files())
            values.put(artifact.relativePath(), ("inert fixture, never executable: " + artifact.relativePath()).repeat(20).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return values;
    }
    private RuntimeInstaller service() throws Exception {
        List<RuntimeCatalog.Component> catalog = new ArrayList<>();
        for (var component : RuntimeCatalog.standard()) {
            List<RuntimeCatalog.Artifact> files = new ArrayList<>();
            for (var artifact : component.files()) files.add(new RuntimeCatalog.Artifact(artifact.relativePath(), bytes.get(artifact.relativePath()).length, sha(bytes.get(artifact.relativePath()))));
            catalog.add(new RuntimeCatalog.Component(component.id(), component.title(), files));
        }
        return new RuntimeInstaller(root, catalog);
    }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private Path pack(Map<String, byte[]> contents) throws Exception {
        Path file = root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip"); Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : contents.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
        }
        return file;
    }
    private Report inspect(RuntimeInstaller service) { return service.inspect(ALL, () -> false, p -> {}); }
    private Report install(RuntimeInstaller service) { return service.install(ALL, () -> false, p -> {}); }
    private void installed(String name, byte[] content) throws Exception { Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.write(path, content); }
    private void noTargets() { for (String name : bytes.keySet()) assertFalse(Files.exists(root.resolve(name)), name); }
    private void noStages() throws Exception {
        Path folder = root.resolve("piq-runtime-packs"); if (!Files.exists(folder)) return;
        try (var names = Files.list(folder)) { assertFalse(names.anyMatch(p -> p.getFileName().toString().startsWith(".install-"))); }
    }

    @Test void standardCatalogHasNineExactExecutablesAndNoBiosOrRom() {
        assertEquals(3, RuntimeCatalog.standard().size());
        var all = RuntimeCatalog.standard().stream().flatMap(c -> c.files().stream()).toList(); assertEquals(9, all.size());
        assertEquals(437599687L, all.stream().mapToLong(RuntimeCatalog.Artifact::bytes).sum());
        assertTrue(all.stream().anyMatch(a->a.relativePath().equals("piq-native-arcade/runtime/piq-native-helper-v4.jar")
                &&a.bytes()==18858&&a.sha256().equals("51A1A6A0A326E5E855647A414DBB78894CA01E9D766627EF272CE59BC809A304")));
        assertFalse(all.stream().anyMatch(a->a.relativePath().equals("piq-native-arcade/runtime/piq-native-helper.jar")));
        for (var file : all) { assertTrue(file.sha256().matches("[0-9A-F]{64}")); assertFalse(file.relativePath().contains("rom")); assertFalse(file.relativePath().contains("bios")); }
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCatalog.Artifact("../evil.dll", 1, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeCatalog.Artifact("piq-gba/runtime/neogeo.zip", 1, "0".repeat(64)));
    }

    @Test void originalOfflinePackRetainsItsExactNineEntriesIncludingGbaAndOldHelper() {
        var legacy=RuntimeCatalog.originalOfflinePack();assertEquals(9,legacy.size());
        assertEquals(437599400L,legacy.stream().mapToLong(RuntimeCatalog.Artifact::bytes).sum());
        assertTrue(legacy.stream().anyMatch(a->a.relativePath().equals("piq-native-arcade/runtime/piq-native-helper.jar")
                &&a.bytes()==18571&&a.sha256().equals("20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C")));
        assertFalse(legacy.stream().anyMatch(a->a.relativePath().endsWith("piq-native-helper-v4.jar")));
        for(var component:RuntimeCatalog.standard())if(component.id()!=MAME)for(var artifact:component.files())assertTrue(legacy.contains(artifact));
    }

    @Test void inspectAndMissingPackNeverWriteAnything() throws Exception {
        var report = inspect(service()); assertEquals(Outcome.BLOCKED, report.outcome()); assertEquals(PackState.MISSING, report.pack());
        assertEquals(3, report.runtimes().size()); assertTrue(report.runtimes().stream().allMatch(s -> s.state() == FileState.MISSING));
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
        assertEquals(Outcome.BLOCKED, install(service()).outcome());
        try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test void validPackStagesHashesInstallsAllAndSecondClickDoesNotRewrite() throws Exception {
        pack(bytes); var service = service(); assertEquals(Outcome.AVAILABLE, inspect(service).outcome());
        var first = install(service); assertEquals(Outcome.INSTALLED, first.outcome(), first.details().toString()); assertEquals(9, first.installed()); assertTrue(first.ready());
        Map<String, Object> keys = new LinkedHashMap<>();
        for (var entry : bytes.entrySet()) { assertArrayEquals(entry.getValue(), Files.readAllBytes(root.resolve(entry.getKey()))); keys.put(entry.getKey(), Files.getAttribute(root.resolve(entry.getKey()), "basic:fileKey")); }
        var second = install(service); assertEquals(Outcome.READY, second.outcome()); assertEquals(0, second.installed()); assertEquals(9, second.skipped());
        for (var entry : keys.entrySet()) assertEquals(entry.getValue(), Files.getAttribute(root.resolve(entry.getKey()), "basic:fileKey")); noStages();
    }

    @Test void installOneRuntimeDoesNotInstallOtherAddons() throws Exception {
        pack(bytes); var report = service().install(Set.of(GBA), () -> false, p -> {}); assertEquals(Outcome.INSTALLED, report.outcome(), report.details().toString()); assertEquals(3, report.installed());
        for (String name : bytes.keySet()) assertEquals(name.startsWith("piq-gba/"), Files.exists(root.resolve(name)));
    }

    @Test void wrongExistingVersionBlocksWholeSelectionAndPreservesEveryByte() throws Exception {
        pack(bytes); String name = bytes.keySet().iterator().next(); byte[] old = {1, 2, 3}; installed(name, old);
        var report = install(service()); assertEquals(Outcome.BLOCKED, report.outcome()); assertEquals(0, report.installed()); assertArrayEquals(old, Files.readAllBytes(root.resolve(name)));
        for (String other : bytes.keySet()) if (!other.equals(name)) assertFalse(Files.exists(root.resolve(other))); noStages();
    }

    @Test void sameSizeWrongExistingShaBlocksAndIsNotOverwritten() throws Exception {
        pack(bytes); String name = bytes.keySet().iterator().next(); byte[] wrong = bytes.get(name).clone(); wrong[0] ^= 1; installed(name, wrong);
        assertEquals(Outcome.BLOCKED, install(service()).outcome()); assertArrayEquals(wrong, Files.readAllBytes(root.resolve(name)));
    }

    @Test void preserveExistingGoodFileAndOnlyCountMissingInstalls() throws Exception {
        pack(bytes); String name = bytes.keySet().iterator().next(); installed(name, bytes.get(name)); Object key = Files.getAttribute(root.resolve(name), "basic:fileKey");
        var report = install(service()); assertEquals(Outcome.INSTALLED, report.outcome(), report.details().toString()); assertEquals(8, report.installed()); assertEquals(1, report.skipped()); assertEquals(key, Files.getAttribute(root.resolve(name), "basic:fileKey"));
    }

    @Test void tamperedPackDataFailsBeforeAnyTargetAndCleansStage() throws Exception {
        var altered = new LinkedHashMap<>(bytes); String last = new ArrayList<>(bytes.keySet()).get(8); byte[] wrong = bytes.get(last).clone(); wrong[0] ^= 1; altered.put(last, wrong); pack(altered);
        var report = install(service()); assertEquals(Outcome.FAILED, report.outcome()); assertTrue(report.details().stream().anyMatch(s -> s.contains("SHA-256"))); noTargets(); noStages();
    }

    @Test void cancelWhileStagingLeavesNoExecutableOrPartial() throws Exception {
        pack(bytes); AtomicBoolean cancel = new AtomicBoolean();
        var report = service().install(ALL, cancel::get, p -> { if (p.phase() == Phase.STAGING) cancel.set(true); });
        assertEquals(Outcome.CANCELLED, report.outcome()); noTargets(); noStages();
    }

    @Test void cancelAfterFirstPublicationRollsBackOnlyOwnFiles() throws Exception {
        pack(bytes); String good = new ArrayList<>(bytes.keySet()).get(8); installed(good, bytes.get(good)); AtomicBoolean cancel = new AtomicBoolean();
        var report = service().install(ALL, cancel::get, p -> { if (p.phase() == Phase.INSTALLING) cancel.set(true); });
        assertEquals(Outcome.CANCELLED, report.outcome());
        for (String name : bytes.keySet()) assertEquals(name.equals(good), Files.exists(root.resolve(name))); assertArrayEquals(bytes.get(good), Files.readAllBytes(root.resolve(good))); noStages();
    }

    @Test void callbackFailureAfterPublicationRollsBackRatherThanClaimingSuccess() throws Exception {
        pack(bytes); var report = service().install(ALL, () -> false, p -> { if (p.phase() == Phase.INSTALLING) throw new IllegalStateException("fixture callback failed"); });
        assertEquals(Outcome.FAILED, report.outcome()); noTargets(); noStages();
    }

    @Test void foreignReplacementAfterPublicationIsNeverDeletedByRollback() throws Exception {
        pack(bytes); AtomicBoolean changed = new AtomicBoolean(); String first = bytes.keySet().iterator().next(); Path target = root.resolve(first);
        var report = service().install(ALL, () -> false, p -> {
            if (p.phase() == Phase.INSTALLING && changed.compareAndSet(false, true)) {
                try { Files.delete(target); Files.writeString(target, "foreign replacement"); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }
        });
        assertEquals(Outcome.FAILED, report.outcome()); assertEquals("foreign replacement", Files.readString(target));
        for (String name : bytes.keySet()) if (!name.equals(first)) assertFalse(Files.exists(root.resolve(name))); noStages();
    }

    @Test void concurrentProcessLockReturnsBusyWithoutExecutables() throws Exception {
        pack(bytes); Path lock = root.resolve("piq-runtime-packs/.piq-runtime-install.lock");
        try (var file = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); var held = file.lock()) {
            assertEquals(Outcome.BUSY, install(service()).outcome()); noTargets(); noStages();
        }
    }

    @Test void unrelatedLockContentIsPreserved() throws Exception {
        pack(bytes); Path lock = root.resolve("piq-runtime-packs/.piq-runtime-install.lock"); Files.writeString(lock, "do not change");
        assertEquals(Outcome.FAILED, install(service()).outcome()); assertEquals("do not change", Files.readString(lock)); noTargets();
    }

    @Test void packNamesOutsideExactWhitelistAreRejectedIncludingBios() throws Exception {
        for (String name : List.of("../escape.dll", "/evil.dll", "C:/evil.dll", "piq-gba\\runtime\\evil.dll", "piq-gba/runtime/neogeo.zip", "README.md", "piq-gba/runtime/./mgba_libretro.dll")) {
            var altered = new LinkedHashMap<>(bytes); altered.put(name, new byte[]{1}); pack(altered);
            var report = install(service()); assertEquals(Outcome.BLOCKED, report.outcome(), name + report.details()); assertEquals(PackState.INVALID, report.pack()); noTargets();
        }
    }

    @Test void missingOrWrongSizedPackMemberIsRejectedBeforeStaging() throws Exception {
        var altered = new LinkedHashMap<>(bytes); altered.remove(bytes.keySet().iterator().next()); pack(altered);
        assertEquals(PackState.INVALID, inspect(service()).pack()); noTargets();
        altered = new LinkedHashMap<>(bytes); altered.put(bytes.keySet().iterator().next(), new byte[]{1}); pack(altered);
        assertEquals(PackState.INVALID, inspect(service()).pack()); noTargets();
    }

    private void patchCentral(int field, int value, int width) throws Exception {
        Path file = root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip"); byte[] raw = Files.readAllBytes(file); ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < raw.length - 46; i++) if (b.getInt(i) == 0x02014b50) { if (width == 2) b.putShort(i + field, (short) value); else b.putInt(i + field, value); Files.write(file, raw); return; }
        fail("central directory fixture absent");
    }

    @Test void zipSymlinkAndWindowsReparseMetadataAreRejected() throws Exception {
        pack(bytes); patchCentral(38, 0120777 << 16, 4); assertEquals(PackState.INVALID, inspect(service()).pack());
        pack(bytes); patchCentral(38, 0x400, 4); assertEquals(PackState.INVALID, inspect(service()).pack()); noTargets();
    }

    @Test void encryptedUnsupportedMethodAndZip64MetadataAreRejected() throws Exception {
        pack(bytes); patchCentral(8, 1, 2); assertEquals(PackState.INVALID, inspect(service()).pack());
        pack(bytes); patchCentral(10, 99, 2); assertEquals(PackState.INVALID, inspect(service()).pack());
        pack(bytes); patchCentral(24, -1, 4); assertEquals(PackState.INVALID, inspect(service()).pack()); noTargets();
    }

    @Test void truncatedAndOversizedArchivesAreRejected() throws Exception {
        Path file = pack(bytes); byte[] raw = Files.readAllBytes(file); Files.write(file, java.util.Arrays.copyOf(raw, raw.length - 8)); assertEquals(PackState.INVALID, inspect(service()).pack());
        Files.write(file, new byte[2 * 1024 * 1024]); assertEquals(PackState.INVALID, inspect(service()).pack()); noTargets();
    }

    @Test void onlyExactParentDirectoryEntriesAreAllowed() throws Exception {
        var altered = new LinkedHashMap<>(bytes); altered.put("piq-gba/", new byte[0]); altered.put("piq-gba/runtime/", new byte[0]); pack(altered);
        assertEquals(PackState.AVAILABLE, inspect(service()).pack());
        altered.put("unrelated/", new byte[0]); pack(altered); assertEquals(PackState.INVALID, inspect(service()).pack());
    }

    @Test void regularFileWhereRuntimeDirectoryShouldBeIsUnsafeAndPreserved() throws Exception {
        pack(bytes); Files.writeString(root.resolve("piq-gba"), "foreign data"); var report = install(service());
        assertEquals(Outcome.BLOCKED, report.outcome()); assertEquals("foreign data", Files.readString(root.resolve("piq-gba"))); noTargets();
    }

    @Test void alreadyCancelledAndInterruptedNeverTouchDisk() throws Exception {
        var service = service(); assertEquals(Outcome.CANCELLED, service.install(ALL, () -> true, p -> {}).outcome());
        Thread.currentThread().interrupt(); try { assertEquals(Outcome.CANCELLED, inspect(service).outcome()); } finally { Thread.interrupted(); }
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void reportCollectionsCannotBeMutated() throws Exception {
        var report = inspect(service()); assertThrows(UnsupportedOperationException.class, () -> report.runtimes().clear()); assertThrows(UnsupportedOperationException.class, () -> report.details().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.runtimes().getFirst().files().clear());
    }

    @Test void emptySelectionOrUnsupportedPlatformCannotStartInstall() throws Exception {
        var service = service(); assertEquals(Outcome.READY, service.install(Set.of(), () -> false, p -> {}).outcome());
        assertEquals(Outcome.READY, service.inspect(Set.of(), () -> false, p -> {}).outcome());
        var unsupported = new RuntimeInstaller(root, RuntimeCatalog.standard(), false);
        assertEquals(Outcome.READY, unsupported.install(Set.of(), () -> false, p -> {}).outcome());
        assertEquals(Outcome.READY, unsupported.inspect(Set.of(), () -> false, p -> {}).outcome());
        assertEquals(Outcome.BLOCKED, install(unsupported).outcome()); assertEquals(Outcome.BLOCKED, inspect(unsupported).outcome());
        assertFalse(install(unsupported).ready()); try (var paths = Files.list(root)) { assertEquals(0, paths.count()); }
    }

    @Test void symbolicLinksInTargetOrPackAreRefusedWhenPlatformPermitsFixtureLinks() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside-fixture"));
        Path link = root.resolve("piq-gba");
        try { Files.createSymbolicLink(link, outside); }
        catch (java.nio.file.FileSystemException | UnsupportedOperationException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "OS account cannot create fixture symlinks: " + unsupported.getMessage());
        }
        pack(bytes); assertEquals(Outcome.BLOCKED, install(service()).outcome());
        try (var paths = Files.list(outside)) { assertEquals(0, paths.count()); }
        Files.delete(link); Path normalPack = root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip"); Path moved = outside.resolve("fixture.zip");
        Files.move(normalPack, moved); Files.createSymbolicLink(normalPack, moved);
        assertEquals(PackState.UNSAFE, inspect(service()).pack()); noTargets();
    }

    @Test void missingFilesDoNotInventHashedOrTransferredBytes() throws Exception {
        List<Progress> progress = new ArrayList<>(); var report = service().inspect(ALL, () -> false, progress::add);
        assertEquals(Outcome.BLOCKED, report.outcome());
        assertTrue(progress.stream().allMatch(p -> p.completedBytes() == 0 && p.totalBytes() == 0));
    }

    @Test void retainedParentIdentityRejectsOrdinaryDirectoryReplacement() throws Exception {
        Path directory = Files.createDirectory(root.resolve("piq-gba")); var paths = new RuntimePaths(root); paths.check(directory, true, false);
        Path retained = root.resolve("old-directory-fixture"); Files.move(directory, retained); Files.createDirectory(directory);
        // Explicit distinct fixture creation time makes the Windows null-fileKey fallback deterministic.
        Files.setAttribute(directory, "basic:creationTime", java.nio.file.attribute.FileTime.fromMillis(1234567));
        assertThrows(java.io.IOException.class, () -> paths.check(directory, true, false));
        assertTrue(Files.isDirectory(retained)); assertTrue(Files.isDirectory(directory));
    }

    @Test void sameBytesForeignInodeAfterPublicationIsAlsoNotRollbackOwned() throws Exception {
        pack(bytes); AtomicBoolean changed = new AtomicBoolean(); AtomicBoolean cancel = new AtomicBoolean(); String first = bytes.keySet().iterator().next(); Path target = root.resolve(first);
        var report = service().install(ALL, cancel::get, p -> {
            if (p.phase() == Phase.INSTALLING && changed.compareAndSet(false, true)) {
                try { Files.delete(target); Files.write(target, bytes.get(first)); cancel.set(true); }
                catch (Exception failure) { throw new RuntimeException(failure); }
            }
        });
        assertEquals(Outcome.CANCELLED, report.outcome()); assertArrayEquals(bytes.get(first), Files.readAllBytes(target));
        for (String name : bytes.keySet()) if (!name.equals(first)) assertFalse(Files.exists(root.resolve(name))); noStages();
    }
}
