package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RomSaveModeTest {
    @Test
    void cyclesAllAdministratorChoices() {
        assertEquals(RomSaveMode.PLAYER, RomSaveMode.NONE.next());
        assertEquals(RomSaveMode.MACHINE, RomSaveMode.PLAYER.next());
        assertEquals(RomSaveMode.NONE, RomSaveMode.MACHINE.next());
    }

    @Test
    void rejectsUnknownNetworkIds() {
        assertThrows(IllegalArgumentException.class, () -> RomSaveMode.fromId(-1));
        assertThrows(IllegalArgumentException.class, () -> RomSaveMode.fromId(3));
    }
}
