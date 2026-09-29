package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/** Never reuse a previous server's policy after reconnecting. */
@EventBusSubscriber(modid="piq_fc_arcade",bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class CabinetClientSettings {
    private static Connection source;
    private static CabinetServerNetwork.State state;
    private CabinetClientSettings(){}
    private static Connection current(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    public static CabinetServerRules rules(){return state!=null&&source!=null&&source==current()&&source.isConnected()?state.rules():CabinetServerRules.DEFAULT;}
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->CabinetServerNetwork.clientSink((connection,value)->{
        if(connection!=current())return;
        if(connection!=source){source=connection;state=null;}
        if(state!=null&&value.revision()<state.revision())return;
        state=value;
        if(Minecraft.getInstance().screen instanceof CabinetServerSettingsScreen page)page.receive(connection,value);
    }));}
    @EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
    public static final class Events {
        @SubscribeEvent public static void logout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event){source=null;state=null;}
    }
}
