package cn.piq.fcarcade.session;

import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.core.libretro.LibretroNesCore;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Executable identity gates; native execution is verified separately by the real core probes. */
class LibretroCoreIdentity62Test {
    private static final String ROM="ab".repeat(32);
    private static byte[] state(boolean gun) {
        return ByteBuffer.allocate(83).putInt(0x504C5231).putInt(1).putInt(gun?1:0)
                .put(HexFormat.of().parseHex(LibretroNesCore.PROFILE_SHA256))
                .put(HexFormat.of().parseHex(ROM)).putInt(3).put(new byte[]{4,5,6}).array();
    }
    @Test void ordinalsAppendWithoutChangingHistoricalIdentities() {
        assertEquals(3,NesCoreVariant.LIBRETRO_V1.ordinal());
        assertEquals(4,NesCoreVariant.LIBRETRO_ZAPPER_V1.ordinal());
        assertEquals(NesCoreVariant.LIBRETRO_V1,NesCoreVariant.fromNetwork(3));
        assertEquals(NesCoreVariant.LIBRETRO_ZAPPER_V1,NesCoreVariant.fromNetwork(4));
        assertThrows(IllegalArgumentException.class,()->NesCoreVariant.fromNetwork(5));
        assertThrows(IllegalArgumentException.class,()->NesCoreVariant.fromNetwork(255));
    }
    @Test void gunAndStandardStateProfilesAndAllWasmVariantsAreMutuallyExclusive() {
        for(boolean gun:new boolean[]{false,true}) {
            var expected=gun?NesCoreVariant.LIBRETRO_ZAPPER_V1:NesCoreVariant.LIBRETRO_V1;
            for(var candidate:NesCoreVariant.values())
                assertEquals(candidate==expected,candidate.acceptsStateHeader(state(gun),ROM),candidate.name());
        }
    }
    @Test void rejectsMismatchedMagicVersionModeProfileRomAndInvalidSizes() {
        var variant=NesCoreVariant.LIBRETRO_V1;
        for(int offset:new int[]{0,4,8,12,44}) {
            byte[] bytes=state(false);bytes[offset]^=1;
            assertFalse(variant.acceptsStateHeader(bytes,ROM),"offset="+offset);
        }
        for(int size:new int[]{Integer.MIN_VALUE,-1,0,2,4,16*1024*1024+1}) {
            byte[] bytes=state(false);ByteBuffer.wrap(bytes).putInt(76,size);
            assertFalse(variant.acceptsStateHeader(bytes,ROM),"size="+size);
        }
        assertFalse(variant.acceptsStateHeader(state(false),"cd".repeat(32)));
        assertFalse(variant.acceptsStateHeader(state(false),null));
        assertFalse(variant.acceptsStateHeader(state(false),"invalid"));
        assertTrue(variant.acceptsStateHeader(state(false),ROM.toUpperCase(java.util.Locale.ROOT)));
    }
    @Test void truncationNullAndTrailingBytesCannotPassTheStateGate() {
        for(var variant:new NesCoreVariant[]{NesCoreVariant.LIBRETRO_V1,NesCoreVariant.LIBRETRO_ZAPPER_V1}) {
            byte[] good=state(variant.isZapper());
            assertFalse(variant.acceptsStateHeader(null,ROM));
            for(int length=0;length<good.length;length++)
                assertFalse(variant.acceptsStateHeader(Arrays.copyOf(good,length),ROM),"length="+length);
            assertFalse(variant.acceptsStateHeader(Arrays.copyOf(good,good.length+1),ROM));
        }
    }
    @Test void onlyDeclaredGunVariantsAdvertiseGunAndModuleProfileIsShared() {
        for(var variant:NesCoreVariant.values()) {
            assertEquals(variant==NesCoreVariant.ZAPPER_V1||variant==NesCoreVariant.LIBRETRO_ZAPPER_V1,variant.isZapper());
            assertEquals(variant==NesCoreVariant.LIBRETRO_V1||variant==NesCoreVariant.LIBRETRO_ZAPPER_V1,variant.isLibretro());
        }
        assertEquals(LibretroNesCore.MODULE_RESOURCE,NesCores.moduleResource(NesCoreVariant.LIBRETRO_V1));
        assertEquals(LibretroNesCore.MODULE_RESOURCE,NesCores.moduleResource(NesCoreVariant.LIBRETRO_ZAPPER_V1));
        assertNotEquals(NesCoreVariant.LIBRETRO_V1.saveKey("same-card"),NesCoreVariant.LIBRETRO_ZAPPER_V1.saveKey("same-card"));
    }
}
