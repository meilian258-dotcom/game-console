package cn.piq.fcarcade.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkinTransferBufferTest {
    @Test
    void acceptsContiguousChunks() {
        SkinTransferBuffer buffer = new SkinTransferBuffer(
                "测试皮肤",
                "a".repeat(64),
                5);
        buffer.append(0, new byte[]{1, 2});
        buffer.append(2, new byte[]{3, 4, 5});

        assertTrue(buffer.complete());
        assertArrayEquals(
                new byte[]{1, 2, 3, 4, 5},
                buffer.completedBytes());
    }

    @Test
    void rejectsOutOfOrderChunk() {
        SkinTransferBuffer buffer = new SkinTransferBuffer(
                "测试皮肤",
                "b".repeat(64),
                5);
        assertThrows(
                IllegalArgumentException.class,
                () -> buffer.append(1, new byte[]{1}));
    }

    @Test
    void acceptsBackloggedChunksWithoutDependingOnServerTickSpacing() {
        int totalBytes = SkinTransferLimits.CHUNK_BYTES * 12;
        SkinTransferBuffer buffer = new SkinTransferBuffer("积压上传", "c".repeat(64), totalBytes);
        byte[] chunk = new byte[SkinTransferLimits.CHUNK_BYTES];
        // More than four packets can be drained during one server tick after
        // a stall. The bounded transfer requires no wait between appends.
        for (int offset = 0; offset < totalBytes; offset += chunk.length) {
            buffer.append(offset, chunk);
        }
        assertTrue(buffer.complete());
        assertEquals(totalBytes, buffer.receivedBytes());
        assertEquals(totalBytes, buffer.completedBytes().length);
    }

    @Test
    void burstCannotAppendPastItsReservedTotalOrReuseEarlierOffsets() {
        SkinTransferBuffer buffer = new SkinTransferBuffer("边界上传", "d".repeat(64), 6);
        for (int offset = 0; offset < 6; offset++) buffer.append(offset, new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> buffer.append(6, new byte[]{2}));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(5, new byte[]{2}));
        assertEquals(6, buffer.receivedBytes());
    }

    @Test
    void burstStillRejectsOversizedEmptyAndSkippedChunksWithoutProgress() {
        SkinTransferBuffer buffer = new SkinTransferBuffer("受限上传", "e".repeat(64),
                SkinTransferLimits.CHUNK_BYTES * 2);
        assertThrows(IllegalArgumentException.class,
                () -> buffer.append(0, new byte[SkinTransferLimits.CHUNK_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> buffer.append(1, new byte[]{1}));
        assertEquals(0, buffer.receivedBytes());
    }
}
