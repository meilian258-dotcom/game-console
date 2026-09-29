package cn.piq.fcarcade.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

/** Builds the styled in-game leaderboard while plain formatting stays testable. */
final class ArcadeLeaderboardComponent {
    private ArcadeLeaderboardComponent() {
    }

    static Component create(
            List<ArcadeScoreStore.ScoreEntry> entries,
            int pageStart
    ) {
        MutableComponent text = Component.literal("公路赛车 TOP 10")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        if (entries == null || entries.isEmpty()) {
            return text.append(Component.literal("\n暂无成绩")
                    .withStyle(ChatFormatting.GRAY));
        }

        int entryCount = Math.min(
                entries.size(),
                ArcadeLeaderboardText.LEADERBOARD_LIMIT);
        int start = ArcadeLeaderboardText.clampedPageStart(
                entryCount,
                pageStart);
        int end = Math.min(
                entryCount,
                start + ArcadeLeaderboardText.DISPLAY_LIMIT);
        for (int index = start; index < end; index++) {
            ArcadeScoreStore.ScoreEntry entry = entries.get(index);
            text.append(Component.literal("\n" + (index + 1) + ". ")
                            .withStyle(rankColor(index)))
                    .append(Component.literal(
                                    ArcadeLeaderboardText.safeName(
                                            entry.playerName()))
                            .withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("  " + String.format(
                                    Locale.ROOT,
                                    "%06d",
                                    entry.score()))
                            .withStyle(ChatFormatting.GREEN));
        }
        return text;
    }

    private static ChatFormatting rankColor(int zeroBasedRank) {
        return switch (zeroBasedRank) {
            case 0 -> ChatFormatting.GOLD;
            case 1 -> ChatFormatting.WHITE;
            case 2 -> ChatFormatting.RED;
            default -> ChatFormatting.YELLOW;
        };
    }
}
