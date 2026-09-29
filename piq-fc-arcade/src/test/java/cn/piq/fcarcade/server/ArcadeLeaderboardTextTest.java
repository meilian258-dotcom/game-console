package cn.piq.fcarcade.server;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcadeLeaderboardTextTest {
    @Test
    void formatsFirstThreeRowsOfTopTen() {
        List<ArcadeScoreStore.ScoreEntry> entries = List.of(
                entry("Alice", 16_280),
                entry("Bob", 9_500),
                entry("Carol", 800),
                entry("Hidden", 700));

        String text = ArcadeLeaderboardText.create(entries);

        assertEquals(
                "公路赛车 TOP 10\n"
                        + "1. Alice  016280\n"
                        + "2. Bob  009500\n"
                        + "3. Carol  000800",
                text);
        assertFalse(text.contains("Hidden"));
    }

    @Test
    void groupsThreeRowsWhileKeepingGlobalRanks() {
        List<ArcadeScoreStore.ScoreEntry> entries = List.of(
                entry("P1", 10_000),
                entry("P2", 9_000),
                entry("P3", 8_000),
                entry("P4", 7_000),
                entry("P5", 6_000),
                entry("P6", 5_000),
                entry("P7", 4_000),
                entry("P8", 3_000),
                entry("P9", 2_000),
                entry("P10", 1_000));

        String middle = ArcadeLeaderboardText.create(entries, 3);
        String end = ArcadeLeaderboardText.create(entries, 9);

        assertTrue(middle.contains("4. P4  007000"));
        assertTrue(middle.contains("6. P6  005000"));
        assertFalse(middle.contains("1. P1"));
        assertTrue(end.contains("10. P10  001000"));
        assertFalse(end.contains("9. P9"));
    }

    @Test
    void stripsControlsAndHandlesAnEmptyBoard() {
        String populated = ArcadeLeaderboardText.create(List.of(
                entry("玩家\n名字", 1)));
        String empty = ArcadeLeaderboardText.create(List.of());

        assertTrue(populated.contains("玩家名字"));
        assertTrue(empty.contains("暂无成绩"));
    }

    private static ArcadeScoreStore.ScoreEntry entry(String name, int score) {
        return new ArcadeScoreStore.ScoreEntry(
                UUID.randomUUID(),
                name,
                score,
                1L);
    }
}
