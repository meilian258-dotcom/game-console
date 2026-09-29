package cn.piq.flashbox;

import cn.piq.fcarcade.home.HomeApplianceService;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import cn.piq.flashbox.net.FlashBoxNetwork;
import cn.piq.flashbox.registry.FlashBoxRegistries;
import cn.piq.flashbox.server.FlashBoxServer;
import cn.piq.flashbox.world.FlashBoxBlockEntity;
import cn.piq.flashbox.world.FlashBoxControls;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/** Hardware prototype only; the common entry never links a client/runtime class. */
@Mod(FlashBoxMod.MOD_ID)
public final class FlashBoxMod {
    public static final String MOD_ID = "piq_flash_box";
    public FlashBoxMod(IEventBus bus) {
        FlashBoxRegistries.register(bus);
        FlashBoxNetwork.register(bus);
        HomeApplianceService.registerControls(FlashBoxBlockEntity.SYSTEM_ID, FlashBoxControls::hit);
        FlashBoxServer.register();
        bus.addListener(FlashBoxMod::creativeContents);
    }
    private static void creativeContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.FC.getKey())) event.accept(FlashBoxRegistries.BOX_ITEM.get());
    }
}
