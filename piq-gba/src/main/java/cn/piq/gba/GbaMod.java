// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba;

import cn.piq.fcarcade.cabinet.CabinetBackends;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import cn.piq.gba.item.GbaHandheldItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionResult;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Cabinet provider and physical handheld item. No native loading or client links on the common side. */
@Mod(GbaMod.ID)
public final class GbaMod {
    public static final String ID="piq_gba";
    public static final ResourceLocation BACKEND=ResourceLocation.fromNamespaceAndPath(ID,"gba");
    private static final DeferredRegister.Items ITEMS=DeferredRegister.createItems(ID);
    public static final DeferredItem<GbaHandheldItem> HANDHELD=ITEMS.register("handheld",()->new GbaHandheldItem(new Item.Properties().stacksTo(1)));
    public GbaMod(IEventBus bus){
        ITEMS.register(bus);
        bus.addListener((FMLCommonSetupEvent e)->e.enqueueWork(GbaMod::registerBackend));
        // Loading worker, after Arcade's common-setup extraction; never a render/server tick.
        bus.addListener((FMLLoadCompleteEvent e)->GbaBundledRuntime.prepare());
        bus.addListener(GbaMod::creativeContents);
        NeoForge.EVENT_BUS.addListener(GbaMod::handheldBlockUse);
        NeoForge.EVENT_BUS.addListener(GbaMod::handheldEntityUse);
        NeoForge.EVENT_BUS.addListener(GbaMod::handheldSpecificEntityUse);
    }
    private static void creativeContents(BuildCreativeModeTabContentsEvent event){
        if(event.getTabKey().equals(ModCreativeTabs.FC.getKey()))event.accept(HANDHELD.get());
    }
    private static void handheldBlockUse(PlayerInteractEvent.RightClickBlock event){
        // The handheld owns right click while held; do not also open a chest/cabinet,
        // including the secondary-hand pass. Never enable an otherwise denied item use.
        if(event.getEntity().getMainHandItem().is(HANDHELD.get()))event.setUseBlock(TriState.FALSE);
    }
    private static void handheldEntityUse(PlayerInteractEvent.EntityInteract event){
        if(disableEntityUse(event)){event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);}
    }
    private static void handheldSpecificEntityUse(PlayerInteractEvent.EntityInteractSpecific event){
        if(disableEntityUse(event)){event.setCancellationResult(InteractionResult.SUCCESS);event.setCanceled(true);}
    }
    private static boolean disableEntityUse(PlayerInteractEvent event){
        // Client cancellation also dispatches its local toggle; don't pre-cancel that handler.
        return !event.getLevel().isClientSide&&event.getEntity().getMainHandItem().is(HANDHELD.get());
    }
    public static void registerBackend(){
        CabinetBackends.register(BACKEND,"GBA · 单席游戏",false);
        CabinetBackends.registerNetwork(BACKEND,1);
        cn.piq.fcarcade.server.hosted.ServerCoreRegistry.register(BACKEND,new cn.piq.gba.server.GbaServerCoreFactory());
    }
}
