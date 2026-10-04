// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro.jni;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import static cn.piq.retro.libretro.jni.NativeLibretroBridge.*;

/** Synthetic native boundary probe; never reads user ROMs or allocates Java ROM arrays. */
public final class LargeContentProbe {
    private static final long MIB = 1024L * 1024;
    private static String memoryCore, fullPathCore, work;
    private static Path content;

    private static void fixture(long bytes) throws Exception {
        try (var file = new RandomAccessFile(content.toFile(), "rw")) {
            file.setLength(bytes);
            if (bytes > 0) {
                file.seek(0); file.write(0x41);
                file.seek(bytes - 1); file.write(0x5a);
            }
        }
    }

    private static void accepts(long bytes, boolean fullPath) throws Exception {
        fixture(bytes);
        long h = open(fullPath ? fullPathCore : memoryCore, content.toString(), work, work,
                "PIQ mock", fullPath, new int[]{1}, new String[0], 0);
        try {
            int[] meta = new int[11];
            step(h, ByteBuffer.allocateDirect(64), ByteBuffer.allocateDirect(65536),
                    new int[]{0,0,0,0,-1,0,0,0,0,0,0,-1,0}, new int[0], meta, new double[3]);
            check(meta[6] == 16, "frame after persistent extended-info access");
        } finally { close(h); }
        check(availableSlots() == 4, "successful load releases slot: " + bytes);
    }

    private static void rejectsSize(long bytes, boolean fullPath, String reason) throws Exception {
        fixture(bytes);
        try {
            long h = open(fullPath ? fullPathCore : memoryCore, content.toString(), work, work,
                    "PIQ mock", fullPath, new int[]{1}, new String[0], 0);
            close(h);
            throw new AssertionError("Accepted invalid content size " + bytes);
        } catch (IOException expected) {
            check(expected.getMessage().contains(reason), "correct rejection reason: " + expected);
        }
        check(availableSlots() == 4, "failed load releases slot: " + bytes);
    }

    public static void main(String[] args) throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        memoryCore = Path.of(args[1]).toAbsolutePath().toString();
        fullPathCore = Path.of(args[2]).toAbsolutePath().toString();
        work = Path.of(args[3]).toAbsolutePath().toString();
        content = Path.of(work, "synthetic-large.bin");
        Files.createFile(content);
        check(abiVersion() == 2 && availableSlots() == 4, "initial ABI/capacity");
        accepts(64, true);
        accepts(64 * MIB, false);
        accepts(64 * MIB, true);
        accepts(64 * MIB + 1, true);
        accepts(96 * MIB, true);
        rejectsSize(0, false, "empty");
        rejectsSize(0, true, "empty");
        rejectsSize(64 * MIB + 1, false, "64 MiB native memory-content");
        rejectsSize(96 * MIB + 1, true, "96 MiB native full-path");
        fixture(64);
        rejects(() -> open(memoryCore, content.toString(), work, work, "PIQ mock", true,
                new int[]{1}, new String[0], 0), "trusted full-path flag cannot override core metadata");
        check(availableSlots() == 4, "metadata mismatch releases slot");
        System.out.println("JNI_LARGE_CONTENT_OK checks=" + checks + " successfulLoads=5 rejectedSizes=4 slots=" + availableSlots());
    }
}
