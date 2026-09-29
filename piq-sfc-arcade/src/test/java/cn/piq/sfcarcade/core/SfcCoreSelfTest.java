// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public final class SfcCoreSelfTest {
    private SfcCoreSelfTest() {
    }

    public static void main(String[] args) {
        testControllerMask();
        testRawRom();
        testCopierHeaderRemoval();
        testInvalidRom();
        testDynamicVideoContract();
        testAudioContract();
        testPackagedWasmCore();
        System.out.println("SFC core contract self-test passed (ABI v" + SfcCoreAbi.VERSION + ")");
    }

    private static void testControllerMask() {
        SfcControllerState state = SfcControllerState.of(SfcButton.UP, SfcButton.A, SfcButton.L);
        require(state.pressed(SfcButton.UP), "UP should be pressed");
        require(state.pressed(SfcButton.A), "A should be pressed");
        require(state.pressed(SfcButton.L), "L should be pressed");
        require(!state.pressed(SfcButton.DOWN), "DOWN should not be pressed");
        require(state.with(SfcButton.UP, false).mask()
                        == (SfcButton.A.mask() | SfcButton.L.mask()),
                "Clearing a button changed unrelated bits");
    }

    private static void testRawRom() {
        byte[] raw = deterministicRom();
        SfcRomImage image = SfcRomImage.fromBytes(raw);
        require(!image.hadCopierHeader(), "Raw .sfc image was misdetected as headered");
        require(image.payloadLength() == raw.length, "Raw payload length changed");
        require(Arrays.equals(raw, image.copyPayload()), "Raw payload bytes changed");
        require(image.sha256().length() == 64, "SHA-256 should have 64 hex characters");
    }

    private static void testCopierHeaderRemoval() {
        byte[] raw = deterministicRom();
        byte[] headered = new byte[raw.length + SfcRomImage.COPIER_HEADER_BYTES];
        Arrays.fill(headered, 0, SfcRomImage.COPIER_HEADER_BYTES, (byte) 0x5A);
        System.arraycopy(raw, 0, headered, SfcRomImage.COPIER_HEADER_BYTES, raw.length);

        SfcRomImage image = SfcRomImage.fromBytes(headered);
        require(image.hadCopierHeader(), "512-byte copier header was not detected");
        require(image.removedHeaderBytes() == 512, "Wrong removed header size");
        require(Arrays.equals(raw, image.copyPayload()), "Copier header removal damaged payload");
        require(image.sha256().equals(SfcRomImage.fromBytes(raw).sha256()),
                "Headered and raw forms should identify the same ROM");
    }

    private static void testInvalidRom() {
        boolean rejected = false;
        try {
            SfcRomImage.fromBytes(new byte[1024]);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "Undersized ROM was accepted");
    }

    private static void testDynamicVideoContract() {
        SfcVideoMode lowResolution = new SfcVideoMode(256, 224, 1024, 8.0 / 7.0, 60.0988);
        SfcVideoMode highInterlaced = new SfcVideoMode(512, 478, 2048, 4.0 / 7.0, 50.007);
        require(lowResolution.requiredRgbaBytes() == 256 * 224 * 4, "Low-res RGBA size mismatch");
        require(highInterlaced.requiredRgbaBytes() == 512 * 478 * 4, "Hi-res RGBA size mismatch");
    }

    private static void testAudioContract() {
        SfcFrameResult result = new SfcFrameResult(
                new SfcVideoMode(256, 224, 1024, 8.0 / 7.0, 60.0988),
                799,
                10
        );
        require(result.requiredPcmShorts() == 1598, "Stereo sample frame conversion is wrong");
        require(SfcFrameResult.AUDIO_SAMPLE_RATE == 48_000, "Unexpected ABI audio rate");
    }

    private static void testPackagedWasmCore() {
        String resource = "/assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm";
        try (InputStream input = SfcCoreSelfTest.class.getResourceAsStream(resource)) {
            require(input != null, "Compiled jgenesis WASM core is missing from resources");
            byte[] bytes = input.readAllBytes();
            require(bytes.length > 1_000_000, "Compiled WASM core is unexpectedly small");
            require(bytes[0] == 0x00 && bytes[1] == 0x61 && bytes[2] == 0x73 && bytes[3] == 0x6D,
                    "Packaged core does not have a WebAssembly magic header");
            require(bytes[4] == 0x01 && bytes[5] == 0x00 && bytes[6] == 0x00 && bytes[7] == 0x00,
                    "Packaged core uses an unsupported WebAssembly binary version");
        } catch (IOException exception) {
            throw new AssertionError("Unable to inspect packaged WASM core", exception);
        }
    }

    private static byte[] deterministicRom() {
        byte[] bytes = new byte[SfcRomImage.MIN_ROM_BYTES];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
