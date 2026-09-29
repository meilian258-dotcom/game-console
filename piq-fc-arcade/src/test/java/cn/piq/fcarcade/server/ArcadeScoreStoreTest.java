package cn.piq.fcarcade.server;

import cn.piq.fcarcade.score.RoadRaceScoreRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcadeScoreStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsPersonalBestSortsAndReloads() {
        Path file = temporaryDirectory.resolve("leaderboard.properties");
        ArcadeScoreStore store = new ArcadeScoreStore(file);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        assertTrue(store.submit(
                RoadRaceScoreRule.ROM_SHA256, alice, "Alice", 6_750));
        assertFalse(store.submit(
                RoadRaceScoreRule.ROM_SHA256, alice, "Alice", 2_850));
        assertTrue(store.submit(
                RoadRaceScoreRule.ROM_SHA256, bob, "Bob", 16_290));
        store.flush();

        ArcadeScoreStore reloaded = new ArcadeScoreStore(file);
        assertEquals(6_750, reloaded.personalBest(
                RoadRaceScoreRule.ROM_SHA256, alice));
        assertEquals("Bob", reloaded.top(
                RoadRaceScoreRule.ROM_SHA256, 10).getFirst().playerName());
        assertEquals(16_290, reloaded.top(
                RoadRaceScoreRule.ROM_SHA256, 10).getFirst().score());
    }
}
