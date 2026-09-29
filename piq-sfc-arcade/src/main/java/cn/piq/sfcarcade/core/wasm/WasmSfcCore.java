// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core.wasm;

import ai.tegmentum.wasmtime4j.Engine;
import ai.tegmentum.wasmtime4j.Instance;
import ai.tegmentum.wasmtime4j.Store;
import ai.tegmentum.wasmtime4j.WasmFunction;
import ai.tegmentum.wasmtime4j.WasmMemory;
import ai.tegmentum.wasmtime4j.WasmRuntime;
import ai.tegmentum.wasmtime4j.WasmValue;
import ai.tegmentum.wasmtime4j.exception.WasmException;
import ai.tegmentum.wasmtime4j.jni.JniWasmRuntime;
import cn.piq.sfcarcade.core.SfcControllerState;
import cn.piq.sfcarcade.core.SfcCore;
import cn.piq.sfcarcade.core.SfcCoreAbi;
import cn.piq.sfcarcade.core.SfcFrameResult;
import cn.piq.sfcarcade.core.SfcRomImage;
import cn.piq.sfcarcade.core.SfcVideoMode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Headless jgenesis SFC core hosted in Wasmtime. */
public final class WasmSfcCore implements SfcCore {
    private static final String MODULE_RESOURCE =
            "/assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm";
    private static final int MAX_AUDIO_SAMPLE_FRAMES = 4096;
    private static final int MAX_STATE_BYTES = 128 * 1024 * 1024;
    private static final int MAX_SRAM_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 64 * 1024;

    private final WasmRuntime runtime;
    private final Engine engine;
    private final ai.tegmentum.wasmtime4j.Module module;
    private final Store store;
    private final Instance instance;
    private final WasmMemory memory;

    private final WasmFunction destroy;
    private final WasmFunction allocate;
    private final WasmFunction free;
    private final WasmFunction loadRom;
    private final WasmFunction setInput;
    private final WasmFunction runFrame;
    private final WasmFunction reset;
    private final WasmFunction frameWidth;
    private final WasmFunction frameHeight;
    private final WasmFunction frameStride;
    private final WasmFunction framePointer;
    private final WasmFunction frameLength;
    private final WasmFunction pixelAspectRatio;
    private final WasmFunction targetFps;
    private final WasmFunction audioPointer;
    private final WasmFunction audioSampleFrames;
    private final WasmFunction saveStateSize;
    private final WasmFunction saveState;
    private final WasmFunction loadState;
    private final WasmFunction sramSize;
    private final WasmFunction saveSram;
    private final WasmFunction loadSram;
    private final WasmFunction lastErrorPointer;
    private final WasmFunction lastErrorLength;
    private final int handle;

    private boolean loaded;
    private boolean frameAvailable;
    private boolean closed;
    private long emulatedFrameNumber;
    private SfcVideoMode latestVideoMode;
    private int latestFramePointer;
    private int latestFrameLength;
    private int latestAudioPointer;
    private int latestAudioSampleFrames;

