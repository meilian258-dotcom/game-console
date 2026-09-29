// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade.client;

import cn.piq.nativearcade.NativeNetplayProfile;
import cn.piq.retro.libretro.LibretroRuntimes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Declaration/default-safety checks only. This test never loads or executes a native DLL. */
class NativeJniTrialProfileTest {
    @Test void pinnedProfileKeepsFourDigitalPortsAndFullPathZipIdentity() {
        var profile = NativeJniTrialProfile.profile();
        assertEquals("FinalBurn Neo", profile.name());
        assertEquals("zip", profile.extension());
        assertTrue(profile.fullPath());
        assertFalse(profile.mesenGun());
        assertEquals(List.of(1, 1, 1, 1), profile.devices());
        assertEquals(1, profile.cores().size());
        var artifact = profile.cores().get("windows-x64");
        assertEquals(NativeNetplayProfile.CORE_RESOURCE, artifact.resource());
        assertEquals(NativeNetplayProfile.CORE_SHA, artifact.sha256());
        assertThrows(UnsupportedOperationException.class, () -> profile.devices().set(2, 0));
    }

    @Test void probeDoesNotInheritNetplayMaintenanceOrUnboundedOptionOverrides() {
        var options = NativeJniTrialProfile.profile().options();
        assertEquals("None", options.get("fbneo-diagnostic-input"));
        assertEquals("disabled", options.get("fbneo-allow-patched-romsets"));
        // These are not offered by the pinned core's actual KOV options declaration.
        assertFalse(options.containsKey("fbneo-hiscores"));
        assertFalse(options.containsKey("fbneo-frameskip-type"));
        assertEquals("0", options.get("fbneo-fixed-frameskip"));
        assertEquals("disabled", options.get("fbneo-force-60hz"));
        assertEquals("48000", options.get("fbneo-samplerate"));
        assertEquals(5, options.size());
        assertThrows(UnsupportedOperationException.class,
                () -> options.put("fbneo-diagnostic-input", "Hold Start + L + R"));
    }

    @Test void explicitFactoryIsLazyAndOrdinaryCabinetRemainsMame() throws Exception {
        boolean wasBusy = LibretroRuntimes.isJniBusy();
        try (var runtime = NativeJniTrialProfile.createOnOwnerThread()) {
            assertEquals(LibretroRuntimes.Backend.JNI_TRIAL, runtime.backend());
            assertEquals(wasBusy, LibretroRuntimes.isJniBusy(), "Construction must not open native code");
        }
        assertEquals(wasBusy, LibretroRuntimes.isJniBusy());
        String provider = Files.readString(Path.of("src/main/java/cn/piq/nativearcade/client/NativeCabinetBackend.java"));
        assertTrue(provider.contains("new NativeJniMediaSession("));
        var media = cn.piq.nativearcade.bridge.NativeJniMediaSession.profile();
        assertEquals("MAME", media.name());
        assertEquals(372431360, media.cores().get("windows-x64").maxBytes());
        assertFalse(provider.contains("NativeJniTrialProfile"));
        assertFalse(provider.contains("LibretroJniRuntime"));
    }
}
