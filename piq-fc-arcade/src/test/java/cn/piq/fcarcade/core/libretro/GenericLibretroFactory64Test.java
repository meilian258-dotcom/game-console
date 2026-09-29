package cn.piq.fcarcade.core.libretro;

import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.core.NesCores;
import cn.piq.fcarcade.session.NesCoreVariant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenericLibretroFactory64Test {
    @Test void publicAndPrivateFactoryUseGenericBridgeForBothLibretroVariants() {
        for (NesCoreVariant variant : new NesCoreVariant[]{NesCoreVariant.LIBRETRO_V1, NesCoreVariant.LIBRETRO_ZAPPER_V1}) {
            try (NesCore core = NesCores.create(variant)) {
                assertInstanceOf(GenericLibretroNesCore.class, core);
                assertEquals(variant.stateNamespace(), core.stateNamespace());
                assertEquals(variant == NesCoreVariant.LIBRETRO_ZAPPER_V1, core.supportsZapper());
                assertEquals(LibretroNesCore.MODULE_RESOURCE, NesCores.moduleResource(variant));
            }
        }
    }

    @Test void profileKeepsFixedMesenOptionsAndPortSubtypes() {
        var ordinary = GenericLibretroNesCore.profile(false);
        var lightGun = GenericLibretroNesCore.profile(true);
        assertEquals("Mesen", ordinary.name());
        assertTrue(ordinary.fullPath());
        assertEquals(java.util.List.of(257, 257), ordinary.devices());
        assertEquals(java.util.List.of(257, 262), lightGun.devices());
        assertEquals(ordinary.options(), lightGun.options());
        assertEquals("44100", ordinary.options().get("mesen_audio_sample_rate"));
        assertEquals("NTSC", ordinary.options().get("mesen_region"));
        assertEquals("Disabled", ordinary.options().get("mesen_ntsc_filter"));
        assertEquals(2, ordinary.cores().size());
    }
}
