package cn.piq.fcarcade.home.content;

import cn.piq.retro.storage.ConsoleStorage;
import java.nio.file.Path;
import net.minecraft.resources.ResourceLocation;

/** One path rule for folder buttons, local scans and subsequent file reads. No IO here. */
public final class ContentCardDirectories {
    public static Path roms(Path instance,ResourceLocation system){return path(instance,system,"content-cards");}
    public static Path covers(Path instance,ResourceLocation system){return path(instance,system,"content-card-covers");}
    public static Path metadata(Path instance,ResourceLocation system){return path(instance,system,"content-card-metadata");}
    private static Path path(Path instance,ResourceLocation system,String purpose){
        Path root=ConsoleStorage.location(instance).resolve(purpose).resolve(system.getNamespace()).toAbsolutePath().normalize();
        Path result=root.resolve(system.getPath()).normalize();
        if(!result.startsWith(root)||result.equals(root))throw new IllegalArgumentException("Content-card directory escaped data root");
        return result;
    }
    private ContentCardDirectories(){}
}
