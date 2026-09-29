package cn.piq.computer;

import cn.piq.computer.net.ComputerNetwork;
import cn.piq.computer.world.ComputerEntity;
import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.registry.ModCreativeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

@Mod(ComputerMod.ID)
public final class ComputerMod {
    public static final String ID="piq_computer";
    public ComputerMod(IEventBus bus) {
        ComputerRegistry.register(bus); ComputerNetwork.register(bus);cn.piq.computer.net.ComputerStreamNetwork.register(bus);
        HomeSystems.register(ComputerEntity.SYSTEM,new HomeSystems.ServerHooks(){
            @Override public boolean isRunning(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){return c instanceof ComputerEntity pc&&pc.powered;}
            @Override public void onPowerOff(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c){if(c instanceof ComputerEntity pc)pc.stop();}
            @Override public void onPlaybackStopped(net.minecraft.server.level.ServerLevel l,ExternalHomeConsoleBlockEntity c,HomeSystems.StopReason reason){if(c instanceof ComputerEntity pc)pc.stop();}
            @Override public void onInteract(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.InteractionHand h,HomeSystems.Connection c,net.minecraft.world.phys.BlockHitResult hit){}
        });
        bus.addListener(ComputerMod::creative);
    }
    private static void creative(BuildCreativeModeTabContentsEvent e){if(e.getTabKey().equals(ModCreativeTabs.FC.getKey()))ComputerRegistry.ITEMS.getEntries().stream().filter(i->!ComputerRegistry.legacyPeripheral(i.getId().getPath())).forEach(i->e.accept(i.get()));}
}
