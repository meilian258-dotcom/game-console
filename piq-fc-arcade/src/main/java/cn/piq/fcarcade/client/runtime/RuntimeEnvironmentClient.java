package cn.piq.fcarcade.client.runtime;

import cn.piq.fcarcade.FcArcadeMod;
import cn.piq.fcarcade.runtime.RuntimeCatalog;
import cn.piq.fcarcade.runtime.RuntimeStartupState;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.EnumSet;
import java.util.Set;

/** Client bootstrap only. Register no client extension on a dedicated server. */
public final class RuntimeEnvironmentClient {
    private static boolean failureReported;
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
        // Diagnostics remain available on demand. Never replace the title/game screen automatically.
        // Startup validation keeps its original checks; presentation reads only its immutable result.
        if(failureReported||mc.player==null)return;
        RuntimeStartupState.failure(mc.gameDirectory.toPath()).ifPresent(report->{
            failureReported=true;
            cn.piq.fcarcade.client.ui.DeviceNotices.record("运行环境",report.summary()+"\n"+String.join("\n",report.details())
                    +"\n无来源的旧组件启动错误会保守保留至重启；各组件成功只清除其自身错误。");
        });
    }
}
