// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade;

import cn.piq.nativearcade.registry.NativeArcadeRegistries;
import com.mojang.logging.LogUtils;
import cn.piq.fcarcade.cabinet.CabinetBackends;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/** Common registration and pre-world runtime preparation; never loads a native core or client class. */
@Mod(NativeArcadeMod.MOD_ID)
public final class NativeArcadeMod {
    public static final String MOD_ID="piq_native_arcade";
    public static final Logger LOGGER=LogUtils.getLogger();
    public static final ResourceLocation BACKEND_ID=ResourceLocation.fromNamespaceAndPath(MOD_ID,"mame");
    public NativeArcadeMod(IEventBus bus){
        NativeArcadeRegistries.register(bus); // Existing cabinet IDs remain valid for saved worlds.
        bus.addListener((FMLCommonSetupEvent event)->event.enqueueWork(NativeArcadeMod::registerBackend));
        // This parallel lifecycle listener completes before loading advances to a playable world.
        // Do not move filesystem work into enqueueWork or the pure registerBackend QA entry point.
        bus.addListener((FMLCommonSetupEvent event)->NativeBundledRuntime.prepare());
    }
    /** Explicit common-only declaration, also exercised by the isolated dedicated-server QA loader. */
    public static void registerBackend(){
        CabinetBackends.register(BACKEND_ID,"MAME 原生街机",false);
        CabinetBackends.registerNetwork(BACKEND_ID,4);
        CabinetBackends.registerSnapshotSync(BACKEND_ID,2,NativeSnapshotProfile.COMPATIBILITY_ID);
        cn.piq.fcarcade.cabinet.CabinetNetplay.register(BACKEND_ID,4,NativeNetplayProfile::profile);
        cn.piq.fcarcade.cabinet.PgmServicePolicy.registerDiagnosticBackend(BACKEND_ID);
        cn.piq.fcarcade.server.hosted.ServerCoreRegistry.register(BACKEND_ID,new cn.piq.nativearcade.server.NativeServerCoreFactory());
    }
}
