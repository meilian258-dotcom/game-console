package cn.piq.retro.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerContentPathsTest {
    @TempDir Path root;
    @Test void constructionDoesNotTouchDisk() {
        Path instance = root.resolve("not-created");
        assertEquals(instance.resolve("game-console/piq-sfc-arcade/roms"),
                ServerContentPaths.instanceArea(instance, "piq-sfc-arcade", "roms"));
        assertFalse(Files.exists(instance));
    }
    @Test void scopeKeepsTheExistingRelativeIdentity() {
        assertEquals("world-486ea46224d1bb4fb680f34f7c9ad96a8f24ec88be73ea8e5a6c65260e9cb8a7",
                ServerContentPaths.worldScope(root, root.resolve("world")));
        assertEquals(ServerContentPaths.worldScope(root, root.resolve("saves/测试")),
                ServerContentPaths.worldScope(root.resolve("moved"), root.resolve("moved/saves/测试")));
        assertNotEquals(ServerContentPaths.worldScope(root, root.resolve("a/world")),
                ServerContentPaths.worldScope(root, root.resolve("b/world")));
        assertTrue(ServerContentPaths.worldArea(root, root.resolve("world"), "piq-sfc-home", "roms")
                .startsWith(root.resolve("game-console/world-content")));
    }
    @Test void rejectsEscapingAndUnsupportedSegments() {
        for (String invalid : new String[]{"..", "", "/roms", "a/b", "a\\b", "C:", "CON.txt", "con", "lpt1"}) {
            assertThrows(IllegalArgumentException.class, () -> ServerContentPaths.instanceArea(root, invalid, "roms"));
            assertThrows(IllegalArgumentException.class, () -> ServerContentPaths.worldArea(root, root, "piq-sfc-home", invalid));
        }
    }
}
