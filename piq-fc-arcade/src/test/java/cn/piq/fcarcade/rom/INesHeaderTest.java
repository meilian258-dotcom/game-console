package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class INesHeaderTest {
    @Test
    void parsesMapperZeroINesRom() {
        byte[] rom = nrom(1, 1, 0);
        INesHeader header = INesHeader.parse(rom);

        assertEquals(INesHeader.Format.INES, header.format());
        assertEquals(0, header.mapper());
        assertEquals(16 * 1024, header.prgRomBytes());
        assertEquals(8 * 1024, header.chrRomBytes());
    }

    @Test
    void rejectsWrongMagic() {
        byte[] rom = nrom(1, 1, 0);
        rom[0] = 0;
        assertThrows(IllegalArgumentException.class, () -> INesHeader.parse(rom));
    }

    @Test
    void rejectsTruncatedData() {
        byte[] rom = nrom(1, 1, 0);
        byte[] truncated = java.util.Arrays.copyOf(rom, 128);
        assertThrows(IllegalArgumentException.class, () -> INesHeader.parse(truncated));
    }

    static byte[] nrom(int prgBanks, int chrBanks, int flags6) {
        byte[] rom = new byte[16 + prgBanks * 16 * 1024 + chrBanks * 8 * 1024];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = (byte) prgBanks;
        rom[5] = (byte) chrBanks;
        rom[6] = (byte) flags6;
        return rom;
    }
}
