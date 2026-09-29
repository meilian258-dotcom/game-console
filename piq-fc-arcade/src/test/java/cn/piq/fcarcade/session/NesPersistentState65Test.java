package cn.piq.fcarcade.session;

import cn.piq.fcarcade.core.libretro.LibretroNesCore;
import cn.piq.retro.libretro.LibretroSaveMemory;
import java.nio.ByteBuffer;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NesPersistentState65Test {
    private final String rom = "a".repeat(64);
    private byte[] snapshot() {
        return ByteBuffer.allocate(81).putInt(0x504c5231).putInt(1).putInt(0)
            .put(HexFormat.of().parseHex(LibretroNesCore.PROFILE_SHA256)).put(HexFormat.of().parseHex(rom)).putInt(1).put((byte)1).array();
    }
    private byte[] bundle() { return NesPersistentState.encode(snapshot(), new byte[32], new LibretroSaveMemory(new byte[]{5}, new byte[]{6})); }
    @Test void roundTripAndLiveSnapshotRemainSeparate() {
        byte[] value=bundle(); var parts=NesPersistentState.decode(value);
        assertArrayEquals(snapshot(),parts.snapshot()); assertArrayEquals(new byte[]{5},parts.memory().ram());
        assertArrayEquals(new byte[]{6},parts.memory().rtc()); assertArrayEquals(snapshot(),NesPersistentState.snapshot(value));
        assertTrue(NesCoreVariant.LIBRETRO_V1.acceptsPersistentStateHeader(value,rom));
        assertFalse(NesCoreVariant.LIBRETRO_V1.acceptsStateHeader(value,rom));
        assertFalse(NesCoreVariant.LEGACY.acceptsStateHeader(value,rom));
        assertFalse(NesCoreVariant.LEGACY.acceptsPersistentStateHeader(value,rom));
        assertFalse(NesCoreVariant.LIBRETRO_ZAPPER_V1.acceptsPersistentStateHeader(value,rom));
        assertFalse(NesCoreVariant.LIBRETRO_V1.acceptsPersistentStateHeader(value,"b".repeat(64)));
    }
    @Test void corruptionTruncationAndOversizeRejectWithoutFallback() {
        byte[] value=bundle(); value[55]^=1;
        assertThrows(IllegalArgumentException.class,()->NesPersistentState.snapshot(value));
        assertFalse(NesCoreVariant.LIBRETRO_V1.acceptsPersistentStateHeader(value,rom));
        assertThrows(IllegalArgumentException.class,()->NesPersistentState.decode(Arrays.copyOf(bundle(),60)));
        assertThrows(IllegalArgumentException.class,()->NesPersistentState.encode(new byte[NesPersistentState.MAX_BYTES],new byte[32],new LibretroSaveMemory(new byte[0],new byte[0])));
        byte[] negative=bundle(); ByteBuffer.wrap(negative).putInt(40,-1);
        assertThrows(IllegalArgumentException.class,()->NesPersistentState.decode(negative));
    }
    @Test void oldDataIsUnchangedAndCopiesAreDetached() {
        byte[] old=snapshot(); assertArrayEquals(old,NesPersistentState.snapshot(old));
        assertTrue(NesCoreVariant.LIBRETRO_V1.acceptsPersistentStateHeader(old,rom));
        byte[] input=bundle(); var parts=NesPersistentState.decode(input); input[53]^=1;
        byte[] copy=parts.snapshot(); copy[0]=0; assertArrayEquals(old,parts.snapshot());
        parts.identity()[0]=1; assertEquals(0,parts.identity()[0]);
        assertThrows(IllegalArgumentException.class,()->NesPersistentState.encode(bundle(),new byte[32],parts.memory()));
    }
}
