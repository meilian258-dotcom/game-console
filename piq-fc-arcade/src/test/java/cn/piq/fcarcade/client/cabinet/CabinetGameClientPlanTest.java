package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.cabinet.CabinetGameStore;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameClientPlanTest {
    @TempDir Path temporary;
    private static final CabinetGameClientPlan.Check CURRENT = () -> {};
    private record Fixture(List<Path> sources, CabinetGameManifest manifest) {}
    private Fixture fixture() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("selected"));
        String[] names = {"game.zip", "neogeo.zip", "qsound_hle.zip"};
        var files = new ArrayList<Path>();
        var entries = new ArrayList<CabinetGameManifest.Entry>();
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = new byte[40000 + i * 17000];
            new Random(777 + i).nextBytes(bytes);
            files.add(Files.write(source.resolve(names[i]), bytes));
            entries.add(new CabinetGameManifest.Entry(names[i], CabinetGameManifest.digest(bytes), bytes.length));
        }
        return new Fixture(files, new CabinetGameManifest("piq_native:arcade", entries));
    }
    private Path cache(Fixture fixture) {
        return CabinetGameClientPlan.cacheDirectory(temporary.resolve("cache"), "server:fixture:24455", fixture.manifest);
    }
    private void populate(Fixture fixture, Path cache) throws Exception {
        CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest, UUID.randomUUID(), CURRENT, additional -> {});
    }
    private void noParts(Path cache) throws Exception {
        try (var files = Files.list(cache)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".part"))); }
    }

    @Test void everyMissingMaskTransfersOnlyThoseFilesWithExactByteDenominator() throws Exception {
        var fixture = fixture();
        for (int mask = 0; mask < 8; mask++) {
            var plan = new CabinetGameClientPlan(fixture.manifest, mask);
            var sent = new ArrayList<Integer>();
            long[] bytes = {0};
            plan.transferMissing((index, entry) -> { sent.add(index); bytes[0] += Files.readAllBytes(fixture.sources.get(index)).length; });
            var expected = new ArrayList<Integer>();
            for (int i = 0; i < 3; i++) if ((mask & (1 << i)) != 0) expected.add(i);
            assertEquals(expected, sent);
            assertEquals(bytes[0], plan.missingBytes());
            assertEquals(expected.isEmpty(), plan.missingBytes() == 0);
        }
    }

    @Test void allServerHitsInvokeZeroPutCallbacksButRemainAnExplicitPlan() throws Exception {
        var fixture = fixture();
        var plan = new CabinetGameClientPlan(fixture.manifest, 0);
        plan.transferMissing((index, entry) -> fail("A server cache hit must not issue PUT"));
        assertEquals(0, plan.missingBytes());
        assertSame(fixture.manifest, plan.manifest());
    }

    @Test void sharedBiosWithNewRomSendsOnlyRomAndNotItsCompanions() throws Exception {
        var fixture = fixture();
        var plan = new CabinetGameClientPlan(fixture.manifest, 1);
        var sent = new ArrayList<String>();
        plan.transferMissing((index, entry) -> sent.add(entry.name()));
        assertEquals(List.of("game.zip"), sent);
        assertEquals(fixture.manifest.files().getFirst().size(), plan.missingBytes());
    }

    @Test void noncontiguousMissingFilesKeepManifestIndices() throws Exception {
        var fixture = fixture();
        var sent = new ArrayList<Integer>();
        new CabinetGameClientPlan(fixture.manifest, 5).transferMissing((index, entry) -> sent.add(index));
        assertEquals(List.of(0, 2), sent);
    }

    @Test void negativeAndBeyondManifestMasksAreRejected() throws Exception {
        var fixture = fixture();
        for (int mask : new int[]{-1, Integer.MIN_VALUE, 8, 15, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CabinetGameClientPlan(fixture.manifest, mask));
        var single = new CabinetGameManifest(fixture.manifest.backend(), List.of(fixture.manifest.files().getFirst()));
        for (int mask : new int[]{2, 3, 4, 7}) assertThrows(IllegalArgumentException.class, () -> new CabinetGameClientPlan(single, mask));
        assertThrows(IllegalArgumentException.class, () -> new CabinetGameClientPlan(single, 0).missing(-1));
        assertThrows(IllegalArgumentException.class, () -> new CabinetGameClientPlan(single, 0).missing(1));
    }

    @Test void onlySuccessfulExactUploadOpenMayCarryMissingMask() throws Exception {
        var manifest = fixture().manifest;
        assertTrue(CabinetGameClientPlan.validReply(true, true, manifest, manifest, 7));
        assertTrue(CabinetGameClientPlan.validReply(true, true, manifest, manifest, 0));
        assertTrue(CabinetGameClientPlan.validReply(true, false, null, manifest, 0));
        assertTrue(CabinetGameClientPlan.validReply(false, true, manifest, null, 0));
        assertFalse(CabinetGameClientPlan.validReply(true, false, null, manifest, 1));
        assertFalse(CabinetGameClientPlan.validReply(false, true, manifest, manifest, 1));
        assertFalse(CabinetGameClientPlan.validReply(true, true, manifest, null, 0));
        assertFalse(CabinetGameClientPlan.validReply(true, true, null, manifest, 0));
        assertFalse(CabinetGameClientPlan.validReply(true, true, manifest, manifest, 8));
        assertFalse(CabinetGameClientPlan.validReply(true, true, manifest, manifest, -1));
    }

    @Test void matchingRomHashAloneNeverAcceptsDifferentBiosOrBackendPlan() throws Exception {
        var manifest = fixture().manifest;
        var otherBackend = new CabinetGameManifest("other:arcade", manifest.files());
        var entries = new ArrayList<>(manifest.files());
        var bios = entries.get(1);
        entries.set(1, new CabinetGameManifest.Entry(bios.name(), "e".repeat(64), bios.size()));
        var otherBios = new CabinetGameManifest(manifest.backend(), entries);
        for (var changed : List.of(otherBackend, otherBios)) {
            assertEquals(manifest.gameHash(), changed.gameHash());
            assertFalse(CabinetGameClientPlan.validReply(true, true, manifest, changed, 0));
        }
    }

    @Test void firstSelectionPopulatesAllFilesAndOrdinaryReopenNeedsZeroGet() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        var plan = CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT);
        assertEquals(0, plan.missingMask());
        plan.transferMissing((index, entry) -> fail("Uploader reopen must use verified local cache, not GET"));
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(Files.readAllBytes(fixture.sources.get(i)), Files.readAllBytes(cache.resolve(fixture.manifest.files().get(i).name())));
            CabinetGameStore.verify(fixture.sources.get(i), fixture.manifest.files().get(i));
        }
        noParts(cache);
    }

    @Test void allLocalHitsDoNotRewriteFilesOrReserveMoreQuota() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        var before = new ArrayList<java.nio.file.attribute.BasicFileAttributes>();
        for (var entry : fixture.manifest.files()) before.add(CabinetGameStore.regular(cache.resolve(entry.name())));
        CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest, UUID.randomUUID(), CURRENT,
                additional -> { throw new IOException("Cache is full: a complete hit must not reserve space"); });
        for (int i = 0; i < 3; i++) {
            var after = CabinetGameStore.regular(cache.resolve(fixture.manifest.files().get(i).name()));
            assertEquals(before.get(i).lastModifiedTime(), after.lastModifiedTime());
            assertEquals(before.get(i).fileKey(), after.fileKey());
        }
        noParts(cache);
    }

    @Test void missingBiosAloneIsCopiedAndChargedOnce() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        var romBefore = CabinetGameStore.regular(cache.resolve("game.zip"));
        Files.delete(cache.resolve("neogeo.zip"));
        var quota = new ArrayList<Long>();
        CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest, UUID.randomUUID(), CURRENT, quota::add);
        assertEquals(List.of((long)fixture.manifest.files().get(1).size()), quota);
        assertEquals(romBefore.fileKey(), CabinetGameStore.regular(cache.resolve("game.zip")).fileKey());
        assertEquals(0, CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT).missingBytes());
    }

    @Test void ordinaryDownloadMissingBiosOnlyInvokesOneGetFile() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        Files.delete(cache.resolve("qsound_hle.zip"));
        var plan = CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT);
        var received = new ArrayList<Integer>();
        plan.transferMissing((index, entry) -> {
            received.add(index);
            Files.copy(fixture.sources.get(index), cache.resolve(entry.name()));
        });
        assertEquals(List.of(2), received);
        assertEquals(fixture.manifest.files().get(2).size(), plan.missingBytes());
        assertEquals(0, CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT).missingMask());
    }

    @Test void quotaFailureCreatesNoCopiedOrTemporaryFilesAndLeavesSourcesExact() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        assertThrows(IOException.class, () -> CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest,
                UUID.randomUUID(), CURRENT, additional -> { throw new IOException("full"); }));
        try (var files = Files.list(cache)) { assertEquals(0, files.count()); }
        CabinetGameClientPlan.verifySelected(fixture.sources, fixture.manifest, CURRENT);
    }

    @Test void cancellationDuringCopyRemovesOnlyOwnPartAndNeverChangesOriginals() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        CabinetGameStore.directory(cache);
        CabinetGameClientPlan.Check cancelDuringWrite = () -> {
            try (var paths = Files.list(cache)) {
                for (Path part : paths.toList()) if (part.getFileName().toString().endsWith(".part") && Files.size(part) > 0)
                    throw new IOException("cancelled");
            }
        };
        assertThrows(IOException.class, () -> CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest,
                UUID.randomUUID(), cancelDuringWrite, additional -> {}));
        noParts(cache);
        assertFalse(Files.exists(cache.resolve("game.zip")));
        CabinetGameClientPlan.verifySelected(fixture.sources, fixture.manifest, CURRENT);
    }

    @Test void cancellationStopsMissingCallbackBeforeFollowingFiles() throws Exception {
        var fixture = fixture();
        AtomicInteger count = new AtomicInteger();
        assertThrows(IOException.class, () -> new CabinetGameClientPlan(fixture.manifest, 7).transferMissing((index, entry) -> {
            count.incrementAndGet(); throw new IOException("cancelled");
        }));
        assertEquals(1, count.get());
    }

    @Test void preexistingPartCollisionIsNotDeletedOrOverwritten() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        CabinetGameStore.directory(cache);
        UUID id = UUID.randomUUID();
        Path collision = Files.write(cache.resolve("selected-" + id + "-0.part"), new byte[]{9, 8, 7});
        assertThrows(FileAlreadyExistsException.class, () -> CabinetGameClientPlan.cacheSelected(fixture.sources, cache, fixture.manifest,
                id, CURRENT, additional -> {}));
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(collision));
    }

    @Test void corruptSourceBiosEvenWithCompleteLocalCacheIsRejected() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        Path bios = fixture.sources.get(1);
        byte[] corrupted = Files.readAllBytes(bios); corrupted[corrupted.length - 1] ^= 1;
        Files.write(bios, corrupted);
        assertThrows(IOException.class, () -> populate(fixture, cache));
        assertEquals(0, CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT).missingMask());
    }

    @Test void sameSizeCorruptCachedBiosIsRejectedRatherThanPretendingFullHit() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        Path bios = cache.resolve("qsound_hle.zip");
        byte[] corrupted = Files.readAllBytes(bios); corrupted[0] ^= 1; Files.write(bios, corrupted);
        assertThrows(IOException.class, () -> CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT));
        assertThrows(IOException.class, () -> populate(fixture, cache));
        assertArrayEquals(corrupted, Files.readAllBytes(bios));
    }

    @Test void truncatedCachedRomCannotProduceAReusablePlan() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        populate(fixture, cache);
        Files.write(cache.resolve("game.zip"), new byte[]{1});
        assertThrows(IOException.class, () -> CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT));
    }

    @Test void everyManifestCompanionIsRequiredAndSourceOrderCannotBeSubstituted() throws Exception {
        var fixture = fixture();
        assertThrows(IOException.class, () -> CabinetGameClientPlan.verifySelected(fixture.sources.subList(0, 2), fixture.manifest, CURRENT));
        var reversed = new ArrayList<>(fixture.sources); Collections.swap(reversed, 1, 2);
        assertThrows(IOException.class, () -> CabinetGameClientPlan.verifySelected(reversed, fixture.manifest, CURRENT));
        Files.delete(fixture.sources.get(2));
        assertThrows(IOException.class, () -> populate(fixture, cache(fixture)));
    }

    @Test void directoryInPlaceOfCachedFileIsRejectedWithoutModification() throws Exception {
        var fixture = fixture();
        Path cache = cache(fixture);
        CabinetGameStore.directory(cache);
        Path nonfile = Files.createDirectory(cache.resolve("game.zip"));
        assertThrows(IOException.class, () -> CabinetGameClientPlan.inspectCache(cache, fixture.manifest, CURRENT));
        assertTrue(Files.isDirectory(nonfile));
    }

    @Test void contextsAndWholeManifestContentAreIndependentCacheNamespaces() throws Exception {
        var fixture = fixture();
        Path root = temporary.resolve("cache"), first = cache(fixture);
        populate(fixture, first);
        var entries = new ArrayList<>(fixture.manifest.files());
        var bios = entries.get(1); entries.set(1, new CabinetGameManifest.Entry(bios.name(), "c".repeat(64), bios.size()));
        var changedBios = new CabinetGameManifest(fixture.manifest.backend(), entries);
        Path secondContext = CabinetGameClientPlan.cacheDirectory(root, "server:fixture:25565", fixture.manifest);
        Path changedContent = CabinetGameClientPlan.cacheDirectory(root, "server:fixture:24455", changedBios);
        Path secondBackend = CabinetGameClientPlan.cacheDirectory(root, "server:fixture:24455", new CabinetGameManifest("other:arcade", fixture.manifest.files()));
        assertEquals(4, Set.of(first, secondContext, changedContent, secondBackend).size());
        assertEquals(7, CabinetGameClientPlan.inspectCache(secondContext, fixture.manifest, CURRENT).missingMask());
        assertEquals(7, CabinetGameClientPlan.inspectCache(changedContent, changedBios, CURRENT).missingMask());
        assertEquals(0, CabinetGameClientPlan.inspectCache(first, fixture.manifest, CURRENT).missingMask());
        assertThrows(IllegalArgumentException.class, () -> CabinetGameClientPlan.cacheDirectory(root, "", fixture.manifest));
    }
}
