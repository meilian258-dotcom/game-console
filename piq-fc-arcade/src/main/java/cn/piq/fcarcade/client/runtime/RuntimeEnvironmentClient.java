package cn.piq.fcarcade.client.runtime;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.runtime.RuntimeCatalog;
import cn.piq.fcarcade.runtime.RuntimeStartupState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.EnumSet;
import java.util.Set;

/** Client bootstrap only. Register no client extension on a dedicated server. */
public final class RuntimeEnvironmentClient {
    private static boolean attempted;
    private static int titleTicks;
    private RuntimeEnvironmentClient(){}
    public static void register(){
        IConfigScreenFactory factory=(container,parent)->new RuntimeEnvironmentScreen(parent);
        ModList.get().getModContainerById(FcArcadeMod.MOD_ID).orElseThrow().registerExtensionPoint(
                IConfigScreenFactory.class,factory);
        NeoForge.EVENT_BUS.addListener(RuntimeEnvironmentClient::tick);
    }
    static Set<RuntimeCatalog.RuntimeId> required(){
        var result=EnumSet.noneOf(RuntimeCatalog.RuntimeId.class);
        if(ModList.get().isLoaded("piq_native_arcade")){
            result.add(RuntimeCatalog.RuntimeId.MAME);result.add(RuntimeCatalog.RuntimeId.NEOGEO_SNAPSHOT);
        }
        if(ModList.get().isLoaded("piq_gba"))result.add(RuntimeCatalog.RuntimeId.GBA);
        return Set.copyOf(result);
    }
    private static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(attempted||!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
        if(++titleTicks<40)return;attempted=true;
        var root=mc.gameDirectory.toPath();
        var ids=required();
        // Cheap existence only at the title screen; hash/ZIP work starts on a worker after opening the page.
        boolean missing=RuntimeCatalog.standard().stream().filter(c->ids.contains(c.id())).flatMap(c->c.files().stream())
                .anyMatch(file->!Files.isRegularFile(root.resolve(file.relativePath()),LinkOption.NOFOLLOW_LINKS));
        // A conflicting/corrupt existing file can pass the cheap presence check.
        // Startup already did the expensive work; only read its immutable result here.
        if(missing||RuntimeStartupState.failure(root).isPresent())mc.setScreen(new RuntimeEnvironmentScreen(mc.screen));
    }
}
