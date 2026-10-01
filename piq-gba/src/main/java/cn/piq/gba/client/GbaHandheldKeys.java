package cn.piq.gba.client;

import cn.piq.gba.GbaMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Unbound by default: do not steal another mod's key. Also available via /gameconsole-gba. */
@EventBusSubscriber(modid=GbaMod.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class GbaHandheldKeys {
    static final KeyMapping SETTINGS=new KeyMapping("key.piq_gba.settings",org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN,"key.categories.piq_gba");
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent e){e.register(SETTINGS);}
}
