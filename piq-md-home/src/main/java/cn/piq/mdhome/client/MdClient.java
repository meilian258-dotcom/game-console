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
    @SubscribeEvent public static void renderers(net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e){
        e.registerBlockEntityRenderer(MdMod.ENTITY.get(),context->new MdRenderer());
    }
    @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(()->{var p=new Provider();ControllerCapture.register(MdMod.SYSTEM,p);PrivateHomeClient.register(MdMod.SYSTEM,p);MdCoreChoice.register();MdPublicClient.install();MdControllerVisual.install();ControllerPose.registerController(MdMod.CONTROLLER.get());});}
    @SubscribeEvent public static void extensions(net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent e){
        e.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions(){
            private MdCartridgeRenderer renderer;
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new MdCartridgeRenderer();return renderer;}
        },MdMod.CARTRIDGE.get());
        e.registerItem(new net.neoforged.neoforge.client.extensions.common.IClientItemExtensions(){
            private MdControllerRenderer renderer;
            public net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer getCustomRenderer(){if(renderer==null)renderer=new MdControllerRenderer();return renderer;}
            public net.minecraft.client.model.HumanoidModel.ArmPose getArmPose(net.minecraft.world.entity.LivingEntity entity,net.minecraft.world.InteractionHand hand,ItemStack stack){return ControllerPose.armPose(entity,hand,stack);}
            public boolean applyForgeHandTransform(com.mojang.blaze3d.vertex.PoseStack poses,net.minecraft.client.player.LocalPlayer player,net.minecraft.world.entity.HumanoidArm arm,ItemStack stack,float partial,float equip,float swing){return ControllerPose.firstTransform(poses,player,arm,stack,equip,swing);}
        },MdMod.CONTROLLER.get());
    }
    @SubscribeEvent public static void models(net.neoforged.neoforge.client.event.ModelEvent.RegisterAdditional e){e.register(MdCartridgeRenderer.MODEL);for(var model:MdControllerRenderer.MODELS)e.register(model);}
    public static final class Provider implements PrivateHomeClient.Provider {
        public String label(){return "MD2 · "+MdProfile.profile().name()+"（私人单人）";}
        public String storageKey(){return "md";}
        public boolean cartridgePower(){return true;}
        public boolean independentCartridgePower(){return true;}
        public UUID cartridgeSession(Player p,BlockEntity entity){
            return p!=null&&p.isAlive()&&!p.isSpectator()&&entity instanceof MdConsole c&&!c.isRemoved()&&c.getLevel()==p.level()
                    &&!c.publicPlay()&&c.powerSession()!=null&&c.hasInsertedCartridge()&&p.getUUID().equals(c.powerHost())?c.powerSession():null;
        }
        public boolean acceptsFile(String name){return MdRom.accepts(name);}
        public String fileHint(){return ".md / .bin / .gen（普通卡带，非CD/32X）";}
        public KeyboardConfig.Profile profile(){return KeyboardConfig.Profile.SFC;}
        // Preserve canonical bindings; MdProfile maps them to GX's RetroPad layout.
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
            if(id==null||data==null||!(entity instanceof MdConsole c)||c.isRemoved()||p==null||!p.isAlive()||p.isSpectator()||c.getLevel()!=p.level())return false;
            var t=data.copyTag();
            int port=MdController.port(s);
            return t.hasUUID("MdConsole")&&t.getUUID("MdConsole").equals(c.hardwareId())&&id.equals(c.loan(port))&&p.getUUID().equals(c.borrower(port))&&MdInteractionPolicy.inControllerRange(p.distanceToSqr(c.getBlockPos().getCenter()));
        }
        public PrivateEngine create(Path rom,Path root){return create(rom,root,LibretroRuntimes.defaultBackend(true));}
        public boolean supportsJniTrial(){return true;}
        public PrivateEngine create(Path rom,Path root,LibretroRuntimes.Backend b){return new MdEngine(rom,root,b);}
        public PrivateEngine createCartridge(Path rom,Path root,LibretroRuntimes.Backend b,BlockEntity entity){
            if(!(entity instanceof MdConsole c))throw new IllegalArgumentException("MD 主机已失效");
            if(c.publicPlay())throw new IllegalStateException("公开会话必须使用已授权的公共运行器");
            if(cn.piq.fcarcade.home.content.ContentCardData.saveMode(c.cartridge())==1)throw new IllegalStateException("私人模式不能覆盖卡带归属存档");
            var engine=new MdEngine(rom,root,b,MdProfile.Core.GENESIS_PLUS_GX,cn.piq.fcarcade.home.content.ContentCardData.saveMode(c.cartridge())!=0,true);
            MdPublicClient.privateEngine(engine,c.powerSession());
            MdControllerVisual.privateEngine(engine,c);return engine;
        }
    }
}
