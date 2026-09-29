// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetEmulator;
import cn.piq.fcarcade.client.cabinet.CabinetBackend;
import cn.piq.fcarcade.client.cabinet.CabinetClientBackends;
import cn.piq.fcarcade.client.rom.LocalRomLibrary;
import cn.piq.sfchome.SfcHomeMod;
import cn.piq.sfchome.client.SfcCoreLease;
import java.nio.file.Path;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;

@EventBusSubscriber(modid=SfcHomeMod.MOD_ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class SfcCabinetProvider implements CabinetBackend {
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){
        event.enqueueWork(()->CabinetClientBackends.register(SfcHomeMod.CABINET_BACKEND,new SfcCabinetProvider()));
    }
    @Override public String description(){return "SFC：Mesen-S / libretro；支持两位玩家，测试版仅支持 Windows x64。";}
    @Override public String extensions(){return ".sfc / .smc（可信本地 ROM，32 MiB 上限）";}
    @Override public Path romDirectory(){return LocalRomLibrary.sfcDirectory(FMLPaths.GAMEDIR.get());}
    @Override public Set<String> romExtensions(){return Set.of(".sfc",".smc");}
    @Override public String unavailableReason(){return SfcCoreLease.occupied()&&!SfcCoreLease.observing()?"请先结束当前 SFC 游戏，等待核心退出":null;}
    @Override public CabinetEmulator open(Path rom)throws Exception{cn.piq.sfchome.client.SfcLocalWatchClient.yieldForControl();return new SfcCabinetSession(SfcCabinetRom.read(rom));}
    @Override public cn.piq.fcarcade.cabinet.CabinetSyncCore openSync(Path rom)throws Exception{cn.piq.sfchome.client.SfcLocalWatchClient.yieldForControl();return new SfcCabinetSyncCore(SfcCabinetRom.read(rom));}
}
