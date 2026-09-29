package cn.piq.j2mearcade;

import com.mojang.logging.LogUtils;
import cn.piq.j2mearcade.core.MicroEmuRuntimeProbe;
import cn.piq.j2mearcade.registry.ModBlocks;
import cn.piq.j2mearcade.registry.ModBlockEntities;
import cn.piq.j2mearcade.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;

@Mod(PiqJ2meArcadeMod.MOD_ID)
public final class PiqJ2meArcadeMod {
    public static final String MOD_ID = "piq_j2me_arcade";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PiqJ2meArcadeMod(IEventBus modBus) {
        ModBlocks.BLOCKS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModItems.ITEMS.register(modBus);
        modBus.addListener(J2meNetwork::register);
        modBus.addListener(this::addCreativeTabItems);

        // The Java ME VM and its AWT-backed device implementation are strictly
        // client-side. A dedicated server owns sessions/sync later, but must not
        // load java.desktop emulator classes during common mod construction.
        if (FMLEnvironment.dist.isClient()) {
            cn.piq.j2mearcade.client.ClientJ2meArcade.register(modBus);
            MicroEmuRuntimeProbe.Result probe = MicroEmuRuntimeProbe.probe();
            if (probe.available()) {
                LOGGER.info("[PIQ J2ME] MicroEmulator runtime ready on Java {}", probe.javaVersion());
            } else {
                LOGGER.error("[PIQ J2ME] MicroEmulator client runtime unavailable", probe.error());
            }
        } else {
            LOGGER.info("[PIQ J2ME] Dedicated server loaded; emulator runtime remains client-side");
        }
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.J2ME_ARCADE.get());
        }
    }
}
