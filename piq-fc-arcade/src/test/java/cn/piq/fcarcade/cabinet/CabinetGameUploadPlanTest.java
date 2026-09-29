package cn.piq.fcarcade.cabinet;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameUploadPlanTest {
    private CabinetGameManifest.Entry file(String name, int seed, int size) {
        return new CabinetGameManifest.Entry(name, String.format("%064x", seed), size);
    }
    private CabinetGameManifest arcade() {
        return new CabinetGameManifest("piq_native_arcade:mame", List.of(
                file("game.zip", 1, 30000), file("neogeo.zip", 2, 40), file("qsound_hle.zip", 3, 70)));
    }
    private CabinetGameUploadPlan plan(int mask) { return new CabinetGameUploadPlan(arcade(), mask); }

    @Test void fullyCachedOpenNeedsNoPutAndOnlyEndCommitsBinding() {
        var plan = plan(0);var commits = new AtomicInteger();
        assertEquals(0, plan.missingMask());assertEquals(0, plan.missingBytes());
        assertEquals(3, plan.nextFile());assertEquals(0, plan.offset());
        plan.requireComplete();assertEquals(0, commits.get());
        for (int i = 0; i < 3; i++) { int file = i;assertThrows(IllegalArgumentException.class, () -> plan.validate(file, 0, 1)); }
        plan.finish(commits::incrementAndGet);assertEquals(1, commits.get());
        assertThrows(IllegalStateException.class, () -> plan.finish(commits::incrementAndGet));assertEquals(1, commits.get());
    }

    @Test void onlyRomMissingSkipsBothCachedBiosObjects() {
        var plan = plan(1);assertEquals(30000, plan.missingBytes());assertEquals(0, plan.nextFile());
        plan.accepted(0, 0, CabinetGameManifest.CHUNK);assertEquals(CabinetGameManifest.CHUNK, plan.offset());
        assertThrows(IllegalStateException.class, plan::requireComplete);
        plan.accepted(0, CabinetGameManifest.CHUNK, 30000 - CabinetGameManifest.CHUNK);
        assertEquals(3, plan.nextFile());plan.requireComplete();
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(1, 0, 40));
    }

    @Test void onlyBiosMissingDoesNotAcceptAnyCachedRomPut() {
        var plan = plan(2);assertEquals(40, plan.missingBytes());assertEquals(1, plan.nextFile());
        assertThrows(IllegalArgumentException.class, () -> plan.validate(0, 0, 1));
        plan.accepted(1, 0, 40);assertEquals(3, plan.nextFile());plan.requireComplete();
    }

    @Test void differentGameReusesSharedBiosAndOnlyTransfersNewRom() {
        var first = arcade();var second = new CabinetGameManifest(first.backend(), List.of(file("different.zip", 9, 110), first.files().get(1)));
        var plan = CabinetGameUploadPlan.fromVerifiedMissing(second, 1);
        assertEquals(1, plan.missingMask());assertEquals(110, plan.missingBytes());
        var binding = new java.util.concurrent.atomic.AtomicReference<>(first);
        plan.accepted(0, 0, 110);assertSame(first, binding.get());
        plan.finish(() -> binding.set(second));assertSame(second, binding.get());
    }

    @Test void everyValidUniqueObjectMaskTransfersExactlyThoseEntriesInOrder() {
        for (int mask = 0; mask <= 7; mask++) {
            var plan = plan(mask);long sent = 0;
            for (int file = 0; file < 3; file++) {
                if ((mask & (1 << file)) == 0) continue;
                assertEquals(file, plan.nextFile());int size = arcade().files().get(file).size();
                for (int offset = 0; offset < size;) {
                    int length = Math.min(CabinetGameManifest.CHUNK, size - offset);
                    plan.validate(file, offset, length);assertEquals(offset, plan.offset());
                    plan.accepted(file, offset, length);offset += length;sent += length;
                }
            }
            assertEquals(sent, plan.missingBytes());assertEquals(3, plan.nextFile());plan.requireComplete();
        }
    }

    @Test void invalidMasksCannotBecomeAnUploadPlan() {
        assertThrows(IllegalArgumentException.class, () -> plan(-1));assertThrows(IllegalArgumentException.class, () -> plan(8));
        assertThrows(IllegalArgumentException.class, () -> plan(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> CabinetGameUploadPlan.allFilesMask(0));
        assertThrows(IllegalArgumentException.class, () -> CabinetGameUploadPlan.allFilesMask(6));
        var single = new CabinetGameManifest("piq_gba:gba", List.of(file("game.gba", 1, 100)));
        assertThrows(IllegalArgumentException.class, () -> new CabinetGameUploadPlan(single, 2));
        assertThrows(IllegalArgumentException.class, () -> CabinetGameUploadPlan.fromVerifiedMissing(single, 2));
    }

    @Test void skipsGapsButRejectsOrderReplayWrongOffsetsAndLengthsWithoutAdvancing() {
        var plan = plan(5);
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(2, 0, 70));
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, 0, CabinetGameManifest.CHUNK + 1));
        assertEquals(0, plan.nextFile());assertEquals(0, plan.offset());
        plan.accepted(0, 0, CabinetGameManifest.CHUNK);
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, CabinetGameManifest.CHUNK, 6000));
        plan.accepted(0, CabinetGameManifest.CHUNK, 30000 - CabinetGameManifest.CHUNK);
        assertEquals(2, plan.nextFile());assertEquals(0, plan.offset());
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(1, 0, 40));
        plan.accepted(2, 0, 70);plan.requireComplete();
    }

    @Test void earlyEndOrRevocationCannotCommitAnOldBindingOrGrant() {
        for (int mask : new int[]{0, 1, 2, 7}) {
            var plan = plan(mask);var commits = new AtomicInteger();
            if (mask != 0) assertThrows(IllegalStateException.class, () -> plan.finish(commits::incrementAndGet));
            plan.close();assertEquals(0, commits.get());
            assertThrows(IllegalStateException.class, plan::requireComplete);
            assertThrows(IllegalStateException.class, () -> plan.finish(commits::incrementAndGet));
            assertThrows(IllegalArgumentException.class, () -> plan.accepted(0, 0, 1));assertEquals(0, commits.get());
        }
    }

    @Test void completedBytesDoNotCommitIfAuthorizationIsRevokedBeforeEnd() {
        var plan = plan(2);var commits = new AtomicInteger();plan.accepted(1, 0, 40);
        plan.requireComplete();assertEquals(0, commits.get());plan.close();
        assertThrows(IllegalStateException.class, () -> plan.finish(commits::incrementAndGet));assertEquals(0, commits.get());
    }

    @Test void sameHashAndSizeAliasUploadsFirstObjectOnceAndUsesUniqueQuota() {
        var manifest = new CabinetGameManifest("piq_native_arcade:mame", List.of(
                file("game.zip", 1, 100), file("neogeo.zip", 1, 100), file("qsound_hle.zip", 3, 70)));
        var plan = CabinetGameUploadPlan.fromVerifiedMissing(manifest, 7);
        assertEquals(5, plan.missingMask());assertEquals(170, plan.missingBytes());
        plan.accepted(0, 0, 100);assertEquals(2, plan.nextFile());
        assertThrows(IllegalArgumentException.class, () -> plan.accepted(1, 0, 100));
        plan.accepted(2, 0, 70);plan.requireComplete();
        assertEquals(0, CabinetGameUploadPlan.fromVerifiedMissing(manifest, 0).missingBytes());
        assertThrows(IllegalArgumentException.class, () -> new CabinetGameUploadPlan(manifest, 2));
        assertThrows(IllegalArgumentException.class, () -> new CabinetGameUploadPlan(manifest, 3));
        assertThrows(IllegalArgumentException.class, () -> CabinetGameUploadPlan.fromVerifiedMissing(manifest, 1));
    }

    @Test void hashWithConflictingSizeIsRejectedEvenIfEverythingClaimsToBeCached() {
        var manifest = new CabinetGameManifest("piq_native_arcade:mame", List.of(file("game.zip", 1, 100), file("neogeo.zip", 1, 101)));
        for (int mask = 0; mask < 4; mask++) {
            int invalid = mask;
            assertThrows(IllegalArgumentException.class, () -> new CabinetGameUploadPlan(manifest, invalid));
            assertThrows(IllegalArgumentException.class, () -> CabinetGameUploadPlan.fromVerifiedMissing(manifest, invalid));
        }
    }
}
