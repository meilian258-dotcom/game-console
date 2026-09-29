// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.core.libretro;

import cn.piq.retro.libretro.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LibretroMemoryPersistenceTest {
    @TempDir Path root;
    private LibretroProcess start(byte[] rom) {
        var core = new LibretroProcess(GenericLibretroNesCore.profile(false));
        try { core.load(rom); return core; } catch (RuntimeException e) { core.close(); throw e; }
    }
    private void run(LibretroProcess core) {
        core.run(List.of(new LibretroProcess.Controls(new int[2], 0)), 0);
    }
    @Test void batteryProgressSurvivesFreshWorkerWithoutTransientState() throws Exception {
        byte[] identity;
        try (var core = start(batteryRom())) {
            identity = core.persistenceIdentity();
            run(core); assertNotEquals(0x5a, core.memory(2)[0] & 255);
            assertEquals(0x5a, core.saveMemory().ram()[0] & 255);
            try (var store = new LibretroMemoryStore(root, identity)) {
                assertFalse(store.restore(core)); assertTrue(store.save(core));
            }
        }
        // A new native worker, not reset or unserialize on the original instance.
        try (var core = start(batteryRom()); var store = new LibretroMemoryStore(root, identity)) {
            assertArrayEquals(identity, core.persistenceIdentity()); assertTrue(store.restore(core));
            run(core); assertEquals(0x5a, core.memory(2)[0] & 255);
        }
        // A clean worker without the disk restore does not see the saved progress.
        try (var core = start(batteryRom())) {
            run(core); assertNotEquals(0x5a, core.memory(2)[0] & 255);
        }
    }
    @Test void otherContentIsRejectedWithoutKillingCore() throws Exception {
        byte[] identity;
        try (var core = start(batteryRom())) { identity = core.persistenceIdentity(); }
        byte[] other = batteryRom(); other[100] = 1;
        try (var core = start(other); var store = new LibretroMemoryStore(root, identity)) {
            assertThrows(IOException.class, () -> store.restore(core));
            assertThrows(IOException.class, () -> store.save(core));
            run(core); assertEquals(0x5a, core.saveMemory().ram()[0] & 255);
        }
    }
    @Test void invalidRtcRegionFailsClosedWithoutTouchingDisk() throws Exception {
        try (var core = start(batteryRom()); var store = new LibretroMemoryStore(root, core.persistenceIdentity())) {
            run(core); store.save(core);
            Path file = root.resolve(java.util.HexFormat.of().formatHex(core.persistenceIdentity())).resolve("memory.bin");
            byte[] saved = Files.readAllBytes(file);
            assertThrows(IllegalStateException.class, () -> core.restoreSaveMemory(
                    new LibretroSaveMemory(core.saveMemory().ram(), new byte[1])));
            assertArrayEquals(saved, Files.readAllBytes(file));
        }
    }
    // Self-authored iNES NROM battery test: remember initial SRAM in $00, write $5a to $6000, loop.
    private static byte[] batteryRom() {
        byte[] rom = new byte[16 + 16384 + 8192];
        rom[0] = 'N'; rom[1] = 'E'; rom[2] = 'S'; rom[3] = 0x1a;
        rom[4] = 1; rom[5] = 1; rom[6] = 2; rom[8] = 1;
        int[] code = {0x78,0xd8,0xa2,0xff,0x9a,0xad,0x00,0x60,0x85,0x00,
                0xa9,0x5a,0x8d,0x00,0x60,0x4c,0x0f,0x80};
        for (int i = 0; i < code.length; i++) rom[16+i] = (byte)code[i];
        for (int i = 0x3ffa; i < 0x4000; i += 2) { rom[16+i] = 0; rom[16+i+1] = (byte)0x80; }
        return rom;
    }
}
