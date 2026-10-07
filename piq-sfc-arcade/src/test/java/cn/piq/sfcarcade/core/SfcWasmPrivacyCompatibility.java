// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import ai.tegmentum.wasmtime4j.*;
import ai.tegmentum.wasmtime4j.jni.JniWasmRuntime;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Isolated original/derived module comparison using only the original test cartridge. */
public final class SfcWasmPrivacyCompatibility {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Core implements AutoCloseable {
        final WasmRuntime runtime = new JniWasmRuntime();
        final Engine engine = runtime.createEngine();
        final ai.tegmentum.wasmtime4j.Module module;
        final Store store;
        final Instance instance;
        final WasmMemory memory;
        final int handle;

        Core(Path path) throws Exception {
            module = engine.compileModule(Files.readAllBytes(path));
            store = engine.createStore();
            instance = module.instantiate(store);
            memory = instance.getMemory("memory").orElseThrow();
            require(call("abi_version") == 1, "ABI changed");
            handle = call("create");
            require(handle != 0, "create failed");
        }

        int call(String name, int... arguments) throws Exception {
            WasmValue[] values = Arrays.stream(arguments).mapToObj(WasmValue::i32).toArray(WasmValue[]::new);
            WasmValue[] result = instance.getFunction("piq_sfc_" + name).orElseThrow().call(values);
            return result.length == 0 ? 0 : result[0].asInt();
        }

        byte[] read(int pointer, int length) {
            byte[] result = new byte[length];
            if (length != 0) memory.readBytes(pointer, result, 0, length);
            return result;
        }

        void loadRom(byte[] rom) throws Exception {
            int pointer = call("alloc", rom.length);
            memory.writeBytes(pointer, rom, 0, rom.length);
            // load_rom consumes this allocation on both success and validation failure.
            require(call("load_rom", handle, pointer, rom.length) == 0, "load ROM failed");
        }

        byte[] state() throws Exception {
            int length = call("save_state_size", handle);
            require(length > 0, "empty state");
            int pointer = call("alloc", length);
            try {
                require(call("save_state", handle, pointer, length) == length, "incomplete state");
                return read(pointer, length);
            } finally { call("free", pointer, length); }
        }

        void restore(byte[] state) throws Exception {
            int pointer = call("alloc", state.length);
            try {
                memory.writeBytes(pointer, state, 0, state.length);
                require(call("load_state", handle, pointer, state.length) == 0, "restore failed");
            } finally { call("free", pointer, state.length); }
        }

        void frame(int one, int two) throws Exception {
            require(call("set_input", handle, 0, one) == 0, "P1 input failed");
            require(call("set_input", handle, 1, two) == 0, "P2 input failed");
            require(call("run_frame", handle) == 0, "frame failed");
        }

        byte[] rgba() throws Exception { return read(call("frame_ptr", handle), call("frame_len", handle)); }
        byte[] pcm() throws Exception { return read(call("audio_ptr", handle), call("audio_sample_frames", handle) * 4); }

        void checkSram() throws Exception {
            require(call("sram_size", handle) == 0, "ROM-only test has unexpected SRAM");
        }

        byte[] sram() throws Exception {
            int length = call("sram_size", handle);
            require(length > 0, "SRAM not exposed");
            int pointer = call("alloc", length);
            try {
                require(call("save_sram", handle, pointer, length) == length, "incomplete SRAM");
                return read(pointer, length);
            } finally { call("free", pointer, length); }
        }

        void restoreSram(byte[] bytes) throws Exception {
            int pointer = call("alloc", bytes.length);
            try {
                memory.writeBytes(pointer, bytes, 0, bytes.length);
                require(call("load_sram", handle, pointer, bytes.length) == 0, "SRAM load failed");
            } finally { call("free", pointer, bytes.length); }
        }

