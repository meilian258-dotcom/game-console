package cn.piq.computer.client;

import cn.piq.computer.ComputerMod;
import cn.piq.computer.net.ComputerNetwork;
import cn.piq.computer.world.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.*;

@EventBusSubscriber(modid=ComputerMod.ID,value=Dist.CLIENT)
public final class ComputerClient {
    @EventBusSubscriber(modid=ComputerMod.ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void setup(FMLClientSetupEvent e){e.enqueueWork(()->{cn.piq.computer.net.ComputerStreamNetwork.client=new ComputerStreams();ComputerNetwork.client=new ComputerNetwork.Client(){
            public boolean accepts(Object c){var mc=Minecraft.getInstance();return mc.getConnection()!=null&&mc.getConnection().getConnection()==c;}
            public void open(ComputerNetwork.Open m){if(current(m)!=null){if(m.control())ComputerWorldInput.enter(m);else Minecraft.getInstance().setScreen(new ComputerScreen(m));}}
        };});}
        @SubscribeEvent public static void renderers(EntityRenderersEvent.RegisterRenderers e){ComputerRenderer.register(e);}
        @SubscribeEvent public static void models(ModelEvent.RegisterAdditional e){ComputerRenderer.models(e);}
    }
    public static ComputerEntity current(ComputerNetwork.Open m){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||!mc.level.dimension().location().equals(m.dimension())||!mc.level.hasChunkAt(m.pos())||mc.player.distanceToSqr(Vec3.atCenterOf(m.pos()))>64)return null;
        var pc=ComputerBlock.find(mc.level,m.pos());return pc!=null&&pc.hardwareId().equals(m.id())?pc:null;
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent e){FirmwareDisplay.render(e);ComputerPointerDisplay.render(e);}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){ComputerStreams.tick();ComputerPrograms.maintain(false);}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){ComputerWorldInput.stop();ComputerPrograms.stop();ComputerStreams.clear();FirmwareDisplay.clear();ComputerPointerDisplay.clear();}
    @SubscribeEvent public static void shutdown(net.neoforged.neoforge.event.GameShuttingDownEvent e){ComputerWorldInput.stop();ComputerStreams.clear();ComputerPrograms.shutdown();}
}
