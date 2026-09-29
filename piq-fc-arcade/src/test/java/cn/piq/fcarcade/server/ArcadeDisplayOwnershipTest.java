package cn.piq.fcarcade.server;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArcadeDisplayOwnershipTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final ArcadeDisplayOwnership.Owner CABINET =
            new ArcadeDisplayOwnership.Owner(DIMENSION, 15, 64, 15);

    @Test
    void legacyRocketScreenAndOccupancyPositionsResolveInEveryFacingAcrossChunkEdges() {
        for (int turns = 0; turns < 4; turns++) {
            var screen = RocketArcadeGeometry.leaderboardTextOrigin(turns);
            assertEquals(CABINET, ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                    CABINET.x() + screen.x(), CABINET.y() + screen.y(), CABINET.z() + screen.z())
                    .orElseThrow(), "rocket facing " + turns);
        }
        assertEquals(CABINET, ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                15.5, 66.38, 15.5).orElseThrow());
        // Previous one-block legacy cabinet occupancy position is also retained.
        assertEquals(CABINET, ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                15.5, 65.38, 15.5).orElseThrow());
    }

    @Test
    void legacyMaximumPanelAndTallOccupancyHaveRecoverableOwnersInAllDirections() {
        int[][] rightSteps = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] right : rightSteps) {
            double x = CABINET.x() + 0.5 + right[0] * 3.5;
            double z = CABINET.z() + 0.5 + right[1] * 3.5;
            assertEquals(CABINET, ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                    x, CABINET.y() + 8.38, z).orElseThrow());
        }
    }

    @Test
    void ownershipSurvivesRotationMovementAndReloadButDoesNotCrossDimensions() {
        var loaded = new ArcadeDisplayOwnership.LoadedDisplays<String>();
        var persisted = new HashSet<>(legacyTags(CABINET));
        var owner = loaded.track("old", persisted, DIMENSION, 15.5, 66.38, 15.5).orElseThrow();
        persisted.add(owner.tag());
        loaded.forget("old"); // Entity chunk unload, not a deletion.
        assertTrue(loaded.ownedBy(CABINET).isEmpty());
        assertEquals(CABINET, loaded.track("reloaded", persisted, DIMENSION,
                -200, 190, 400).orElseThrow()); // Explicit coordinates are not a spatial guess.
        assertTrue(loaded.track("another-dimension", persisted, "minecraft:the_nether",
                15.5, 66.38, 15.5).isEmpty());
        assertEquals(List.of("reloaded"), loaded.ownedBy(CABINET));
    }

    @Test
    void missingMachineIsRemovedAfterItsChunkLoadsWithoutDeletingAdjacentMachine() {
        var loaded = new ArcadeDisplayOwnership.LoadedDisplays<String>();
        var neighbor = new ArcadeDisplayOwnership.Owner(DIMENSION, 16, 64, 15);
        loaded.track("leaderboard", exactTags(CABINET), DIMENSION, 16.01, 65.3, 15.5);
        loaded.track("duplicate-occupancy", exactTags(CABINET), DIMENSION, 15.5, 66.38, 15.5);
        loaded.track("neighbor", exactTags(neighbor), DIMENSION, 15.99, 65.3, 15.5);
        List<String> discarded = new ArrayList<>();
        loaded.reconcile(owner -> owner.equals(CABINET)
                ? ArcadeDisplayOwnership.AnchorState.UNLOADED
                : ArcadeDisplayOwnership.AnchorState.PRESENT, discarded::add);
        assertTrue(discarded.isEmpty()); // The display's chunk loaded first.
        loaded.reconcile(owner -> owner.equals(CABINET)
                ? ArcadeDisplayOwnership.AnchorState.MISSING
                : ArcadeDisplayOwnership.AnchorState.PRESENT, display -> {
                    discarded.add(display);
                    loaded.forget(display); // Synchronous entity-leave callback is safe.
                });
        assertEquals(List.of("leaderboard", "duplicate-occupancy"), discarded);
        assertEquals(List.of("neighbor"), loaded.ownedBy(neighbor));
        assertTrue(loaded.ownedBy(CABINET).isEmpty());
    }

    @Test
    void alreadyOrphanedLegacyEntityCanBeCleanedAfterReloadWithoutMachineConfiguration() {
        var loaded = new ArcadeDisplayOwnership.LoadedDisplays<String>();
        loaded.track("disk-orphan", legacyTags(CABINET), DIMENSION, 15.5, 65.5, 15.03);
        List<String> discarded = new ArrayList<>();
        loaded.reconcile(owner -> ArcadeDisplayOwnership.AnchorState.MISSING, discarded::add);
        assertEquals(List.of("disk-orphan"), discarded);
    }

    @Test
    void untaggedForeignMalformedAndAmbiguousDisplaysAreNeverClaimed() {
        var neighbor = new ArcadeDisplayOwnership.Owner(DIMENSION, 16, 64, 15);
        for (Set<String> tags : List.of(
                Set.<String>of(), Set.of(CABINET.legacyTag()),
                Set.of(ArcadeDisplayOwnership.DISPLAY_TAG),
                Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, CABINET.tag()),
                Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, CABINET.legacyTag(), neighbor.tag()),
                Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, CABINET.legacyTag(),
                        "piq_fc_owner_v1|minecraft:overworld|bad,64,15"),
                Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, CABINET.legacyTag(), neighbor.legacyTag()))) {
            assertTrue(ArcadeDisplayOwnership.resolve(tags, DIMENSION, 15.5, 65.5, 15.5).isEmpty());
        }
        assertTrue(ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                2_000, 65.5, 15.5).isEmpty());
        assertTrue(ArcadeDisplayOwnership.resolve(legacyTags(CABINET), DIMENSION,
                Double.NaN, 65.5, 15.5).isEmpty());
    }

    @Test
    void negativeWorldCoordinatesAndNonOverworldNamesRoundTripExactly() {
        var owner = new ArcadeDisplayOwnership.Owner("piq:arcade/lobby", -17, -40, -1);
        assertEquals(owner, ArcadeDisplayOwnership.resolve(legacyTags(owner), owner.dimension(),
                -16.5, -37.62, -0.5).orElseThrow());
        assertEquals(owner, ArcadeDisplayOwnership.resolve(exactTags(owner), owner.dimension(),
                -16.5, -37.62, -0.5).orElseThrow());
    }

    private static Set<String> legacyTags(ArcadeDisplayOwnership.Owner owner) {
        return Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, owner.legacyTag());
    }

    @Test
    void vanishedRoomRemovesBothLinkedLabelsButNotLeaderboardOrAnotherRoom() {
        var loaded = new ArcadeDisplayOwnership.LoadedDisplays<String>();
        var secondary = new ArcadeDisplayOwnership.Owner(DIMENSION, 16, 64, 15);
        var other = new ArcadeDisplayOwnership.Owner(DIMENSION, 20, 64, 15);
        loaded.track("primary", exactTags(CABINET), DIMENSION, 16, 67, 15);
        loaded.track("secondary", exactTags(secondary), DIMENSION, 17, 67, 15);
        loaded.track("leaderboard", exactTags(CABINET), DIMENSION, 16, 65, 15);
        loaded.track("other", exactTags(other), DIMENSION, 21, 67, 15);
        Set<String> occupancy = Set.of("primary", "secondary", "other");
        List<String> removed = new ArrayList<>();
        loaded.discardIf((display, owner) -> occupancy.contains(display) && !owner.equals(other), display -> {
            removed.add(display); loaded.forget(display);
        });
        assertEquals(List.of("primary", "secondary"), removed);
        assertEquals(List.of("leaderboard"), loaded.ownedBy(CABINET));
        assertEquals(List.of("other"), loaded.ownedBy(other));
    }

    @Test
    void reloadedOccupancyCanBeRemovedWithoutAnInMemoryPreviousRoomOrLoadedMachineChunk() {
        var loaded = new ArcadeDisplayOwnership.LoadedDisplays<String>();
        loaded.track("persisted", exactTags(CABINET), DIMENSION, 16, 67, 15);
        List<String> removed = new ArrayList<>();
        loaded.discardIf((display, owner) -> true, removed::add);
        assertEquals(List.of("persisted"), removed);
        assertTrue(loaded.ownedBy(CABINET).isEmpty());
        loaded.discardIf((display, owner) -> true, removed::add);
        assertEquals(1, removed.size());
    }

    private static Set<String> exactTags(ArcadeDisplayOwnership.Owner owner) {
        return Set.of(ArcadeDisplayOwnership.DISPLAY_TAG, owner.legacyTag(), owner.tag());
    }
}
