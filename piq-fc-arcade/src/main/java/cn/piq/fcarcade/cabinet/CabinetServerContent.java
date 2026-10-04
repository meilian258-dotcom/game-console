package cn.piq.fcarcade.cabinet;

import cn.piq.retro.storage.ConsoleStorage;
import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Pure path resolution on the server thread; all validation/copying belongs to store IO workers. */
final class CabinetServerContent {
    private CabinetServerContent() {}
    static Path directory(MinecraftServer server) {
        // Keep formal server data OUT of the client's shared-games cache, also in integrated servers.
        return ConsoleStorage.location(server.getServerDirectory()).resolve("piq-cabinet/server-games");
    }
    static CabinetGameStore store(MinecraftServer server) {
        return new CabinetGameStore(directory(server).resolve("objects"),
            ConsoleStorage.location(server.getWorldPath(LevelResource.ROOT)).resolve("piq-cabinet/shared-games/objects"));
    }
}
