// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome;
import cn.piq.sfchome.net.SfcHomeNetwork;
import cn.piq.sfchome.registry.SfcHomeRegistries;
import cn.piq.sfchome.server.SfcHomeServer;
import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Common entry never resolves client renderers or starts a WASM emulator on the server. */
@Mod(SfcHomeMod.MOD_ID)
public final class SfcHomeMod {
    public static final String MOD_ID="piq_sfc_home";
    public static final ResourceLocation CABINET_BACKEND=ResourceLocation.fromNamespaceAndPath(MOD_ID,"sfc");
    public SfcHomeMod(IEventBus bus){
        cn.piq.fcarcade.home.HomeApplianceService.registerControls(ResourceLocation.fromNamespaceAndPath(MOD_ID,"sfc"),
                cn.piq.sfchome.layout.SfcApplianceControls::hit);
        SfcHomeRegistries.register(bus);bus.addListener(SfcHomeNetwork::register);SfcHomeServer.register();
        bus.addListener(cn.piq.sfchome.net.SfcRepairNetwork::register);
        bus.addListener(cn.piq.sfchome.net.SfcLocalWatchNetwork::register);
        CabinetBackends.register(CABINET_BACKEND,"SFC / Super Famicom",false);
        CabinetBackends.registerNetwork(CABINET_BACKEND,2);
        CabinetBackends.registerSync(CABINET_BACKEND);
        cn.piq.fcarcade.server.hosted.ServerCoreRegistry.register(CABINET_BACKEND,new cn.piq.sfchome.server.hosted.SfcServerCoreFactory());
        cn.piq.sfchome.server.SfcWatchProvider.register();
        bus.addListener(SfcHomeMod::creativeContents);
    }
    private static void creativeContents(BuildCreativeModeTabContentsEvent event){
        if(event.getTabKey().equals(ModCreativeTabs.FC.getKey())){
            event.accept(SfcHomeRegistries.CONSOLE_ITEM.get());
            event.accept(SfcHomeRegistries.CARTRIDGE.get());
        }
    }
}
