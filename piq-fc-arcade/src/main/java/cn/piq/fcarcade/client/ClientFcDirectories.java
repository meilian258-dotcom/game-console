package cn.piq.fcarcade.client;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.storage.FcStoragePaths;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Path;

/** Cheap paths for labels; explicit preparation/opening runs outside render callbacks. */
public final class ClientFcDirectories {
    private static volatile int skinCacheState; // 0 unstarted, 1 queued, 2 ready, -1 failed
    private ClientFcDirectories() {}

    public static Path path(FcStoragePaths.Area area) { return FcStoragePaths.path(FMLPaths.GAMEDIR.get(), area); }
    public static Path prepare(FcStoragePaths.Area area) throws IOException { return FcStoragePaths.prepare(FMLPaths.GAMEDIR.get(), area); }
    public static Path romDirectory() { return path(FcStoragePaths.Area.ROMS); }
    public static Path coverDirectory() { return path(FcStoragePaths.Area.COVERS); }
    public static Path prepareRomDirectory() throws IOException { return prepare(FcStoragePaths.Area.ROMS); }
    public static Path prepareCoverDirectory() throws IOException { return prepare(FcStoragePaths.Area.COVERS); }
    public static void openRomDirectory() { open(FcStoragePaths.Area.ROMS); }
    public static void openCoverDirectory() { open(FcStoragePaths.Area.COVERS); }

    private static void open(FcStoragePaths.Area area) {
        if (!ClientCartridgeIo.submit(() -> {
            try {
                Path directory = prepare(area);
                Minecraft.getInstance().execute(() -> {
                    try { Util.getPlatform().openFile(directory.toFile()); }
                    catch (RuntimeException error) { failure(directory, error); }
                });
            } catch (IOException | RuntimeException error) { failure(path(area), error); }
        })) failure(path(area), new IOException("文件操作忙，请稍后重试"));
    }

    /** Cache migration may be large: callers only observe state, never block a render frame. */
    static synchronized boolean skinCacheReady() {
        if (skinCacheState == 0) {
            skinCacheState = 1;
            if (!ClientCartridgeIo.submit(() -> {
                try { prepare(FcStoragePaths.Area.SKIN_CACHE); skinCacheState = 2; }
                catch (IOException | RuntimeException error) { skinCacheState = -1; failure(path(FcStoragePaths.Area.SKIN_CACHE), error); }
            })) skinCacheState = 0;
        }
        return skinCacheState == 2;
    }

    private static void failure(Path directory, Throwable error) {
        FcArcadeMod.LOGGER.error("[PIQ FC] 初始化/打开本地目录失败 {}；旧数据保持不变", directory, error);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) Minecraft.getInstance().player.sendSystemMessage(
                    Component.literal("FC 文件目录不可用：" + directory + "（旧数据已保留，请查看日志）"));
        });
    }
}