    public WasmSfcCore() {
        WasmRuntime createdRuntime = null;
        Engine createdEngine = null;
        ai.tegmentum.wasmtime4j.Module createdModule = null;
        Store createdStore = null;
        Instance createdInstance = null;
        try {
            // Directly use the JNI backend: NeoForge's JarJar module isolation can hide it
            // from WasmRuntimeFactory even when the sibling dependency is present.
            createdRuntime = new JniWasmRuntime();
            createdEngine = createdRuntime.createEngine();
            createdModule = createdEngine.compileModule(readModule());
            createdStore = createdEngine.createStore();
            createdInstance = createdModule.instantiate(createdStore);

            runtime = createdRuntime;
            engine = createdEngine;
            module = createdModule;
            store = createdStore;
            instance = createdInstance;
            memory = instance.getMemory("memory")
                    .orElseThrow(() -> new IllegalStateException("SFC WASM 缺少 memory 导出"));

            int abiVersion = callInt(requiredExport(SfcCoreAbi.ABI_VERSION));
            if (abiVersion != SfcCoreAbi.VERSION) {
                throw new IllegalStateException(
                        "SFC ABI 版本不匹配：Java=" + SfcCoreAbi.VERSION + "，WASM=" + abiVersion);
            }
            int sampleRate = callInt(requiredExport(SfcCoreAbi.AUDIO_SAMPLE_RATE));
            if (sampleRate != SfcFrameResult.AUDIO_SAMPLE_RATE) {
                throw new IllegalStateException("SFC 核心采样率不兼容：" + sampleRate);
            }

            destroy = requiredExport(SfcCoreAbi.DESTROY);
            allocate = requiredExport(SfcCoreAbi.ALLOCATE);
            free = requiredExport(SfcCoreAbi.FREE);
            loadRom = requiredExport(SfcCoreAbi.LOAD_ROM);
            setInput = requiredExport(SfcCoreAbi.SET_INPUT);
            runFrame = requiredExport(SfcCoreAbi.RUN_FRAME);
            reset = requiredExport(SfcCoreAbi.RESET);
            frameWidth = requiredExport(SfcCoreAbi.FRAME_WIDTH);
            frameHeight = requiredExport(SfcCoreAbi.FRAME_HEIGHT);
            frameStride = requiredExport(SfcCoreAbi.FRAME_STRIDE);
            framePointer = requiredExport(SfcCoreAbi.FRAME_POINTER);
            frameLength = requiredExport(SfcCoreAbi.FRAME_LENGTH);
            pixelAspectRatio = requiredExport(SfcCoreAbi.PIXEL_ASPECT_RATIO);
            targetFps = requiredExport(SfcCoreAbi.TARGET_FPS);
            audioPointer = requiredExport(SfcCoreAbi.AUDIO_POINTER);
            audioSampleFrames = requiredExport(SfcCoreAbi.AUDIO_SAMPLE_FRAMES);
            saveStateSize = requiredExport(SfcCoreAbi.SAVE_STATE_SIZE);
            saveState = requiredExport(SfcCoreAbi.SAVE_STATE);
            loadState = requiredExport(SfcCoreAbi.LOAD_STATE);
            sramSize = requiredExport(SfcCoreAbi.SAVE_SRAM_SIZE);
            saveSram = requiredExport(SfcCoreAbi.SAVE_SRAM);
            loadSram = requiredExport(SfcCoreAbi.LOAD_SRAM);
            lastErrorPointer = requiredExport(SfcCoreAbi.LAST_ERROR_POINTER);
            lastErrorLength = requiredExport(SfcCoreAbi.LAST_ERROR_LENGTH);

            handle = callInt(requiredExport(SfcCoreAbi.CREATE));
            if (handle == 0) {
                throw new IllegalStateException("SFC WASM 未能创建核心实例");
            }
        } catch (Throwable error) {
            closeQuietly(createdInstance);
            closeQuietly(createdStore);
            closeQuietly(createdModule);
            closeQuietly(createdEngine);
            closeQuietly(createdRuntime);
            throw runtimeFailure("初始化 Wasmtime SFC 核心失败", error);
        }
    }

    @Override
    public String backendName() {
        return "jgenesis 0.13.1 (WASM/Wasmtime)";
    }

    @Override
    public synchronized void loadRom(SfcRomImage rom) {
        ensureOpen();
        byte[] bytes = Objects.requireNonNull(rom, "rom").copyPayload();
        int pointer = allocate(bytes.length);
        boolean nativeOwnsPointer = false;
        try {
            memory.writeBytes(pointer, bytes, 0, bytes.length);
            // load_rom deliberately consumes this allocation on both success and validation failure.
            nativeOwnsPointer = true;
            requireStatus(callInt(loadRom, handle, pointer, bytes.length), "加载 SFC ROM");
            loaded = true;
            emulatedFrameNumber = 0;
            clearLatestFrame();
        } finally {
            if (!nativeOwnsPointer) {
                free(pointer, bytes.length);
            }
        }
    }

