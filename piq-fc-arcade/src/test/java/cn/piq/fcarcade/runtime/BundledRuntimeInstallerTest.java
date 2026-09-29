package cn.piq.fcarcade.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static cn.piq.fcarcade.runtime.RuntimeCatalog.RuntimeId.*;
import static cn.piq.fcarcade.runtime.RuntimeInstaller.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tiny inert streams under @TempDir only; never load a library or use a real game directory. */
class BundledRuntimeInstallerTest {
    @TempDir Path root;
    private static final Set<RuntimeCatalog.RuntimeId> ARCADE = Set.of(MAME, NEOGEO_SNAPSHOT);
    private static final Set<RuntimeCatalog.RuntimeId> ALL = EnumSet.allOf(RuntimeCatalog.RuntimeId.class);
    private final Map<String, byte[]> bytes = fixture();

    private static Map<String, byte[]> fixture() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (var component : RuntimeCatalog.standard()) for (var artifact : component.files())
            values.put(artifact.relativePath(), ("inert bundled fixture: " + artifact.relativePath()).repeat(12)
                    .getBytes(StandardCharsets.US_ASCII));
        return values;
    }

    private List<RuntimeCatalog.Component> catalog() throws Exception {
        List<RuntimeCatalog.Component> catalog = new ArrayList<>();
        for (var component : RuntimeCatalog.standard()) {
            List<RuntimeCatalog.Artifact> files = new ArrayList<>();
            for (var artifact : component.files()) {
                byte[] content = bytes.get(artifact.relativePath());
                files.add(new RuntimeCatalog.Artifact(artifact.relativePath(), content.length,
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))));
            }
            catalog.add(new RuntimeCatalog.Component(component.id(), component.title(), files));
        }
        return catalog;
    }

    private RuntimeInstaller service(BundledSource source) throws Exception {
        return new RuntimeInstaller(root, catalog(), true, Map.of(MAME, source, NEOGEO_SNAPSHOT, source));
    }

    private Report install(RuntimeInstaller service) { return service.install(ARCADE, () -> false, p -> {}); }
    private Report inspect(RuntimeInstaller service) { return service.inspect(ARCADE, () -> false, p -> {}); }

    @Test void manualBundledInstallCannotBypassOrdinaryArcadeArchitectureGate() throws Exception {
        String original = System.getProperty("os.arch");
        AtomicInteger calls = new AtomicInteger();
        try {
            System.setProperty("os.arch", "x86_64");
            var service = service(a -> { calls.incrementAndGet(); return new ByteArrayInputStream(bytes.get(a.relativePath())); });
            assertEquals(Outcome.BLOCKED, inspect(service).outcome());
            assertEquals(Outcome.BLOCKED, install(service).outcome());
            assertEquals(0, calls.get()); emptyRoot();
            pack(bytes);
            assertEquals(Outcome.INSTALLED, service.install(Set.of(GBA), () -> false, p -> {}).outcome());
            for (String name : arcadePaths()) assertFalse(Files.exists(root.resolve(name)));
        } finally {
            if (original == null) System.clearProperty("os.arch"); else System.setProperty("os.arch", original);
        }
    }
    private List<String> arcadePaths() { return bytes.keySet().stream().filter(p -> p.startsWith("piq-native-arcade/")).toList(); }

    private void installed(String name, byte[] content) throws Exception {
        Path target = root.resolve(name); Files.createDirectories(target.getParent()); Files.write(target, content);
    }

    private void pack(Map<String, byte[]> contents) throws Exception {
        Path target = root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip");
        Files.createDirectories(target.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            for (var entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }

    private void noTargets() { bytes.keySet().forEach(name -> assertFalse(Files.exists(root.resolve(name)), name)); }
    private void emptyRoot() throws Exception { try (var files = Files.list(root)) { assertEquals(0, files.count()); } }
    private void noStages() throws Exception {
        Path directory = root.resolve("piq-runtime-packs");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".install-")));
        }
    }
    private void noGba() { assertFalse(Files.exists(root.resolve("piq-gba"))); }

    private final class TrackedSource implements BundledSource {
        final Map<String, byte[]> contents;
        final Map<String, Integer> opened = new LinkedHashMap<>();
        int closed;
        TrackedSource() { this(bytes); }
        TrackedSource(Map<String, byte[]> contents) { this.contents = contents; }
        @Override public InputStream open(RuntimeCatalog.Artifact artifact) throws IOException {
            String name = artifact.relativePath();
            opened.merge(name, 1, Integer::sum);
            byte[] content = contents.get(name);
            if (content == null) throw new IOException("missing bundled fixture: " + name);
            // Every probe and staging attempt gets a distinct, unread stream.
            return new ByteArrayInputStream(content) {
                private boolean didClose;
                @Override public void close() throws IOException {
                    if (!didClose) { didClose = true; closed++; }
                    super.close();
                }
            };
        }
        int openedCount() { return opened.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    @Test void bundledInspectionIsReadOnlyAndDoesNotInventByteProgress() throws Exception {
        var source = new TrackedSource(); List<Progress> progress = new ArrayList<>();
        var result = service(source).inspect(ARCADE, () -> false, progress::add);
        assertEquals(Outcome.AVAILABLE, result.outcome(), result.details().toString());
        assertEquals(PackState.AVAILABLE, result.pack()); assertEquals(2, result.runtimes().size());
        assertTrue(progress.stream().allMatch(p -> p.completedBytes() == 0 && p.totalBytes() == 0));
        assertEquals(Set.copyOf(arcadePaths()), source.opened.keySet());
        assertEquals(6, source.openedCount()); assertEquals(source.openedCount(), source.closed); emptyRoot();
    }

    @Test void firstInstallPublishesExactlySixWithoutOfflinePackOrGba() throws Exception {
        var source = new TrackedSource(); var result = install(service(source));
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString());
        assertEquals(6, result.installed()); assertEquals(0, result.skipped()); assertTrue(result.ready());
        for (String name : arcadePaths()) assertArrayEquals(bytes.get(name), Files.readAllBytes(root.resolve(name)));
        assertEquals(Set.copyOf(arcadePaths()), source.opened.keySet());
        assertEquals(12, source.openedCount()); assertEquals(source.openedCount(), source.closed);
        assertFalse(Files.exists(root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip"))); noGba(); noStages();
    }

    @Test void repeatInstallReusesEveryCorrectFileWithoutOpeningResource() throws Exception {
        var source = new TrackedSource(); var service = service(source);
        assertEquals(Outcome.INSTALLED, install(service).outcome());
        int previousOpens = source.openedCount(); Map<String, Object> identities = new LinkedHashMap<>();
        for (String name : arcadePaths()) identities.put(name, Files.getAttribute(root.resolve(name), "basic:fileKey"));
        var result = install(service);
        assertEquals(Outcome.READY, result.outcome()); assertEquals(0, result.installed()); assertEquals(6, result.skipped());
        assertEquals(previousOpens, source.openedCount());
        for (var entry : identities.entrySet()) assertEquals(entry.getValue(), Files.getAttribute(root.resolve(entry.getKey()), "basic:fileKey"));
        noGba(); noStages();
    }

    @Test void oneMissingFileIsTheOnlyResourceReadAndOnlyFilePublished() throws Exception {
        String missing = arcadePaths().get(4); Map<String, Object> identities = new LinkedHashMap<>();
        for (String name : arcadePaths()) if (!name.equals(missing)) {
            installed(name, bytes.get(name)); identities.put(name, Files.getAttribute(root.resolve(name), "basic:fileKey"));
        }
        var source = new TrackedSource(Map.of(missing, bytes.get(missing))); var result = install(service(source));
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString());
        assertEquals(1, result.installed()); assertEquals(5, result.skipped());
        assertEquals(Map.of(missing, 2), source.opened); assertEquals(2, source.closed);
        for (var entry : identities.entrySet()) assertEquals(entry.getValue(), Files.getAttribute(root.resolve(entry.getKey()), "basic:fileKey"));
        noGba(); noStages();
    }

    @Test void existingCorrectFilesNeedNeitherResourcesNorOfflinePack() throws Exception {
        for (String name : arcadePaths()) installed(name, bytes.get(name));
        AtomicInteger opened = new AtomicInteger(); var service = service(a -> { opened.incrementAndGet(); throw new IOException("no resource"); });
        assertEquals(Outcome.READY, inspect(service).outcome()); assertEquals(Outcome.READY, install(service).outcome());
        assertEquals(0, opened.get()); noGba(); noStages();
    }

    @Test void sameSizeWrongHashResourceNeverPublishesAnyTarget() throws Exception {
        String last = arcadePaths().getLast(); var bad = new LinkedHashMap<>(bytes);
        byte[] altered = bytes.get(last).clone(); altered[0] ^= 1; bad.put(last, altered);
        var source = new TrackedSource(bad); var result = install(service(source));
        assertEquals(Outcome.FAILED, result.outcome()); assertFalse(result.ready()); assertEquals(0, result.installed());
        assertTrue(result.details().stream().anyMatch(s -> s.contains("SHA-256")));
        assertEquals(source.openedCount(), source.closed); noTargets(); noStages();
    }

    @Test void shortResourceNeverPublishesPreviouslyStagedTargets() throws Exception {
        String last = arcadePaths().getLast(); var bad = new LinkedHashMap<>(bytes);
        bad.put(last, Arrays.copyOf(bytes.get(last), bytes.get(last).length - 1));
        var source = new TrackedSource(bad); var result = install(service(source));
        assertEquals(Outcome.FAILED, result.outcome()); assertEquals(source.openedCount(), source.closed); noTargets(); noStages();
    }

    @Test void oversizedResourceIsBoundedAndNeverPublished() throws Exception {
        String last = arcadePaths().getLast(); var bad = new LinkedHashMap<>(bytes);
        bad.put(last, Arrays.copyOf(bytes.get(last), bytes.get(last).length + 1));
        var source = new TrackedSource(bad); var result = install(service(source));
        assertEquals(Outcome.FAILED, result.outcome());
        assertTrue(result.details().stream().anyMatch(s -> s.contains("上限")));
        assertEquals(source.openedCount(), source.closed); noTargets(); noStages();
    }

    @Test void missingResourceIsBlockedBeforeDiskWrites() throws Exception {
        var source = new TrackedSource(Map.of()); var result = install(service(source));
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(PackState.INVALID, result.pack());
        assertEquals(0, result.installed()); emptyRoot();
    }

    @Test void nullResourceIsBlockedBeforeDiskWrites() throws Exception {
        var result = install(service(a -> null));
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(PackState.INVALID, result.pack()); emptyRoot();
    }

    @Test void resourceDisappearingAfterProbeFailsBeforePublication() throws Exception {
        String last = arcadePaths().getLast(); Map<String, Integer> attempts = new LinkedHashMap<>();
        var result = install(service(a -> {
            int attempt = attempts.merge(a.relativePath(), 1, Integer::sum);
            if (a.relativePath().equals(last) && attempt > 1) throw new IOException("fixture resource disappeared");
            return new ByteArrayInputStream(bytes.get(a.relativePath()));
        }));
        assertEquals(Outcome.FAILED, result.outcome()); noTargets(); noStages();
    }

    @Test void cancellationDuringStagingClosesResourcesAndPublishesNothing() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean(); var source = new TrackedSource();
        var result = service(source).install(ARCADE, cancelled::get, p -> { if (p.phase() == Phase.STAGING) cancelled.set(true); });
        assertEquals(Outcome.CANCELLED, result.outcome()); assertEquals(source.openedCount(), source.closed); noTargets(); noStages();
    }

    @Test void cancellationAfterPublicationRollsBackOnlyNewFiles() throws Exception {
        String existing = arcadePaths().getLast(); installed(existing, bytes.get(existing));
        Object identity = Files.getAttribute(root.resolve(existing), "basic:fileKey"); AtomicBoolean cancelled = new AtomicBoolean();
        var result = service(new TrackedSource()).install(ARCADE, cancelled::get, p -> { if (p.phase() == Phase.INSTALLING) cancelled.set(true); });
        assertEquals(Outcome.CANCELLED, result.outcome());
        for (String name : bytes.keySet()) assertEquals(name.equals(existing), Files.exists(root.resolve(name)), name);
        assertArrayEquals(bytes.get(existing), Files.readAllBytes(root.resolve(existing)));
        assertEquals(identity, Files.getAttribute(root.resolve(existing), "basic:fileKey")); noStages();
    }

    @Test void publicationCallbackFailureRollsBackAllNewFiles() throws Exception {
        var result = service(new TrackedSource()).install(ARCADE, () -> false, p -> {
            if (p.phase() == Phase.INSTALLING) throw new IllegalStateException("inert callback failure");
        });
        assertEquals(Outcome.FAILED, result.outcome()); noTargets(); noStages();
    }

    @Test void foreignReplacementDuringPublicationIsRetainedOnRollback() throws Exception {
        String first = arcadePaths().getFirst(); Path target = root.resolve(first); AtomicBoolean changed = new AtomicBoolean();
        var result = service(new TrackedSource()).install(ARCADE, () -> false, p -> {
            if (p.phase() == Phase.INSTALLING && changed.compareAndSet(false, true)) {
                try { Files.delete(target); Files.writeString(target, "foreign fixture replacement"); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            }
        });
        assertEquals(Outcome.FAILED, result.outcome()); assertEquals("foreign fixture replacement", Files.readString(target));
        for (String name : bytes.keySet()) if (!name.equals(first)) assertFalse(Files.exists(root.resolve(name)), name); noStages();
    }

    @Test void existingConflictBlocksWithoutReadingOrOverwritingResources() throws Exception {
        String first = arcadePaths().getFirst(); byte[] old = bytes.get(first).clone(); old[0] ^= 1; installed(first, old);
        var source = new TrackedSource(); var result = install(service(source));
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(0, source.openedCount());
        assertArrayEquals(old, Files.readAllBytes(root.resolve(first)));
        for (String name : bytes.keySet()) if (!name.equals(first)) assertFalse(Files.exists(root.resolve(name)), name); noStages();
    }

    @Test void unsupportedPlatformDoesNotOpenEmbeddedStreamsOrWriteDirectories() throws Exception {
        var source = new TrackedSource(); var unsupported = new RuntimeInstaller(root, catalog(), false,
                Map.of(MAME, source, NEOGEO_SNAPSHOT, source));
        assertEquals(Outcome.BLOCKED, inspect(unsupported).outcome()); assertEquals(Outcome.BLOCKED, install(unsupported).outcome());
        assertEquals(0, source.openedCount()); emptyRoot();
    }

    @Test void alreadyCancelledInstallNeverOpensResourcesOrWritesDirectories() throws Exception {
        var source = new TrackedSource(); var result = service(source).install(ARCADE, () -> true, p -> {});
        assertEquals(Outcome.CANCELLED, result.outcome()); assertEquals(0, source.openedCount()); emptyRoot();
    }

    @Test void noSelectedAddonNeverOpensResourcesOrWritesDirectories() throws Exception {
        var source = new TrackedSource(); var service = service(source);
        assertEquals(Outcome.READY, service.inspect(Set.of(), () -> false, p -> {}).outcome());
        assertEquals(Outcome.READY, service.install(Set.of(), () -> false, p -> {}).outcome());
        assertEquals(0, source.openedCount()); emptyRoot();
    }

    @Test void invalidBundleDoesNotSilentlyFallBackToGoodOfflinePack() throws Exception {
        pack(bytes); var bad = new LinkedHashMap<>(bytes); String last = arcadePaths().getLast();
        byte[] altered = bytes.get(last).clone(); altered[0] ^= 1; bad.put(last, altered);
        var result = install(service(new TrackedSource(bad)));
        assertEquals(Outcome.FAILED, result.outcome()); noTargets(); noStages();
    }

    @Test void nativeOnlyIgnoresUnneededInvalidOfflinePack() throws Exception {
        Path pack = root.resolve("piq-runtime-packs/piq-runtime-pack-v1.zip"); Files.createDirectories(pack.getParent());
        byte[] unrelated = "not a zip, leave unchanged".getBytes(StandardCharsets.US_ASCII); Files.write(pack, unrelated);
        var result = install(service(new TrackedSource()));
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(6, result.installed());
        assertArrayEquals(unrelated, Files.readAllBytes(pack)); noGba(); noStages();
    }

    @Test void mixedGbaRequiresOfflinePackAndDoesNotPartiallyInstallArcade() throws Exception {
        var result = service(new TrackedSource()).install(ALL, () -> false, p -> {});
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(PackState.MISSING, result.pack()); noTargets(); emptyRoot();
    }

    @Test void mixedGbaRejectsTrimmedThreeEntryOfflinePack() throws Exception {
        var trimmed = new LinkedHashMap<String, byte[]>();
        bytes.forEach((name, data) -> { if (name.startsWith("piq-gba/")) trimmed.put(name, data); }); pack(trimmed);
        var result = service(new TrackedSource()).install(ALL, () -> false, p -> {});
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(PackState.INVALID, result.pack()); noTargets(); noStages();
    }

    @Test void mixedGbaInstallsFromFullNineEntryPackAndSixEmbeddedStreams() throws Exception {
        // Same-size stale arcade bytes in an otherwise valid nine-entry ZIP must not
        // shadow the selected embedded provider; only GBA bytes come from this ZIP.
        var offline = new LinkedHashMap<>(bytes);
        for (String name : arcadePaths()) { byte[] stale = bytes.get(name).clone(); stale[0] ^= 1; offline.put(name, stale); }
        pack(offline); var source = new TrackedSource(); var result = service(source).install(ALL, () -> false, p -> {});
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(9, result.installed());
        for (var entry : bytes.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(root.resolve(entry.getKey())));
        assertEquals(Set.copyOf(arcadePaths()), source.opened.keySet()); assertEquals(12, source.openedCount());
        assertEquals(source.openedCount(), source.closed); noStages();
    }

    @Test void gbaOnlyRetainsOfflineInstallAndNeverOpensArcadeResources() throws Exception {
        pack(bytes); var source = new TrackedSource(); var result = service(source).install(Set.of(GBA), () -> false, p -> {});
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(3, result.installed());
        assertEquals(0, source.openedCount());
        for (String name : bytes.keySet()) assertEquals(name.startsWith("piq-gba/"), Files.exists(root.resolve(name)), name); noStages();
    }

    @Test void completeGbaNeedsNoOfflinePackWhenOnlyArcadeFilesAreMissing() throws Exception {
        for (String name : bytes.keySet()) if (name.startsWith("piq-gba/")) installed(name, bytes.get(name));
        var result = service(new TrackedSource()).install(ALL, () -> false, p -> {});
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(6, result.installed());
        assertEquals(3, result.skipped()); noStages();
    }

    @Test void twoInstallersSharePersistentLockAndSecondCannotPublish() throws Exception {
        var source = new TrackedSource(); var first = service(source); var second = service(new TrackedSource());
        AtomicReference<Report> nested = new AtomicReference<>(); AtomicBoolean checked = new AtomicBoolean();
        var result = first.install(ARCADE, () -> false, p -> {
            if (p.phase() == Phase.STAGING && checked.compareAndSet(false, true)) {
                nested.set(install(second)); noTargets();
            }
        });
        assertNotNull(nested.get()); assertEquals(Outcome.BUSY, nested.get().outcome());
        assertEquals(Outcome.INSTALLED, result.outcome(), result.details().toString()); assertEquals(6, result.installed());
        assertEquals(0, Files.size(root.resolve("piq-runtime-packs/.piq-runtime-install.lock"))); noGba(); noStages();
    }

    @Test void unsafeRuntimeParentBlocksWithoutReadingResourceOrWritingTargets() throws Exception {
        Path parent = root.resolve("piq-native-arcade"); Files.writeString(parent, "unrelated existing file");
        var source = new TrackedSource(); var result = install(service(source));
        assertEquals(Outcome.BLOCKED, result.outcome()); assertEquals(0, source.openedCount());
        assertEquals("unrelated existing file", Files.readString(parent)); noTargets(); noStages();
    }

    @Test void unsafeStagingParentIsRetainedAndCannotInstallAnyTarget() throws Exception {
        Path parent = root.resolve("piq-runtime-packs"); Files.writeString(parent, "unrelated staging parent");
        var result = install(service(new TrackedSource()));
        assertFalse(result.ready()); assertEquals(0, result.installed());
        assertEquals("unrelated staging parent", Files.readString(parent)); noTargets();
    }

    @Test void nonemptyExistingLockIsNeverTruncatedOrReplaced() throws Exception {
        Path lock = root.resolve("piq-runtime-packs/.piq-runtime-install.lock"); Files.createDirectories(lock.getParent());
        Files.writeString(lock, "unrelated lock content"); var result = install(service(new TrackedSource()));
        assertEquals(Outcome.FAILED, result.outcome()); assertEquals("unrelated lock content", Files.readString(lock)); noTargets(); noStages();
    }
}
