package cn.piq.nativearcade.bridge;

import cn.piq.fcarcade.netplay.NetplayProcess;
import cn.piq.retro.libretro.LibretroContentFiles;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRetroArchMediaSessionTest {
    @Test void profilePreservesFixedRepairedMameAndExplicitRuntimeDependency() {
        var config = NativeRetroArchMediaSession.config("diagnostic.zip");
        var profile = config.profile();
        assertNull(profile.jni(), "Explicit RetroArch must never silently create a JNI session");
        assertEquals(NativeJniMediaSession.profile().cores().get("windows-x64"), config.core());
        assertEquals(NativeJniMediaSession.profile().options(), profile.options());
        assertEquals(309413888, config.core().maxBytes());
        assertEquals(4, profile.ports());
        assertEquals(48000, profile.sampleRate());
        assertEquals(LibretroContentFiles.MAX_MAIN, profile.maxRomBytes());
        assertEquals(1, config.dependencies().size());
        assertEquals("libc++.dll", config.dependencies().getFirst().fileName());
        assertEquals(1659392, config.dependencies().getFirst().bytes());
        for (String invalid : new String[]{"../dino.zip", "Dino.zip", "dino", "dino.zip.exe", "CON.zip"})
            assertThrows(IllegalArgumentException.class, () -> NativeRetroArchMediaSession.config(invalid));
    }
    @Test void presentationPreservesAlreadyNormalizedPnp7AspectWithoutReversingPixels() {
        for (int rotation = 0; rotation < 4; rotation++) {
            var frame = new NetplayProcess.Frame(1, new byte[]{1,2,3,(byte)255,4,5,6,(byte)255},
                    new float[0], 2, 1, .75f, new short[0], 48000, rotation);
            var result = NativeRetroArchMediaSession.picture(frame);
            assertArrayEquals(new int[]{0xff030201,0xff060504}, result.abgr());
            assertEquals(rotation, result.rotation());
            assertEquals(.75f, result.displayAspect(), 1e-6f);
        }
    }
}
