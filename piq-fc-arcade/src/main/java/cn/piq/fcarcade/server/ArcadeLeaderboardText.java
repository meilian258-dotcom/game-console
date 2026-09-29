package cn.piq.fcarcade.server;

import java.util.List;
import java.util.Locale;

/** Builds the compact, runtime-independent Road Fighter leaderboard text. */
public final class ArcadeLeaderboardText {
    public static final int DISPLAY_LIMIT = 3;
    public static final int LEADERBOARD_LIMIT = 10;

    private ArcadeLeaderboardText() {
    }

    static String create(List<ArcadeScoreStore.ScoreEntry> entries) {
        return create(entries, 0);
    }

    static String create(
            List<ArcadeScoreStore.ScoreEntry> entries,
            int pageStart
    ) {
        StringBuilder text = new StringBuilder("公路赛车 TOP 10");
        if (entries == null || entries.isEmpty()) {
            return text.append("\n暂无成绩").toString();
        }
        int entryCount = Math.min(entries.size(), LEADERBOARD_LIMIT);
        int start = clampedPageStart(entryCount, pageStart);
        int end = Math.min(entryCount, start + DISPLAY_LIMIT);
        for (int index = start; index < end; index++) {
            ArcadeScoreStore.ScoreEntry entry = entries.get(index);
            text.append('\n')
                    .append(index + 1)
                    .append(". ")
                    .append(safeName(entry.playerName()))
                    .append("  ")
                    .append(String.format(Locale.ROOT, "%06d", entry.score()));
        }
        return text.toString();
    }

    static int clampedPageStart(int entryCount, int pageStart) {
        int maximumStart = ((entryCount - 1) / DISPLAY_LIMIT) * DISPLAY_LIMIT;
        return Math.max(0, Math.min(pageStart, maximumStart));
    }

    static String safeName(String value) {
        if (value == null || value.isBlank()) return "玩家";
        StringBuilder safe = new StringBuilder();
        value.codePoints()
                .filter(codePoint -> !Character.isISOControl(codePoint))
                .limit(12)
                .forEach(safe::appendCodePoint);
        return safe.isEmpty() ? "玩家" : safe.toString();
    }
}
