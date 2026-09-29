// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.retro.libretro;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class LibretroMemoryStoreTest {
    @TempDir Path root;
    final byte[] id = new byte[32];
    Path file() { return root.resolve(HexFormat.of().formatHex(id)).resolve("memory.bin"); }
    LibretroSaveMemory data(int n) { return new LibretroSaveMemory(new byte[]{(byte)n, 0}, new byte[]{4, 5}); }

    @Test void roundTripBothRegionsAndBackup() throws Exception {
        byte[] first;
        try (var store = new LibretroMemoryStore(root, id)) {
            assertTrue(store.read().isEmpty());
            assertTrue(store.write(data(1))); first = Files.readAllBytes(file());
            assertTrue(store.write(data(2)));
            assertArrayEquals(first, Files.readAllBytes(file().resolveSibling("memory.previous.bin")));
        }
        try (var store = new LibretroMemoryStore(root, id)) {
            var read = store.read().orElseThrow();
            assertArrayEquals(data(2).ram(), read.ram()); assertArrayEquals(data(2).rtc(), read.rtc());
            assertTrue(store.write(data(2)));
            assertArrayEquals(first, Files.readAllBytes(file().resolveSibling("memory.previous.bin")));
        }
    }
    @Test void corruptSaveIsNotOverwritten() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) {
            store.write(data(1)); byte[] broken = Files.readAllBytes(file()); broken[48] ^= 1;
            Files.write(file(), broken);
            assertThrows(IOException.class, store::read);
            assertThrows(IOException.class, () -> store.write(data(2)));
            assertArrayEquals(broken, Files.readAllBytes(file()));
        }
    }
    @Test void truncatedAndWrongIdentityAreRejected() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) {
            store.write(data(1)); byte[] valid = Files.readAllBytes(file());
            Files.write(file(), new byte[]{1}); assertThrows(IOException.class, store::read);
            valid[8] ^= 1; Files.write(file(), valid); assertThrows(IOException.class, store::read);
        }
    }
    @Test void emptyMemoryCannotEraseSave() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) {
            var empty = new LibretroSaveMemory(new byte[0], new byte[0]);
            assertFalse(store.write(empty)); assertFalse(Files.exists(file()));
            store.write(data(1)); byte[] saved = Files.readAllBytes(file());
            assertThrows(IOException.class, () -> store.write(empty));
            assertArrayEquals(saved, Files.readAllBytes(file()));
        }
    }
    @Test void secondWriterIsRejectedThenLeaseCanBeReopened() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) {
            assertThrows(IOException.class, () -> new LibretroMemoryStore(root, id));
        }
        try (var reopened = new LibretroMemoryStore(root, id)) { assertTrue(reopened.read().isEmpty()); }
    }
    @Test void nonRegularBackupDoesNotChangeCurrent() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) {
            store.write(data(1)); byte[] saved = Files.readAllBytes(file());
            Files.createDirectory(file().resolveSibling("memory.previous.bin"));
            assertThrows(IOException.class, () -> store.write(data(2)));
            assertArrayEquals(saved, Files.readAllBytes(file()));
        }
    }
    @Test void differentIdentitiesUseSeparateDirectories() throws Exception {
        try (var store = new LibretroMemoryStore(root, id)) { store.write(data(1)); }
        byte[] other = id.clone(); other[0] = 1;
        try (var store = new LibretroMemoryStore(root, other)) { assertTrue(store.read().isEmpty()); }
    }
    @Test void closedStoreAndForeignThreadAreRejected() throws Exception {
        var store = new LibretroMemoryStore(root, id);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { store.read(); } catch (Throwable e) { error.set(e); } });
        thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, error.get());
        store.close(); store.close(); assertThrows(IllegalStateException.class, store::read);
    }
    @Test void memoryDefensiveCopiesAndBounds() {
        byte[] input = {1}; var data = new LibretroSaveMemory(input, input); input[0] = 5;
        byte[] out = data.ram(); out[0] = 7; assertEquals(1, data.ram()[0]); assertEquals(1, data.rtc()[0]);
        assertThrows(IllegalArgumentException.class, () -> new LibretroSaveMemory(null, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new LibretroSaveMemory(new byte[LibretroSaveMemory.MAX_BYTES], new byte[1]));
    }
}
