// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import cn.piq.gba.GbaMod;
import cn.piq.retro.libretro.LibretroRuntimes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** Local-only selection, cleared on disconnect; no server packet may enable native execution. */
@EventBusSubscriber(modid=GbaMod.ID,value=Dist.CLIENT)
public final class GbaJniChoice {
    private static Connection approved;
    private static boolean opening;
    private static Connection current(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    public static boolean enabled(){return approved!=null&&approved==current()&&approved.isConnected();}
    public static void choose(Screen parent){
        var mc=Minecraft.getInstance();var connection=current();if(connection==null)return;
        if(enabled()){approved=null;mc.setScreen(parent);GbaHandheldClient.notice("后续 GBA 启动使用独立进程；当前运行不切换");return;}
        String reason=LibretroRuntimes.jniUnavailableReason();if(!reason.isEmpty()){GbaHandheldClient.notice(reason);return;}
        mc.setScreen(new ConfirmScreen(yes->{
            boolean valid=current()==connection&&connection.isConnected();
            if(yes&&valid)approved=connection;
            mc.setScreen(valid?parent:null);
        },Component.literal("允许 GBA 使用通用 JNI 试验？"),Component.literal(
                "影响本次连接中后续启动的个人掌机和由你主持的 GBA 单席街机。原生故障可能使整个 Minecraft 崩溃；每个客户端仅一个 JNI 会话。使用独立试验电池档，不导入或覆盖原进程档；不支持 GBA 通讯线。旁观仍接收音画，服务端托管不改。断开服务器后恢复默认独立进程；当前运行不切换。")));
    }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent e){e.getDispatcher().register(Commands.literal("gameconsole-gba-jni").executes(c->{opening=true;return 1;}));}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){if(opening){opening=false;choose(null);}}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){approved=null;opening=false;}
}
