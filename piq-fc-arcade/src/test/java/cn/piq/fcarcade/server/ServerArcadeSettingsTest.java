package cn.piq.fcarcade.server;

import cn.piq.fcarcade.config.ArcadeGlobalSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerArcadeSettingsTest {
    @TempDir
    Path temporary;

    @Test
    void persistsAndReloadsGlobalSettings() {
        Path path = temporary.resolve("piq_fc_arcade-server.properties");
        ServerArcadeSettings first = new ServerArcadeSettings(path);

        assertEquals(ArcadeGlobalSettings.DEFAULT, first.get());
        assertEquals(3, first.leaderboardPageSeconds());
        first.set(new ArcadeGlobalSettings(24, 12, 65, 45));
        first.setLeaderboardPageSeconds(7);

        ServerArcadeSettings reloaded = new ServerArcadeSettings(path);
        assertEquals(new ArcadeGlobalSettings(24, 12, 65, 45), reloaded.get());
        assertEquals(7, reloaded.leaderboardPageSeconds());
        assertThrows(
                IllegalArgumentException.class,
                () -> reloaded.setLeaderboardPageSeconds(0));
    }
}
