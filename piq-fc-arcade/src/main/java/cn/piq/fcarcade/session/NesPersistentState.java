// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.session;

import cn.piq.retro.libretro.LibretroSaveMemory;
import java.nio.ByteBuffer;
import java.security.*;
import java.util.Arrays;

/** One bounded transaction: exact resume snapshot and native memory from the same core instant.
 * Ownership remains in the existing save store. Never send this wrapper to spectator replay.
 */
public final class NesPersistentState {
    private static final int MAGIC = 0x50465031, HEADER = 84;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private NesPersistentState() {}
    public record Parts(byte[] snapshot, byte[] identity, LibretroSaveMemory memory) {
        public Parts { snapshot = snapshot.clone(); identity = identity.clone(); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
        @Override public byte[] identity() { return identity.clone(); }
    }
    public static boolean isBundle(byte[] bytes) {
        return bytes != null && bytes.length >= 4 && ByteBuffer.wrap(bytes).getInt() == MAGIC;
    }
    public static byte[] encode(byte[] snapshot, byte[] identity, LibretroSaveMemory memory) {
        if (snapshot == null || identity == null || identity.length != 32 || memory == null || isBundle(snapshot))
            throw new IllegalArgumentException("Invalid FC persistent state");
        byte[] ram = memory.ram(), rtc = memory.rtc();
        long size = (long) HEADER + snapshot.length + ram.length + rtc.length;
        if (snapshot.length < 1 || size > MAX_BYTES) throw new IllegalArgumentException("FC persistent state exceeds limit");
        ByteBuffer b = ByteBuffer.allocate((int)size);
        b.putInt(MAGIC).putInt(1).put(identity).putInt(snapshot.length).putInt(ram.length).putInt(rtc.length);
        b.put(snapshot).put(ram).put(rtc); b.put(hash(b.array(), b.position()));
        return b.array();
    }
    public static Parts decode(byte[] bytes) {
        if (bytes == null || bytes.length <= HEADER || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("FC persistent state size");
        ByteBuffer b = ByteBuffer.wrap(bytes);
        if (b.getInt() != MAGIC || b.getInt() != 1) throw new IllegalArgumentException("FC persistent state format");
        byte[] identity = new byte[32]; b.get(identity);
        int state = b.getInt(), ram = b.getInt(), rtc = b.getInt();
        if (state < 1 || ram < 0 || rtc < 0 || (long)state + ram + rtc != bytes.length - HEADER
                || !MessageDigest.isEqual(hash(bytes, bytes.length - 32), Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length)))
            throw new IllegalArgumentException("FC persistent state checksum/length");
        byte[] snapshot = new byte[state], saveRam = new byte[ram], clock = new byte[rtc];
        b.get(snapshot); b.get(saveRam); b.get(clock);
        if (isBundle(snapshot)) throw new IllegalArgumentException("Nested FC persistent state");
        return new Parts(snapshot, identity, new LibretroSaveMemory(saveRam, clock));
    }
    /** Plain PLR1/WASM snapshots stay byte-for-byte unchanged. Invalid bundles are never downgraded. */
    public static byte[] snapshot(byte[] bytes) {
        return isBundle(bytes) ? decode(bytes).snapshot() : bytes.clone();
    }
    private static byte[] hash(byte[] bytes, int length) {
        try { var md = MessageDigest.getInstance("SHA-256"); md.update(bytes, 0, length); return md.digest(); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
