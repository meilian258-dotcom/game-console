package cn.piq.fcarcade.core.wasm;

import ai.tegmentum.wasmtime4j.Engine;
import ai.tegmentum.wasmtime4j.Instance;
import ai.tegmentum.wasmtime4j.Store;
import ai.tegmentum.wasmtime4j.WasmFunction;
import ai.tegmentum.wasmtime4j.WasmMemory;
import ai.tegmentum.wasmtime4j.WasmRuntime;
import ai.tegmentum.wasmtime4j.WasmValue;
import ai.tegmentum.wasmtime4j.exception.WasmException;
import ai.tegmentum.wasmtime4j.jni.JniWasmRuntime;
import cn.piq.fcarcade.core.NesButton;
import cn.piq.fcarcade.core.NesCore;
import cn.piq.fcarcade.rom.INesHeader;
import cn.piq.fcarcade.rom.NesCompatibility;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class WasmNesCore implements NesCore {
    private final Thread ownerThread = Thread.currentThread();
    private static final String MODULE_RESOURCE = "/core/nes_rust_wasm_bg.wasm";
    private static final int PLAYER_COUNT = 2;
    private static final int AUDIO_SAMPLE_CAPACITY = 4096;
    private static final int AUDIO_BYTES = AUDIO_SAMPLE_CAPACITY * Float.BYTES;
    private static final int STATE_MAGIC = 0x50465131;
    private static final int MAX_STATE_MEMORY_BYTES = 64 * 1024 * 1024;
    private static final int[][] WASM_BUTTONS = {
            {4, 5, 2, 3, 6, 7, 8, 9},
            {10, 11, -1, -1, 12, 13, 14, 15}
    };

    private final WasmRuntime runtime;
    private final Engine engine;
    private final ai.tegmentum.wasmtime4j.Module module;
    private final Store store;
    private final Instance instance;
    private final WasmMemory memory;
    private final WasmFunction destroy;
    private final WasmFunction setRom;
    private final WasmFunction bootup;
    private final WasmFunction reset;
    private final WasmFunction stepFrame;
    private final WasmFunction updatePixels;
    private final WasmFunction copyAudio;
    private final WasmFunction copyCpuRam;
    private final WasmFunction pressButton;
    private final WasmFunction releaseButton;
    private final WasmFunction malloc;
    private final WasmFunction free;
    private final int[] controllerStates = new int[PLAYER_COUNT];
    private final int nesPointer;
    private final int framePointer;
    private final int audioPointer;
    private final int cpuRamPointer;
    private final byte[] audioBytes = new byte[AUDIO_BYTES];
    private byte[] baselineMemory;
    private boolean loaded;
    private boolean closed;

    public WasmNesCore() {
        WasmRuntime createdRuntime = null;
        Engine createdEngine = null;
        ai.tegmentum.wasmtime4j.Module createdModule = null;
        Store createdStore = null;
        Instance createdInstance = null;
        try {
            // WasmRuntimeFactory uses Class.forName from the API JarJar module.
            // NeoForge loads sibling JarJar libraries in isolated modules, so the
            // factory cannot see wasmtime4j-jni even though it is present. Link
            // the JNI backend directly from this mod's module instead.
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
                    .orElseThrow(() -> new IllegalStateException("NES WASM 缺少 memory 导出"));
            destroy = requiredExport("nes_destroy");
            setRom = requiredExport("nes_set_rom");
            bootup = requiredExport("nes_bootup");
            reset = requiredExport("nes_reset");
            stepFrame = requiredExport("nes_step_frame");
            updatePixels = requiredExport("nes_copy_frame");
            copyAudio = requiredExport("nes_copy_audio");
            copyCpuRam = requiredExport("nes_copy_cpu_ram");
            pressButton = requiredExport("nes_press_button");
            releaseButton = requiredExport("nes_release_button");
            malloc = requiredExport("nes_alloc");
            free = requiredExport("nes_dealloc");
            nesPointer = callToInt(requiredExport("nes_create"));
            if (nesPointer == 0) {
                throw new IllegalStateException("NES WASM 未能创建模拟器实例");
            }
            framePointer = allocate(RGBA_BYTES);
            audioPointer = allocate(AUDIO_BYTES);
            cpuRamPointer = allocate(CPU_RAM_BYTES);
        } catch (Throwable error) {
            closeQuietly(createdInstance);
            closeQuietly(createdStore);
            closeQuietly(createdModule);
            closeQuietly(createdEngine);
            closeQuietly(createdRuntime);
            throw runtimeFailure("初始化 Wasmtime NES 核心失败", error);
        }
    }

    @Override
    public synchronized void loadRom(byte[] rom) {
        ensureOpen();
        INesHeader header = INesHeader.parse(rom);
        NesCompatibility.requireLegacySupported(header);
        byte[] coreRom = withoutTrainer(rom, header);
        int pointer = allocate(coreRom.length);
        memory.writeBytes(pointer, coreRom, 0, coreRom.length);
        callVoid(setRom, nesPointer, pointer, coreRom.length);
        callVoid(bootup, nesPointer);
        loaded = true;
        baselineMemory = readWholeMemory();
    }

    @Override
    public synchronized void reset() {
        ensureLoaded();
        callVoid(reset, nesPointer);
        clearButtons();
    }

    @Override
    public synchronized void setControllerState(int player, int buttonMask) {
        ensureLoaded();
        if (player < 0 || player >= PLAYER_COUNT) {
            throw new IllegalArgumentException("手柄编号只能为 0 或 1");
        }
        int normalized = buttonMask & 0xFF;
        int changed = controllerStates[player] ^ normalized;
        if (changed == 0) return;

        NesButton[] buttons = NesButton.values();
        for (int i = 0; i < buttons.length; i++) {
            if ((changed & buttons[i].mask()) == 0) continue;
            int wasmButton = WASM_BUTTONS[player][i];
            if (wasmButton < 0) continue;
            callVoid(
                    (normalized & buttons[i].mask()) != 0 ? pressButton : releaseButton,
                    nesPointer,
                    wasmButton);
        }
        controllerStates[player] = normalized;
    }

    @Override
    public synchronized void runFrame() {
        ensureLoaded();
        callVoid(stepFrame, nesPointer);
    }

    @Override
    public synchronized void copyFrameRgba(byte[] destination) {
        ensureLoaded();
        if (destination.length != RGBA_BYTES) {
            throw new IllegalArgumentException("画面缓冲区必须正好为 " + RGBA_BYTES + " 字节");
        }
        callVoid(updatePixels, nesPointer, framePointer, destination.length);
        memory.readBytes(framePointer, destination, 0, destination.length);
    }

    @Override
    public synchronized int copyAudioSamples(float[] destination) {
        ensureLoaded();
        if (destination.length == 0) return 0;
        int capacity = Math.min(destination.length, AUDIO_SAMPLE_CAPACITY);
        int count = callToInt(copyAudio, nesPointer, audioPointer, capacity);
        if (count < 0 || count > capacity) {
            throw new IllegalStateException("NES WASM 返回了非法音频采样数：" + count);
        }
        int byteCount = count * Float.BYTES;
        memory.readBytes(audioPointer, audioBytes, 0, byteCount);
        ByteBuffer samples = ByteBuffer.wrap(audioBytes, 0, byteCount)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) destination[i] = samples.getFloat();
        return count;
    }

    @Override
    public synchronized void copyCpuRam(byte[] destination) {
        ensureLoaded();
        if (destination.length != CPU_RAM_BYTES) {
            throw new IllegalArgumentException(
                    "CPU RAM buffer must contain exactly " + CPU_RAM_BYTES + " bytes");
        }
        callVoid(copyCpuRam, nesPointer, cpuRamPointer, destination.length);
        memory.readBytes(cpuRamPointer, destination, 0, destination.length);
    }

    @Override
    public synchronized byte[] saveTransientState() {
        ensureLoaded();
        byte[] current = readWholeMemory();
        byte[] delta = new byte[current.length];
        for (int index = 0; index < current.length; index++) {
            byte baseline = index < baselineMemory.length ? baselineMemory[index] : 0;
            delta[index] = (byte) (current[index] ^ baseline);
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DataOutputStream header = new DataOutputStream(output);
            header.writeInt(STATE_MAGIC);
            header.writeInt(current.length);
            header.writeByte(controllerStates[0]);
            header.writeByte(controllerStates[1]);
            header.flush();
            try (DeflaterOutputStream compressed = new DeflaterOutputStream(
                    output,
                    new Deflater(Deflater.BEST_SPEED))) {
                compressed.write(delta);
            }
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("压缩 NES 临时状态失败", error);
        }
    }

    @Override
    public synchronized void loadTransientState(byte[] state) {
        ensureLoaded();
        if (state == null || state.length < 10) {
            throw new IllegalArgumentException("NES 临时状态为空或损坏");
        }
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(state);
            DataInputStream header = new DataInputStream(input);
            if (header.readInt() != STATE_MAGIC) {
                throw new IllegalArgumentException("NES 临时状态版本不兼容");
            }
            int memoryBytes = header.readInt();
            if (memoryBytes <= 0 || memoryBytes > MAX_STATE_MEMORY_BYTES) {
                throw new IllegalArgumentException("NES 临时状态内存长度非法：" + memoryBytes);
            }
            int playerOneState = header.readUnsignedByte();
            int playerTwoState = header.readUnsignedByte();
            byte[] delta;
            try (InflaterInputStream compressed = new InflaterInputStream(input)) {
                delta = compressed.readNBytes(memoryBytes);
                if (delta.length != memoryBytes || compressed.read() != -1) {
                    throw new IllegalArgumentException("NES 临时状态数据长度不匹配");
                }
            }
            ensureMemoryCapacity(memoryBytes);
            byte[] restored = new byte[memoryBytes];
            for (int index = 0; index < restored.length; index++) {
                byte baseline = index < baselineMemory.length ? baselineMemory[index] : 0;
                restored[index] = (byte) (delta[index] ^ baseline);
            }
            memory.writeBytes(0, restored, 0, restored.length);
            controllerStates[0] = playerOneState;
            controllerStates[1] = playerTwoState;
        } catch (IOException error) {
            throw new IllegalArgumentException("解压 NES 临时状态失败", error);
        }
    }

    @Override
    public synchronized void close() {
        ensureOwner();
        if (closed) return;
        try {
            if (loaded) clearButtons();
            callVoid(free, cpuRamPointer, CPU_RAM_BYTES);
            callVoid(free, audioPointer, AUDIO_BYTES);
            callVoid(free, framePointer, RGBA_BYTES);
            callVoid(destroy, nesPointer);
        } finally {
            closed = true;
            instance.close();
            store.close();
            module.close();
            engine.close();
            runtime.close();
        }
    }

    private void clearButtons() {
        for (int player = 0; player < PLAYER_COUNT; player++) {
            if (controllerStates[player] != 0) setControllerState(player, 0);
        }
    }

    private int allocate(int bytes) {
        int pointer = callToInt(malloc, bytes);
        if (pointer == 0) throw new IllegalStateException("NES WASM 内存分配失败：" + bytes + " 字节");
        return pointer;
    }

    private byte[] readWholeMemory() {
        long size = memory.dataSize();
        if (size <= 0 || size > MAX_STATE_MEMORY_BYTES) {
            throw new IllegalStateException("NES WASM 内存大小非法：" + size);
        }
        byte[] bytes = new byte[(int) size];
        memory.readBytes(0, bytes, 0, bytes.length);
        return bytes;
    }

    private void ensureMemoryCapacity(int requiredBytes) {
        long currentBytes = memory.dataSize();
        if (currentBytes >= requiredBytes) return;
        long pageSize = memory.pageSize();
        long missing = requiredBytes - currentBytes;
        long pages = (missing + pageSize - 1) / pageSize;
        memory.grow64(pages);
        if (memory.dataSize() < requiredBytes) {
            throw new IllegalStateException("NES WASM 内存扩展失败");
        }
    }

    private WasmFunction requiredExport(String name) {
        return instance.getFunction(name)
                .orElseThrow(() -> new IllegalStateException("NES WASM 缺少导出函数：" + name));
    }

    private static int callToInt(WasmFunction function, int... arguments) {
        try {
            WasmValue[] values = new WasmValue[arguments.length];
            for (int i = 0; i < arguments.length; i++) values[i] = WasmValue.i32(arguments[i]);
            return function.call(values)[0].asInt();
        } catch (WasmException error) {
            throw runtimeFailure("调用 NES WASM 函数失败：" + function.getName(), error);
        }
    }

    private static void callVoid(WasmFunction function, int... arguments) {
        try {
            WasmValue[] values = new WasmValue[arguments.length];
            for (int i = 0; i < arguments.length; i++) values[i] = WasmValue.i32(arguments[i]);
            function.call(values);
        } catch (WasmException error) {
            throw runtimeFailure("调用 NES WASM 函数失败：" + function.getName(), error);
        }
    }

    private void ensureLoaded() {
        ensureOpen();
        if (!loaded) throw new IllegalStateException("尚未加载 ROM");
    }

    private void ensureOpen() {
        ensureOwner();
        if (closed) throw new IllegalStateException("NES 核心已经关闭");
    }

    private void ensureOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("NES 核心只能由创建它的线程访问或关闭");
        }
    }

    private static byte[] readModule() {
        try (InputStream input = WasmNesCore.class.getResourceAsStream(MODULE_RESOURCE)) {
            if (input == null) throw new IllegalStateException("找不到内置 NES WASM：" + MODULE_RESOURCE);
            return input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("读取 NES WASM 失败", error);
        }
    }

    private static byte[] withoutTrainer(byte[] rom, INesHeader header) {
        if (!header.trainerPresent()) return rom;
        byte[] normalized = new byte[rom.length - INesHeader.TRAINER_BYTES];
        System.arraycopy(rom, 0, normalized, 0, INesHeader.HEADER_BYTES);
        System.arraycopy(
                rom,
                INesHeader.HEADER_BYTES + INesHeader.TRAINER_BYTES,
                normalized,
                INesHeader.HEADER_BYTES,
                rom.length - INesHeader.HEADER_BYTES - INesHeader.TRAINER_BYTES);
        normalized[6] = (byte) (normalized[6] & ~0x04);
        return normalized;
    }

    private static IllegalStateException runtimeFailure(String message, Throwable error) {
        return new IllegalStateException(message + "：" + error.getMessage(), error);
    }

    private static void closeQuietly(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
        }
    }
}
