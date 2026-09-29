package cn.piq.fcarcade.client;

import cn.piq.fcarcade.rom.RomDescriptor;
import cn.piq.fcarcade.rom.RomRepository;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class ClientRomLibrary {
    private ClientRomLibrary() {
    }

    static List<RomDescriptor> list() throws IOException {
        Path root = ClientFcDirectories.prepareRomDirectory();
        return new RomRepository(root).list();
    }

    static RomDescriptor loadBySha256(String sha256) throws IOException {
        return new RomRepository(ClientFcDirectories.prepareRomDirectory()).findBySha256(sha256);
    }

    static RomDescriptor storeDownloaded(
            String fileName,
            String sha256,
            byte[] bytes
    ) throws IOException {
        return new RomRepository(ClientFcDirectories.prepareRomDirectory()).storeVerified(fileName, sha256, bytes);
    }

    public static Path root() {
        return ClientFcDirectories.romDirectory();
    }
}