    @Override
    public synchronized SfcFrameResult runFrame(
            SfcControllerState playerOne,
            SfcControllerState playerTwo
    ) {
        ensureLoaded();
        SfcControllerState p1 = Objects.requireNonNull(playerOne, "playerOne");
        SfcControllerState p2 = Objects.requireNonNull(playerTwo, "playerTwo");
        requireStatus(callInt(setInput, handle, 0, p1.mask()), "设置 1P 输入");
        requireStatus(callInt(setInput, handle, 1, p2.mask()), "设置 2P 输入");
        requireStatus(callInt(runFrame, handle), "运行 SFC 帧");

        int width = callInt(frameWidth, handle);
        int height = callInt(frameHeight, handle);
        int stride = callInt(frameStride, handle);
        int rgbaPointer = callInt(framePointer, handle);
        int rgbaLength = callInt(frameLength, handle);
        double aspectRatio = callDouble(pixelAspectRatio, handle);
        double framesPerSecond = callDouble(targetFps, handle);
        SfcVideoMode mode = new SfcVideoMode(width, height, stride, aspectRatio, framesPerSecond);
        int requiredRgbaBytes = mode.requiredRgbaBytes();
        if (rgbaLength != requiredRgbaBytes) {
            throw new IllegalStateException(
                    "SFC 核心帧长度不匹配：返回 " + rgbaLength + "，应为 " + requiredRgbaBytes);
        }
        requireMemoryRange(rgbaPointer, rgbaLength, "SFC RGBA 帧");

        int sampleFrames = callInt(audioSampleFrames, handle);
        if (sampleFrames < 0 || sampleFrames > MAX_AUDIO_SAMPLE_FRAMES) {
            throw new IllegalStateException("SFC 核心返回非法音频帧数：" + sampleFrames);
        }
        int pcmBytes = Math.multiplyExact(
                Math.multiplyExact(sampleFrames, SfcFrameResult.AUDIO_CHANNELS),
                Short.BYTES);
        int pcmPointer = callInt(audioPointer, handle);
        if (pcmBytes != 0) {
            requireMemoryRange(pcmPointer, pcmBytes, "SFC PCM 音频");
        }

        latestVideoMode = mode;
        latestFramePointer = rgbaPointer;
        latestFrameLength = rgbaLength;
        latestAudioPointer = pcmPointer;
        latestAudioSampleFrames = sampleFrames;
        frameAvailable = true;
        return new SfcFrameResult(mode, sampleFrames, emulatedFrameNumber++);
    }

    @Override
    public synchronized void copyRgbaFrame(byte[] destination) {
        ensureFrameAvailable();
        Objects.requireNonNull(destination, "destination");
        if (destination.length != latestFrameLength) {
            throw new IllegalArgumentException(
                    "SFC RGBA 缓冲区必须正好为 " + latestFrameLength + " 字节");
        }
        requireMemoryRange(latestFramePointer, latestFrameLength, "SFC RGBA 帧");
        memory.readBytes(latestFramePointer, destination, 0, destination.length);
    }

