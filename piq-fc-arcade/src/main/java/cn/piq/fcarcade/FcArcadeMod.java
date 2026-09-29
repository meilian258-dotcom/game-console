package cn.piq.fcarcade;

import com.mojang.logging.LogUtils;
import cn.piq.fcarcade.registry.ModBlocks;
import cn.piq.fcarcade.registry.ModBlockEntities;
import cn.piq.fcarcade.registry.ModItems;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import cn.piq.fcarcade.server.ServerArcadeSessions;
import cn.piq.fcarcade.server.ServerSkinService;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraft.world.item.CreativeModeTabs;
import org.slf4j.Logger;

@Mod(FcArcadeMod.MOD_ID)
public final class FcArcadeMod {
    public static final String MOD_ID = "piq_fc_arcade";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FcArcadeMod(IEventBus modBus) {
        // Run once before any addon can open an authoritative instance store.
        cn.piq.retro.storage.ConsoleStorage.root(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
        cn.piq.retro.storage.RuntimeWorkspace.configure(net.neoforged.fml.loading.FMLPaths.GAMEDIR.get());
        cn.piq.fcarcade.cabinet.CabinetHostingConfig.register();
        cn.piq.fcarcade.access.PlayerContentAccess.register();
        ModBlocks.BLOCKS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);
        cn.piq.fcarcade.furniture.FurnitureRegistry.register(modBus);
        modBus.addListener(FcNetwork::register);
        modBus.addListener(cn.piq.fcarcade.home.CartridgeNetwork::register);
        modBus.addListener(cn.piq.fcarcade.cabinet.CabinetNetwork::register);
        modBus.addListener(cn.piq.fcarcade.cabinet.CabinetGameNetwork::register);
        modBus.addListener(this::addCreativeTabItems);
        ServerArcadeSessions.register();
        cn.piq.fcarcade.cabinet.ServerCabinets.register();
        cn.piq.fcarcade.cabinet.CabinetSharedGameService.register();
        cn.piq.fcarcade.cabinet.CabinetLinks.register();
        ServerSkinService.register();
        cn.piq.fcarcade.server.ServerCartridgeService.register();
        cn.piq.fcarcade.home.HomeHardware.register();
        cn.piq.fcarcade.home.ZapperStandService.register();
        if (FMLEnvironment.dist.isClient()) {
            cn.piq.fcarcade.client.runtime.RuntimeEnvironmentClient.register();
            cn.piq.fcarcade.client.cabinet.CabinetSharedGames.register();
            cn.piq.fcarcade.client.ClientArcadeEvents.register(modBus);
            cn.piq.fcarcade.client.ClientCartridgeEditor.register();
            cn.piq.fcarcade.client.ClientCartridgeAssembly.register();
            cn.piq.fcarcade.client.HomeHardwareRenderer.register(modBus);
            cn.piq.fcarcade.client.zapper.ZapperStandRenderer.register(modBus);
        }
        LOGGER.info("[PIQ FC] 初始化 FC 街机模组");
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.LEGACY_FC_ARCADE.get());
            event.accept(ModItems.FAMICOM_CONSOLE.get());
            event.accept(ModItems.SUBOR_CONSOLE.get());
            event.accept(ModItems.RETRO_TV.get());
            event.accept(ModItems.GRAY_CRT_TV.get());
            event.accept(ModItems.RED_CRT_TV.get());
            event.accept(ModItems.ARCADE_COIN.get());
            event.accept(ModItems.DUAL_CABINET.get());
            event.accept(ModItems.FC_CARTRIDGE.get());
            event.accept(ModItems.FC_CARTRIDGE_BOARD.get());
            event.accept(ModItems.FC_CARTRIDGE_SHELL.get());
            event.accept(ModItems.PANEL_TV_2.get());
            event.accept(ModItems.PANEL_TV_3.get());
            event.accept(ModItems.CARTRIDGE_COMPUTER.get());
            event.accept(ModItems.TV_REMOTE.get());
            event.accept(ModItems.DEBUG_SCREWDRIVER.get());
            event.accept(ModItems.ADMIN_TERMINAL.get());
            event.accept(ModItems.AV_CABLE.get());
            event.accept(ModItems.ZAPPER_STAND.get());
            event.accept(ModItems.ZAPPER_STAND_CABLE.get());
            if (ModList.get().isLoaded("waterframes")) {
                event.accept(ModItems.WATERFRAMES_FC_ARCADE.get());
                event.accept(ModItems.WATERFRAMES_TV_FC_ARCADE.get());
                event.accept(ModItems.WATERFRAMES_TV_BOX_FC_ARCADE.get());
                event.accept(ModItems.WATERFRAMES_PANEL_FC_ARCADE.get());
            }
        }
    }
}
