package cn.piq.fcarcade.core.wasm;

import cn.piq.fcarcade.core.NesButton;
import cn.piq.fcarcade.core.NesCore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WasmNesCoreTest {
    @Test
    void loadsOwnedNromAndProducesFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            byte[] frame = new byte[NesCore.RGBA_BYTES];
            try (WasmNesCore core = new WasmNesCore()) {
                core.loadRom(minimalLoopRom());
                core.setControllerState(0, NesButton.A.mask() | NesButton.RIGHT.mask());
                for (int frameNumber = 0; frameNumber < 30; frameNumber++) {
                    core.runFrame();
                }
                core.setControllerState(0, 0);
                core.copyFrameRgba(frame);
            }

            assertEquals(NesCore.RGBA_BYTES, frame.length);
            int opaquePixels = 0;
            for (int i = 3; i < frame.length; i += 4) {
                if (Byte.toUnsignedInt(frame[i]) == 255) opaquePixels++;
            }
            assertTrue(opaquePixels > 0, "模拟器应输出带不透明 Alpha 的 RGBA 像素");
        });
    }

    @Test
    void validatesFrameBufferSize() {
        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(minimalLoopRom());
            assertDoesNotThrow(core::runFrame);
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> core.copyFrameRgba(new byte[8])
            );
        }
    }

    @Test
    void copiesInternalCpuRamAndValidatesBufferSize() {
        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(minimalLoopRom());
            core.runFrame();
            byte[] ram = new byte[NesCore.CPU_RAM_BYTES];
            assertDoesNotThrow(() -> core.copyCpuRam(ram));
            assertEquals(NesCore.CPU_RAM_BYTES, ram.length);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> core.copyCpuRam(new byte[NesCore.CPU_RAM_BYTES - 1]));
        }
    }

    @Test
    void acceptsSecondControllerState() {
        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(minimalLoopRom());
            assertDoesNotThrow(() -> core.setControllerState(
                    1,
                    NesButton.A.mask()
                            | NesButton.B.mask()
                            | NesButton.UP.mask()
                            | NesButton.RIGHT.mask()));
            assertDoesNotThrow(core::runFrame);
            assertDoesNotThrow(() -> core.setControllerState(1, 0));
        }
    }

    @Test
    void identicalCoresRemainDeterministicWithTwoPlayerInput() {
        byte[] firstFrame = new byte[NesCore.RGBA_BYTES];
        byte[] secondFrame = new byte[NesCore.RGBA_BYTES];
        try (WasmNesCore first = new WasmNesCore();
             WasmNesCore second = new WasmNesCore()) {
            byte[] rom = minimalLoopRom();
            first.loadRom(rom);
            second.loadRom(rom);
            for (int frame = 0; frame < 180; frame++) {
                int playerOne = frame % 40 < 20
                        ? NesButton.RIGHT.mask() | NesButton.A.mask()
                        : NesButton.LEFT.mask();
                int playerTwo = frame % 60 < 30
                        ? NesButton.UP.mask() | NesButton.B.mask()
                        : NesButton.DOWN.mask();
                first.setControllerState(0, playerOne);
                second.setControllerState(0, playerOne);
                first.setControllerState(1, playerTwo);
                second.setControllerState(1, playerTwo);
                first.runFrame();
                second.runFrame();
            }
            first.copyFrameRgba(firstFrame);
            second.copyFrameRgba(secondFrame);
        }
        assertArrayEquals(firstFrame, secondFrame);
    }

    @Test
    void transientStateRestoresASecondCoreWithoutRomBytes() {
        byte[] continuedFrame = new byte[NesCore.RGBA_BYTES];
        byte[] restoredFrame = new byte[NesCore.RGBA_BYTES];
        byte[] rom = minimalLoopRom();
        try (WasmNesCore continued = new WasmNesCore();
             WasmNesCore restored = new WasmNesCore()) {
            continued.loadRom(rom);
            restored.loadRom(rom);
            runInputSequence(continued, 0, 180);

            byte[] state = continued.saveTransientState();
            assertTrue(state.length < 1024 * 1024, "压缩临时状态不应接近完整 WASM 内存");
            assertFalse(containsSequence(state, rom), "临时状态不得包含完整 ROM");
            restored.loadTransientState(state);

            runInputSequence(continued, 180, 360);
            runInputSequence(restored, 180, 360);
            continued.copyFrameRgba(continuedFrame);
            restored.copyFrameRgba(restoredFrame);
        }
        assertArrayEquals(continuedFrame, restoredFrame);
    }

    @Test
    void producesFrameSizedAudioBatches() {
        float[] samples = new float[4096];
        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(minimalLoopRom());
            core.runFrame();
            int count = core.copyAudioSamples(samples);
            assertTrue(count >= 700 && count <= 800,
                    "NTSC 一帧应产生约 735 个 44.1 kHz 采样，实际为 " + count);
            for (int i = 0; i < count; i++) {
                assertTrue(Float.isFinite(samples[i]), "音频采样必须是有限浮点数");
            }
            assertEquals(0, core.copyAudioSamples(samples), "音频缓冲区读取后应被排空");
        }
    }

    @Test
    void reportsUnsupportedMapperBeforeEnteringWasm() {
        byte[] rom = minimalLoopRom();
        rom[6] = (byte) 0x50;
        try (WasmNesCore core = new WasmNesCore()) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> core.loadRom(rom));
            assertTrue(error.getMessage().contains("Mapper 5"));
        }
    }

    @Test
    void loadsSyntheticMapper140RomAndProducesFrames() {
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            byte[] frame = new byte[NesCore.RGBA_BYTES];
            try (WasmNesCore core = new WasmNesCore()) {
                core.loadRom(mapper140LoopRom());
                for (int frameNumber = 0; frameNumber < 30; frameNumber++) {
                    core.runFrame();
                }
                core.copyFrameRgba(frame);
            }

            int opaquePixels = 0;
            for (int i = 3; i < frame.length; i += 4) {
                if (Byte.toUnsignedInt(frame[i]) == 255) opaquePixels++;
            }
            assertTrue(opaquePixels > 0, "Mapper 140 合成 ROM 应输出有效 RGBA 画面");
        });
    }

    @Test
    void removesTrainerBeforePassingRomToLegacyCore() {
        byte[] source = minimalLoopRom();
        byte[] trained = new byte[source.length + 512];
        System.arraycopy(source, 0, trained, 0, 16);
        trained[6] = 0x04;
        System.arraycopy(source, 16, trained, 16 + 512, source.length - 16);

        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(trained);
            assertDoesNotThrow(core::runFrame);
        }
    }

    @Test
    void profilesFrameExecutionForDevelopment() {
        byte[] frame = new byte[NesCore.RGBA_BYTES];
        long[] runSamples = new long[120];
        long[] copySamples = new long[120];
        long started = System.nanoTime();
        try (WasmNesCore core = new WasmNesCore()) {
            core.loadRom(minimalLoopRom());
            for (int i = 0; i < 10; i++) core.runFrame();
            long initialized = System.nanoTime();
            for (int i = 0; i < runSamples.length; i++) {
                long frameStarted = System.nanoTime();
                core.runFrame();
                runSamples[i] = System.nanoTime() - frameStarted;
                long copyStarted = System.nanoTime();
                core.copyFrameRgba(frame);
                copySamples[i] = System.nanoTime() - copyStarted;
            }
            Arrays.sort(runSamples);
            Arrays.sort(copySamples);
            double initializationMs = (initialized - started) / 1_000_000.0;
            double runAverageMs = Arrays.stream(runSamples).average().orElseThrow() / 1_000_000.0;
            double runP95Ms = runSamples[(int) (runSamples.length * 0.95) - 1] / 1_000_000.0;
            double copyAverageMs = Arrays.stream(copySamples).average().orElseThrow() / 1_000_000.0;
            double copyP95Ms = copySamples[(int) (copySamples.length * 0.95) - 1] / 1_000_000.0;
            System.out.printf(
                    "NES profile: initialization=%.3f ms, run average=%.3f ms, run p95=%.3f ms, "
                            + "copy average=%.3f ms, copy p95=%.3f ms%n",
                    initializationMs,
                    runAverageMs,
                    runP95Ms,
                    copyAverageMs,
                    copyP95Ms);
        }
    }

    private static byte[] minimalLoopRom() {
        int prgBytes = 16 * 1024;
        int chrBytes = 8 * 1024;
        byte[] rom = new byte[16 + prgBytes + chrBytes];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;
        rom[5] = 1;

        int prg = 16;
        rom[prg] = 0x4C;       // JMP $8000
        rom[prg + 1] = 0x00;
        rom[prg + 2] = (byte) 0x80;

        int vectors = prg + prgBytes - 6;
        for (int i = 0; i < 3; i++) {
            rom[vectors + i * 2] = 0x00;
            rom[vectors + i * 2 + 1] = (byte) 0x80;
        }
        return rom;
    }

    private static byte[] mapper140LoopRom() {
        int prgBytes = 64 * 1024;
        int chrBytes = 40 * 1024;
        byte[] rom = new byte[16 + prgBytes + chrBytes];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 4;
        rom[5] = 5;
        rom[6] = (byte) 0xC0;
        rom[7] = (byte) 0x80;

        int firstBank = 16;
        rom[firstBank] = 0x78;                    // SEI
        rom[firstBank + 1] = (byte) 0xA9;         // LDA #$14
        rom[firstBank + 2] = 0x14;
        rom[firstBank + 3] = (byte) 0x8D;         // STA $6000
        rom[firstBank + 4] = 0x00;
        rom[firstBank + 5] = 0x60;

        int secondBank = firstBank + 32 * 1024;
        rom[secondBank + 6] = 0x4C;               // JMP $8006
        rom[secondBank + 7] = 0x06;
        rom[secondBank + 8] = (byte) 0x80;

        int vectors = firstBank + 32 * 1024 - 6;
        for (int i = 0; i < 3; i++) {
            rom[vectors + i * 2] = 0x00;
            rom[vectors + i * 2 + 1] = (byte) 0x80;
        }
        return rom;
    }


    private static void runInputSequence(WasmNesCore core, int start, int end) {
        for (int frame = start; frame < end; frame++) {
            core.setControllerState(
                    0,
                    frame % 50 < 25
                            ? NesButton.RIGHT.mask() | NesButton.A.mask()
                            : NesButton.LEFT.mask());
            core.setControllerState(
                    1,
                    frame % 70 < 35
                            ? NesButton.UP.mask() | NesButton.B.mask()
                            : NesButton.DOWN.mask());
            core.runFrame();
        }
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[start + index] != needle[index]) continue outer;
            }
            return true;
        }
        return false;
    }
}