        @Override public void close() throws Exception {
            call("destroy", handle);
            instance.close(); store.close(); module.close(); engine.close(); runtime.close();
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Expected original and derived WASM paths");
        byte[] rom = SfcLegalTestRom.create();
        MessageDigest video = MessageDigest.getInstance("SHA-256");
        MessageDigest audio = MessageDigest.getInstance("SHA-256");
        int stateChecks = 0;
        int frames = 180;
        try (Core original = new Core(Path.of(args[0])); Core derived = new Core(Path.of(args[1]))) {
            require(original.call("run_frame", original.handle) == -1, "original lifecycle rejection");
            require(derived.call("run_frame", derived.handle) == -1, "derived lifecycle rejection");
            original.loadRom(rom); derived.loadRom(rom);
            require(Arrays.equals(original.state(), derived.state()), "initial serialized state differs");
            for (int i = 0; i < frames; i++) {
                int p1 = (i % 3 == 0) ? SfcButton.A.mask() : SfcButton.RIGHT.mask();
                int p2 = (i % 5 == 0) ? SfcButton.B.mask() : SfcButton.LEFT.mask();
                original.frame(p1, p2); derived.frame(p1, p2);
                byte[] rgba = original.rgba(), pcm = original.pcm();
                require(rgba.length > 0 && pcm.length > 0, "missing output");
                require(Arrays.equals(rgba, derived.rgba()), "video mismatch at " + i);
                require(Arrays.equals(pcm, derived.pcm()), "audio mismatch at " + i);
                video.update(rgba); audio.update(pcm);
                if (i % 30 == 29) {
                    byte[] oldState = original.state(), newState = derived.state();
                    require(Arrays.equals(oldState, newState), "serialized state mismatch at " + i);
                    derived.restore(oldState);
                    original.restore(newState);
                    require(Arrays.equals(oldState, derived.state()), "old-to-new state round-trip mismatch");
                    require(Arrays.equals(newState, original.state()), "new-to-old state round-trip mismatch");
                    stateChecks++;
                }
            }
            original.checkSram(); derived.checkSram();
            require(original.call("reset", original.handle, 0) == 0 && derived.call("reset", derived.handle, 0) == 0, "soft reset failed");
            require(Arrays.equals(original.state(), derived.state()), "soft reset state mismatch");
            require(original.call("reset", original.handle, 1) == 0 && derived.call("reset", derived.handle, 1) == 0, "hard reset failed");
            require(Arrays.equals(original.state(), derived.state()), "hard reset state mismatch");
        }
        byte[] ramRom = rom.clone();
        ramRom[0x7FD6] = 2; // The same original program, with ROM+RAM+battery metadata.
        ramRom[0x7FD8] = 3; // 8 KiB SRAM.
        int sum = 0;
        for (int i = 0; i < ramRom.length; i++) if (i < 0x7FDC || i > 0x7FDF) sum = (sum + Byte.toUnsignedInt(ramRom[i])) & 0xffff;
        int checksum = (sum + 0x1fe) & 0xffff;
        ramRom[0x7FDC] = (byte) (checksum ^ 0xffff);
        ramRom[0x7FDD] = (byte) ((checksum ^ 0xffff) >>> 8);
        ramRom[0x7FDE] = (byte) checksum; ramRom[0x7FDF] = (byte) (checksum >>> 8);
        try (Core original = new Core(Path.of(args[0])); Core derived = new Core(Path.of(args[1]))) {
            original.loadRom(ramRom); derived.loadRom(ramRom);
            byte[] seed = new byte[8192];
            for (int i = 0; i < seed.length; i++) seed[i] = (byte) (i * 31 + 7);
            original.restoreSram(seed); derived.restoreSram(seed);
            require(Arrays.equals(seed, original.sram()) && Arrays.equals(seed, derived.sram()), "SRAM seed changed");
            for (int i = 0; i < 60; i++) { original.frame(0, 0); derived.frame(0, 0); }
            byte[] oldState = original.state(), newState = derived.state();
            require(Arrays.equals(oldState, newState), "SRAM cartridge states differ");
            require(Arrays.equals(original.sram(), derived.sram()), "SRAM cartridge payload differs");
            derived.restoreSram(original.sram()); derived.restore(oldState);
            original.restoreSram(derived.sram()); original.restore(newState);
            require(Arrays.equals(oldState, derived.state()) && Arrays.equals(newState, original.state()), "SRAM cartridge cross-state failed");
            require(Arrays.equals(seed, original.sram()) && Arrays.equals(seed, derived.sram()), "SRAM round-trip changed bytes");
        }
        // Re-create after close: exercise independent lifecycle without running Minecraft.
        try (Core derived = new Core(Path.of(args[1]))) { derived.loadRom(rom); derived.frame(0, 0); }
        System.out.println("{\"ok\":true,\"framesCompared\":" + frames + ",\"bidirectionalStateCheckpoints\":" + stateChecks
                + ",\"videoSha256\":\"" + HexFormat.of().formatHex(video.digest()) + "\",\"audioSha256\":\""
                + HexFormat.of().formatHex(audio.digest()) + "\",\"softHardResetAndReopen\":true,\"romOnlySramCheck\":true,\"sramCartridgeFrames\":60,\"sramBytesRoundTripped\":8192,\"minecraftClientTested\":false}");
    }
}
