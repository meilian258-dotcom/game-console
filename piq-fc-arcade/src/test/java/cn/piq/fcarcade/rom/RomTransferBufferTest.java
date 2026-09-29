package cn.piq.fcarcade.rom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RomTransferBufferTest {
    @Test
    void assemblesSequentialChunks() {
        RomTransferBuffer buffer =
                new RomTransferBuffer("game.nes", "a".repeat(64), 5);
        buffer.append(0, new byte[]{1, 2});
        assertFalse(buffer.complete());
        buffer.append(2, new byte[]{3, 4, 5});
        assertTrue(buffer.complete());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, buffer.completedBytes());
    }

    @Test
    void rejectsGapsOverlapAndOverflow() {
        RomTransferBuffer buffer =
                new RomTransferBuffer("game.nes", "a".repeat(64), 4);
        assertThrows(
                IllegalArgumentException.class,
                () -> buffer.append(1, new byte[]{1}));
        buffer.append(0, new byte[]{1, 2, 3});
        assertThrows(
                IllegalArgumentException.class,
                () -> buffer.append(2, new byte[]{4}));
        assertThrows(
                IllegalArgumentException.class,
                () -> buffer.append(3, new byte[]{4, 5}));
    }
}
