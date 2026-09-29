// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Local risk consent is not persisted and cannot be granted by the server or a received room. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class JniNetplayConsent {
    private static Connection approved;
    private static boolean opening;
    private JniNetplayConsent() {}
    private static Connection current(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    public static boolean allowed(){var c=current();return c!=null&&c==approved&&c.isConnected();}
    public static void confirm(Screen parent,Runnable accepted) {
        var mc=Minecraft.getInstance();var source=current();if(source==null)return;
        String unavailable=cn.piq.retro.libretro.LibretroRuntimes.jniUnavailableReason();
        if(!unavailable.isEmpty()){if(mc.player!=null)mc.player.displayClientMessage(Component.literal(unavailable),false);return;}
        mc.setScreen(new ConfirmScreen(yes->{
            boolean valid=current()==source&&source.isConnected();
            if(yes&&valid)approved=source;
            mc.setScreen(valid?parent:null);
            if(yes&&valid)accepted.run();
        },Component.literal("允许本次连接使用 FC JNI Netplay 试验？"),Component.literal(
                "仅 Windows x64、普通双手柄。JNI 原生故障可能使整个 Minecraft 崩溃；请先备份世界。个人/卡带归属不变，但使用独立试验档，不导入原 RetroArch 进度。每个参与/旁观客户端都需确认，同一客户端仅一个 JNI 会话。允许持续到本次断开服务器，不改变服务器权限或默认联机方式。")));
    }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gameconsole-jni-netplay").executes(context->{opening=true;return 1;}));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(opening){opening=false;var mc=Minecraft.getInstance();if(mc.level!=null&&current()!=null)confirm(null,()->{});}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){approved=null;opening=false;}
}
