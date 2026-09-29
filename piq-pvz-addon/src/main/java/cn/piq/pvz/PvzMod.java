package cn.piq.pvz;

import cn.piq.fcarcade.home.HomeApplianceService;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import cn.piq.pvz.net.PvzNetwork;
import cn.piq.pvz.registry.PvzRegistries;
import cn.piq.pvz.server.PvzServer;
import cn.piq.pvz.world.PvzBlockEntity;
import cn.piq.pvz.world.PvzControls;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Hardware prototype only; the common entry never links a client/runtime class. */
@Mod(PvzMod.MOD_ID)
public final class PvzMod {
    public static final String MOD_ID = "piq_pvz";
    public PvzMod(IEventBus bus) {
        PvzRegistries.register(bus);
        PvzNetwork.register(bus);
        HomeApplianceService.registerControls(PvzBlockEntity.SYSTEM_ID, PvzControls::hit);
        PvzServer.register();
        bus.addListener(PvzMod::creativeContents);
    }
    private static void creativeContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.FC.getKey())) event.accept(PvzRegistries.BOX_ITEM.get());
    }
}
