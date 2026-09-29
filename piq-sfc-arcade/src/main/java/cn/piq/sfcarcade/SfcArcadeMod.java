// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade;

import cn.piq.sfcarcade.registry.ModBlocks;
import cn.piq.sfcarcade.registry.ModBlockEntities;
import cn.piq.sfcarcade.registry.ModItems;
import cn.piq.sfcarcade.net.SfcNetwork;
import cn.piq.sfcarcade.net.SfcClientSupport;
import cn.piq.sfcarcade.server.SfcServerManager;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(SfcArcadeMod.MOD_ID)
public final class SfcArcadeMod {
    public static final String MOD_ID = "piq_sfc_arcade";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SfcArcadeMod(IEventBus modBus) {
        ModBlocks.BLOCKS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(SfcNetwork::register);
        modBus.addListener(this::addCreativeTabItems);
        SfcServerManager.register();
        if (FMLEnvironment.dist.isClient()) {
            SfcClientSupport.registerEvents(modBus);
        }
        LOGGER.info("[PIQ SFC] 初始化独立 SFC 街机模组");
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SFC_ARCADE.get());
        }
    }
}