    @Override
    public synchronized int copyAudioPcm16(short[] destination) {
        ensureFrameAvailable();
        Objects.requireNonNull(destination, "destination");
        int shortCount = Math.multiplyExact(
                latestAudioSampleFrames,
                SfcFrameResult.AUDIO_CHANNELS);
        if (destination.length < shortCount) {
            throw new IllegalArgumentException(
                    "SFC PCM 缓冲区至少需要 " + shortCount + " 个 short");
        }
        if (shortCount == 0) {
            return 0;
        }
        int byteCount = Math.multiplyExact(shortCount, Short.BYTES);
        requireMemoryRange(latestAudioPointer, byteCount, "SFC PCM 音频");
        byte[] bytes = new byte[byteCount];
        memory.readBytes(latestAudioPointer, bytes, 0, byteCount);
        ByteBuffer samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < shortCount; index++) {
            destination[index] = samples.getShort();
        }
        return latestAudioSampleFrames;
    }

    @Override
    public synchronized byte[] saveState() {
        ensureLoaded();
        int length = checkedBlobLength(callInt(saveStateSize, handle), MAX_STATE_BYTES, "状态");
        int pointer = allocate(length);
        try {
            int copied = callInt(saveState, handle, pointer, length);
            if (copied != length) {
                throw new IllegalStateException("SFC 状态导出不完整：" + copied + "/" + length);
            }
            requireMemoryRange(pointer, length, "SFC 状态");
            byte[] bytes = new byte[length];
            memory.readBytes(pointer, bytes, 0, length);
            return bytes;
        } finally {
            free(pointer, length);
        }
    }

    @Override
    public synchronized void loadState(byte[] state) {
        ensureLoaded();
        Objects.requireNonNull(state, "state");
        checkedBlobLength(state.length, MAX_STATE_BYTES, "状态");
        int pointer = allocate(state.length);
        try {
            memory.writeBytes(pointer, state, 0, state.length);
            requireStatus(callInt(loadState, handle, pointer, state.length), "载入 SFC 状态");
            clearLatestFrame();
        } finally {
            free(pointer, state.length);
        }
    }

    @Override
    public synchronized byte[] saveSram() {
        ensureLoaded();
        int length = callInt(sramSize, handle);
        if (length == 0) {
            return new byte[0];
        }
        checkedBlobLength(length, MAX_SRAM_BYTES, "SRAM");
        int pointer = allocate(length);
        try {
            int copied = callInt(saveSram, handle, pointer, length);
            if (copied != length) {
                throw new IllegalStateException("SFC SRAM 导出不完整：" + copied + "/" + length);
            }
            requireMemoryRange(pointer, length, "SFC SRAM");
            byte[] bytes = new byte[length];
            memory.readBytes(pointer, bytes, 0, length);
            return bytes;
        } finally {
            free(pointer, length);
        }
    }

    @Override
    public synchronized void loadSram(byte[] sram) {
        ensureLoaded();
        Objects.requireNonNull(sram, "sram");
        if (sram.length > MAX_SRAM_BYTES) {
            throw new IllegalArgumentException("SFC SRAM 超过安全上限：" + sram.length);
        }
        int allocationBytes = Math.max(1, sram.length);
        int pointer = allocate(allocationBytes);
        try {
            if (sram.length != 0) {
                memory.writeBytes(pointer, sram, 0, sram.length);
            }
            requireStatus(callInt(loadSram, handle, pointer, sram.length), "载入 SFC SRAM");
            clearLatestFrame();
        } finally {
            free(pointer, allocationBytes);
        }
    }

    @Override
    public synchronized void reset(boolean hard) {
        ensureLoaded();
        requireStatus(callInt(reset, handle, hard ? 1 : 0), hard ? "硬重置 SFC" : "软重置 SFC");
        emulatedFrameNumber = 0;
        clearLatestFrame();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            callVoid(destroy, handle);
        } finally {
            closeQuietly(instance);
            closeQuietly(store);
            closeQuietly(module);
            closeQuietly(engine);
            closeQuietly(runtime);
        }
    }

    private void clearLatestFrame() {
        frameAvailable = false;
        latestVideoMode = null;
        latestFramePointer = 0;
        latestFrameLength = 0;
        latestAudioPointer = 0;
        latestAudioSampleFrames = 0;
    }

    private int allocate(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("WASM 分配长度必须为正数：" + bytes);
        }
        int pointer = callInt(allocate, bytes);
        if (pointer == 0) {
            throw new IllegalStateException("SFC WASM 内存分配失败：" + bytes + " 字节");
        }
        requireMemoryRange(pointer, bytes, "新分配的 SFC WASM 内存");
        return pointer;
    }

    private void free(int pointer, int bytes) {
        callVoid(free, pointer, bytes);
    }

    private int checkedBlobLength(int length, int maximum, String label) {
        if (length <= 0 || length > maximum) {
            throw new IllegalStateException("SFC " + label + "长度非法：" + length);
        }
        return length;
    }

    private void requireStatus(int status, String operation) {
        if (status != 0) {
            throw new IllegalStateException(operation + "失败：" + readLastError());
        }
    }

    private String readLastError() {
        int pointer = callInt(lastErrorPointer);
        int length = callInt(lastErrorLength);
        if (length <= 0 || length > MAX_ERROR_BYTES) {
            return "核心未提供有效错误信息（长度 " + length + "）";
        }
        try {
            requireMemoryRange(pointer, length, "SFC 错误信息");
            byte[] bytes = new byte[length];
            memory.readBytes(pointer, bytes, 0, length);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (RuntimeException invalidErrorBuffer) {
            return "核心错误信息不可读取：" + invalidErrorBuffer.getMessage();
        }
    }

    private void requireMemoryRange(int pointer, int length, String label) {
        if (length < 0) {
            throw new IllegalStateException(label + "长度为负数：" + length);
        }
        long unsignedPointer = Integer.toUnsignedLong(pointer);
        long end = unsignedPointer + (long) length;
        long memoryBytes = memory.dataSize();
        if (pointer == 0 || pointer < 0 || end < unsignedPointer || end > memoryBytes) {
            throw new IllegalStateException(
                    label + "越过 WASM 内存：pointer=" + unsignedPointer
                            + "，length=" + length + "，memory=" + memoryBytes);
        }
    }

    private WasmFunction requiredExport(String name) {
        return instance.getFunction(name)
                .orElseThrow(() -> new IllegalStateException("SFC WASM 缺少导出函数：" + name));
    }

    private static int callInt(WasmFunction function, int... arguments) {
        try {
            WasmValue[] values = arguments(arguments);
            WasmValue[] result = function.call(values);
            if (result.length != 1) {
                throw new IllegalStateException("WASM 函数返回值数量异常：" + function.getName());
            }
            return result[0].asInt();
        } catch (WasmException error) {
            throw runtimeFailure("调用 SFC WASM 函数失败：" + function.getName(), error);
        }
    }

    private static double callDouble(WasmFunction function, int... arguments) {
        try {
            WasmValue[] result = function.call(arguments(arguments));
            if (result.length != 1) {
                throw new IllegalStateException("WASM 函数返回值数量异常：" + function.getName());
            }
            return result[0].asDouble();
        } catch (WasmException error) {
            throw runtimeFailure("调用 SFC WASM 函数失败：" + function.getName(), error);
        }
    }

    private static void callVoid(WasmFunction function, int... arguments) {
        try {
            function.call(arguments(arguments));
        } catch (WasmException error) {
            throw runtimeFailure("调用 SFC WASM 函数失败：" + function.getName(), error);
        }
    }

    private static WasmValue[] arguments(int[] values) {
        WasmValue[] arguments = new WasmValue[values.length];
        for (int index = 0; index < values.length; index++) {
            arguments[index] = WasmValue.i32(values[index]);
        }
        return arguments;
    }

    private void ensureFrameAvailable() {
        ensureLoaded();
        if (!frameAvailable || latestVideoMode == null) {
            throw new IllegalStateException("尚未运行出可复制的 SFC 帧");
        }
    }

    private void ensureLoaded() {
        ensureOpen();
        if (!loaded) {
            throw new IllegalStateException("尚未加载 SFC ROM");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("SFC 核心已经关闭");
        }
    }

    private static byte[] readModule() {
        try (InputStream input = WasmSfcCore.class.getResourceAsStream(MODULE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("找不到内置 SFC WASM：" + MODULE_RESOURCE);
            }
            return input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("读取 SFC WASM 失败", error);
        }
    }

    private static IllegalStateException runtimeFailure(String message, Throwable error) {
        if (error instanceof IllegalStateException stateError) {
            return stateError;
        }
        return new IllegalStateException(message + "：" + error.getMessage(), error);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
