// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.client;

import cn.piq.sfcarcade.rom.SfcRomEntry;
import cn.piq.sfcarcade.rom.SfcRomRepository;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ClientSfcRomLibrary {
    private ClientSfcRomLibrary() {
    }

    public static SfcRomRepository repository() {
        return new SfcRomRepository(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("sfc-roms"));
    }

    public static Path root() {
        return repository().root();
    }

    public static List<SfcRomEntry> list() throws IOException {
        return repository().list();
    }

    public static SfcRomEntry find(String sha256) throws IOException {
        return repository().find(sha256);
    }

    public static boolean has(String sha256) {
        try {
            return find(sha256) != null;
        } catch (IOException | IllegalArgumentException ignored) {
            return false;
        }
    }
}
