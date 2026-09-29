// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.mdhome.*;
import cn.piq.fcarcade.client.*;
import cn.piq.fcarcade.client.privateplay.PrivateEngine;
import cn.piq.retro.client.KeyboardConfig;
import cn.piq.retro.libretro.LibretroRuntimes;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import java.nio.file.Path;
import java.util.UUID;

@EventBusSubscriber(modid=MdMod.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
public final class MdClient {
    @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(()->{var p=new Provider();ControllerCapture.register(MdMod.SYSTEM,p);PrivateHomeClient.register(MdMod.SYSTEM,p);});}
    public static final class Provider implements PrivateHomeClient.Provider {
        public String label(){return "MD2（私人单人）";}
        public String storageKey(){return "md";}
        public boolean acceptsFile(String name){return MdRom.accepts(name);}
        public String fileHint(){return ".md / .bin / .gen（普通卡带，非CD/32X）";}
        public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.SFC;}
        // Existing configurable canonical 12-bit layout, mapped by BlastEm: B→A, A→B, R→C, Y→X, X→Y, L→Z.
        public int[][] keys(){return new int[][]{{74},{76},{259},{257},{87},{83},{65},{68},{75},{73},{79},{80}};}
        public UUID identity(ItemStack s){return MdController.loan(s);}
        public UUID lease(ItemStack s){return s.getCount()==1?identity(s):null;}
        public BlockEntity locate(Player p,ItemStack s){
            var data=s.get(DataComponents.CUSTOM_DATA);if(data==null)return null;var t=data.copyTag();
            if(!t.getString("MdDimension").equals(p.level().dimension().location().toString()))return null;
            var pos=BlockPos.of(t.getLong("MdPos"));if(p.distanceToSqr(pos.getCenter())>36||!p.level().hasChunkAt(pos))return null;
            var c=p.level().getBlockEntity(pos);return matches(p,s,c)?c:null;
        }
        public boolean matches(Player p,ItemStack s,BlockEntity entity){
            var id=lease(s);var data=s.get(DataComponents.CUSTOM_DATA);
            if(id==null||data==null||!(entity instanceof MdConsole c)||c.isRemoved()||p==null||!p.isAlive()||p.isSpectator()||c.getLevel()!=p.level()||!c.hasInsertedCartridge()||c.visualPowered())return false;
            var t=data.copyTag();
            return t.hasUUID("MdConsole")&&t.getUUID("MdConsole").equals(c.hardwareId())&&id.equals(c.loan())&&p.getUUID().equals(c.borrower())&&p.distanceToSqr(c.getBlockPos().getCenter())<=36;
        }
        public PrivateEngine create(Path rom,Path root){return new MdEngine(rom,root,LibretroRuntimes.defaultBackend(true));}
        public boolean supportsJniTrial(){return true;}
        public PrivateEngine create(Path rom,Path root,LibretroRuntimes.Backend b){return new MdEngine(rom,root,b);}
    }
}
