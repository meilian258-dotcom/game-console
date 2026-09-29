package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RomCatalogEntryTest {
    private static final String SHA = "a".repeat(64);

    @Test
    void acceptsSingleAndTwoPlayerMetadata() {
        assertEquals(1, entry(1).maxPlayers());
        assertEquals(2, entry(2).maxPlayers());
    }

    @Test
    void rejectsPlayerCountsOutsideNesControllerLimit() {
        assertThrows(IllegalArgumentException.class, () -> entry(0));
        assertThrows(IllegalArgumentException.class, () -> entry(3));
    }

    private static RomCatalogEntry entry(int maxPlayers) {
        return new RomCatalogEntry(
                "test.nes",
                SHA,
                16,
                0,
                maxPlayers,
                RomSaveMode.NONE);
    }
}
