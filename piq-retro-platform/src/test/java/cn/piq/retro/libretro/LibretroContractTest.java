// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure Java contract tests; no native code, ROM, socket, or game process is started. */
class LibretroContractTest {
    private static final String SHA = "ab".repeat(32);
    private static final LibretroProfile.Artifact CORE = new LibretroProfile.Artifact("/core/test/core.dll", SHA);
    private static LibretroProfile profile() {
        return new LibretroProfile("Test", "bin", false, List.of(1, 1), false,
                Map.of("region", "NTSC"), Map.of("windows-x64", CORE));
    }

    @Test void pinsResourcePathsAndHashesWithoutAcceptingExternalLibraries() {
        assertDoesNotThrow(() -> new LibretroProfile.Artifact("/core/test/core.so", SHA.toUpperCase()));
        for (String resource : new String[]{"", "core/test.dll", "/tmp/core.dll", "/core/../private.dll",
                "/core\\test.dll", "/core/C:/private.dll", "https://example.invalid/core.dll"}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile.Artifact(resource, SHA), resource);
        }
        for (String hash : new String[]{"", "ab", "g".repeat(64), SHA + "0"}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile.Artifact("/core/test.dll", hash));
        }
    }

    @Test void descriptorCollectionsAreDefensiveAndUnmodifiable() {
        var devices = new ArrayList<>(List.of(1, 1));
        var options = new HashMap<>(Map.of("region", "NTSC"));
        var cores = new HashMap<>(Map.of("windows-x64", CORE));
        var profile = new LibretroProfile("Test", "bin", false, devices, false, options, cores);
        devices.set(0, 0); options.put("region", "PAL"); cores.clear();
        assertEquals(List.of(1, 1), profile.devices());
        assertEquals(Map.of("region", "NTSC"), profile.options());
        assertEquals(Map.of("windows-x64", CORE), profile.cores());
        assertThrows(UnsupportedOperationException.class, () -> profile.devices().add(0));
        assertThrows(UnsupportedOperationException.class, () -> profile.options().put("a", "b"));
        assertThrows(UnsupportedOperationException.class, () -> profile.cores().clear());
    }

    @Test void descriptorRejectsMalformedNamesExtensionsAndOptionText() {
        for (String name : new String[]{"", " ", "x".repeat(129), "bad\0name"}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile(name, "bin", false,
                    List.of(1), false, Map.of(), Map.of("windows-x64", CORE)));
        }
        for (String extension : new String[]{"", "NES", ".nes", "../nes", "x/y", "bin\0"}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Test", extension, false,
                    List.of(1), false, Map.of(), Map.of("windows-x64", CORE)));
        }
        for (Map<String, String> bad : List.of(Map.of("", "value"), Map.of("bad\0", "value"),
                Map.of("key", "bad\0"), Map.of("key", ""), Map.of("key", "x".repeat(513)),
                Map.of("k".repeat(129), "value"))) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Test", "bin", false,
                    List.of(1), false, bad, Map.of("windows-x64", CORE)));
        }
    }

    @Test void descriptorRejectsUnsupportedPortsAndOversizedOptionSets() {
        for (List<Integer> devices : List.of(List.<Integer>of(), List.of(1, 1, 1, 1, 1),
                List.of(-1), List.of(65536), List.of(2), List.of(5), List.of(262))) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Test", "bin", false,
                    devices, false, Map.of(), Map.of("windows-x64", CORE)), devices.toString());
        }
        assertDoesNotThrow(() -> new LibretroProfile("Test", "bin", false,
                List.of(0, 1, 257, 513), false, Map.of(), Map.of("windows-x64", CORE)));
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i < 257; i++) options.put("k" + i, "v");
        assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Test", "bin", false,
                List.of(1), false, options, Map.of("windows-x64", CORE)));
        assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Test", "bin", false,
                List.of(1), false, Map.of(), Map.of()));
    }

    @Test void lightGunRequiresAnExplicitMatchingMesenDescriptor() {
        assertDoesNotThrow(() -> new LibretroProfile("Mesen", "nes", true,
                List.of(257, 262), true, Map.of(), Map.of("windows-x64", CORE)));
        assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Other", "nes", true,
                List.of(257, 262), true, Map.of(), Map.of("windows-x64", CORE)));
        assertThrows(IllegalArgumentException.class, () -> new LibretroProfile("Mesen", "nes", true,
                List.of(257, 257), true, Map.of(), Map.of("windows-x64", CORE)));
    }

    @Test void metadataRetainsSoftwareFormatGeometryAndNativeStereoTiming() {
        for (int format = 0; format < 3; format++) {
            var info = new LibretroProcess.Info(320, 240, 1024, 512, 4f / 3f, 50, 48000, format);
            assertEquals(320, info.width()); assertEquals(240, info.height());
            assertEquals(50, info.fps()); assertEquals(48000, info.sampleRate()); assertEquals(format, info.pixelFormat());
        }
        assertDoesNotThrow(() -> new LibretroProcess.Info(4096, 2048, 4096, 2048, 2, 240, 192000, 1));
    }

    @Test void metadataRejectsDimensionsOverflowsAndInvalidTiming() {
        for (int width : new int[]{-1, 0, 257, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(width, 240, 256, 240, 1, 60, 44100, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 4096, 4096, 1, 60, 44100, 1));
        assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 256, 239, 1, 60, 44100, 1));
        for (double fps : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, 240.1}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 256, 240, 1, fps, 44100, 1));
        }
        for (double rate : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, 7999, 192001}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 256, 240, 1, 60, rate, 1));
        }
        for (float aspect : new float[]{Float.NaN, Float.POSITIVE_INFINITY, -1, 101}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 256, 240, aspect, 60, 44100, 1));
        }
        for (int format : new int[]{-1, 3, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new LibretroProcess.Info(256, 240, 256, 240, 1, 60, 44100, format));
        }
    }

    @Test void controlsDoNotAliasCallerOrReturnedPadArrays() {
        int[] pads = {1, 65535};
        var controls = new LibretroProcess.Controls(pads, 65536);
        pads[0] = 4;
        int[] returned = controls.pads(); returned[1] = 0;
        assertArrayEquals(new int[]{1, 65535}, controls.pads());
        assertEquals(65536, controls.gun());
    }

    @Test void unloadedAndClosedCallsFailBeforeNativeStartup() {
        try (var process = new LibretroProcess(profile())) {
            assertThrows(IllegalStateException.class, process::info);
            assertThrows(IllegalStateException.class, process::coreVersion);
            assertThrows(IllegalStateException.class, process::serialize);
            assertThrows(IllegalStateException.class, process::reset);
            assertThrows(IllegalStateException.class, () -> process.run(List.of(new LibretroProcess.Controls(new int[]{0, 0}, 0)), 3));
            assertThrows(IllegalArgumentException.class, () -> process.load(null));
            assertThrows(IllegalArgumentException.class, () -> process.load(new byte[0]));
            process.close();
            assertDoesNotThrow(process::close);
            assertThrows(IllegalStateException.class, () -> process.load(new byte[]{1}));
            assertThrows(IllegalStateException.class, () -> process.restore(new byte[]{1}));
            assertThrows(IllegalStateException.class, () -> process.memory(2));
        }
    }

    @Test void wrongThreadCannotStartUseOrCloseOwnedProcess() throws Exception {
        try (var process = new LibretroProcess(profile())) {
            CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
                assertTrue(assertThrows(IllegalStateException.class, () -> process.load(new byte[]{1})).getMessage().contains("owning thread"));
                assertTrue(assertThrows(IllegalStateException.class, process::info).getMessage().contains("owning thread"));
                assertTrue(assertThrows(IllegalStateException.class, process::close).getMessage().contains("owning thread"));
            });
            result.get(5, TimeUnit.SECONDS);
            assertDoesNotThrow(process::close);
        }
    }
}
