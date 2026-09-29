// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import ai.tegmentum.wasmtime4j.Engine;
import ai.tegmentum.wasmtime4j.Instance;
import ai.tegmentum.wasmtime4j.Store;
import ai.tegmentum.wasmtime4j.WasmFunction;
import ai.tegmentum.wasmtime4j.WasmMemory;
import ai.tegmentum.wasmtime4j.WasmRuntime;
import ai.tegmentum.wasmtime4j.WasmValue;
import ai.tegmentum.wasmtime4j.exception.WasmException;
import ai.tegmentum.wasmtime4j.jni.JniWasmRuntime;
import cn.piq.sfcarcade.core.wasm.WasmSfcCore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SfcWasmRuntimeSmoke {
    private static final String MODULE_RESOURCE =
            "/assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm";

    private SfcWasmRuntimeSmoke() {
    }

    public static void main(String[] args) throws Exception {
        try (WasmRuntime runtime = new JniWasmRuntime();
             Engine engine = runtime.createEngine();
             ai.tegmentum.wasmtime4j.Module module = engine.compileModule(readModule());
             Store store = engine.createStore();
             Instance instance = module.instantiate(store)) {
            WasmMemory memory = instance.getMemory("memory")
                    .orElseThrow(() -> new AssertionError("SFC core does not export memory"));
            int abiVersion = callInt(required(instance, SfcCoreAbi.ABI_VERSION));
            require(abiVersion == SfcCoreAbi.VERSION,
                    "SFC ABI mismatch: Java=" + SfcCoreAbi.VERSION + ", WASM=" + abiVersion);

            WasmFunction destroy = required(instance, SfcCoreAbi.DESTROY);
            int handle = callInt(required(instance, SfcCoreAbi.CREATE));
            require(handle != 0, "SFC core returned a null handle");
            try {
                int result = callInt(required(instance, SfcCoreAbi.RUN_FRAME), handle);
                require(result == -1, "run_frame without ROM should fail with -1");
                int errorPointer = callInt(required(instance, SfcCoreAbi.LAST_ERROR_POINTER));
                int errorLength = callInt(required(instance, SfcCoreAbi.LAST_ERROR_LENGTH));
                require(errorPointer > 0 && errorLength > 0, "SFC core did not expose an error message");
                byte[] errorBytes = new byte[errorLength];
                memory.readBytes(errorPointer, errorBytes, 0, errorLength);
                String message = new String(errorBytes, StandardCharsets.UTF_8);
                require(message.contains("no SFC ROM"), "Unexpected SFC core error: " + message);
            } finally {
                callVoid(destroy, handle);
            }
        }
        try (SfcCore core = new WasmSfcCore()) {
            require(core.backendName().contains("jgenesis"),
                    "Unexpected SFC wrapper backend: " + core.backendName());
            try {
                core.runFrame(SfcControllerState.NONE, SfcControllerState.NONE);
                throw new AssertionError("WasmSfcCore accepted runFrame before a ROM was loaded");
            } catch (IllegalStateException expected) {
                require(expected.getMessage().contains("ROM"),
                        "Unexpected wrapper lifecycle error: " + expected.getMessage());
            }

            SfcRomImage testRom = SfcRomImage.fromBytes(SfcLegalTestRom.create());
            core.loadRom(testRom);
            SfcFrameResult firstFrame = core.runFrame(
                    SfcControllerState.NONE, SfcControllerState.NONE);
            require(firstFrame.videoMode().width() >= 256,
                    "Unexpected SFC frame width: " + firstFrame.videoMode().width());
            require(firstFrame.videoMode().height() >= 224,
                    "Unexpected SFC frame height: " + firstFrame.videoMode().height());
            require(firstFrame.stereoSampleFrames() > 0,
                    "SFC core produced no audio clock samples");

            byte[] rgba = new byte[firstFrame.videoMode().requiredRgbaBytes()];
            core.copyRgbaFrame(rgba);
            boolean hasOpaquePixel = false;
            int greenPixels = 0;
            for (int i = 0; i < rgba.length; i += 4) {
                int red = Byte.toUnsignedInt(rgba[i]);
                int green = Byte.toUnsignedInt(rgba[i + 1]);
                int blue = Byte.toUnsignedInt(rgba[i + 2]);
                if (Byte.toUnsignedInt(rgba[i + 3]) == 0xFF) {
                    hasOpaquePixel = true;
                }
                if (green > red + 32 && green > blue + 32) {
                    greenPixels++;
                }
            }
            require(hasOpaquePixel, "SFC core returned an empty RGBA frame");
            require(greenPixels > rgba.length / 8,
                    "Legal SFC test program did not paint its expected green backdrop");

            short[] pcm = new short[firstFrame.requiredPcmShorts()];
            require(core.copyAudioPcm16(pcm) == firstFrame.stereoSampleFrames(),
                    "SFC audio copy returned a different sample-frame count");

            byte[] state = core.saveState();
            require(state.length > 0, "SFC core returned an empty save state");
            SfcFrameResult secondFrame = core.runFrame(
                    new SfcControllerState(SfcButton.A.bit()), SfcControllerState.NONE);
            require(secondFrame.emulatedFrameNumber() == firstFrame.emulatedFrameNumber() + 1,
                    "SFC frame counter did not advance exactly once");
            core.loadState(state);
            core.reset(false);
            core.reset(true);
            require(core.saveSram().length == 0,
                    "ROM-only legal test cartridge unexpectedly exposed SRAM");
        }
        System.out.println("jgenesis WASM runtime smoke passed (ABI v" + SfcCoreAbi.VERSION + ")");
    }

    private static WasmFunction required(Instance instance, String name) {
        return instance.getFunction(name)
                .orElseThrow(() -> new AssertionError("Missing SFC WASM export: " + name));
    }

    private static int callInt(WasmFunction function, int... arguments) throws WasmException {
        WasmValue[] values = new WasmValue[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            values[i] = WasmValue.i32(arguments[i]);
        }
        return function.call(values)[0].asInt();
    }

    private static void callVoid(WasmFunction function, int... arguments) throws WasmException {
        WasmValue[] values = new WasmValue[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            values[i] = WasmValue.i32(arguments[i]);
        }
        function.call(values);
    }

    private static byte[] readModule() throws IOException {
        try (InputStream input = SfcWasmRuntimeSmoke.class.getResourceAsStream(MODULE_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing SFC WASM resource: " + MODULE_RESOURCE);
            }
            return input.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
