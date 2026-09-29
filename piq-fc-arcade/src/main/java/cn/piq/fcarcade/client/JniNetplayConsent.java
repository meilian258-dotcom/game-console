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

/** JNI is enabled locally by default. A local opt-out lasts for this connection; rooms cannot undo it. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class JniNetplayConsent {
    private static final cn.piq.retro.libretro.JniClientPreference preference=new cn.piq.retro.libretro.JniClientPreference();
    private static boolean opening;
    private JniNetplayConsent() {}
    private static Connection current(){var c=Minecraft.getInstance().getConnection();return c==null?null:c.getConnection();}
    public static boolean allowed(){var c=current();return preference.enabled(c,c!=null&&c.isConnected(),
            cn.piq.retro.libretro.LibretroRuntimes.defaultBackend(true)==cn.piq.retro.libretro.LibretroRuntimes.Backend.JNI_TRIAL);}
    public static void confirm(Screen parent,Runnable accepted) {
        var mc=Minecraft.getInstance();var source=current();if(source==null)return;
        if(allowed()){accepted.run();return;}
        String unavailable=cn.piq.retro.libretro.LibretroRuntimes.jniUnavailableReason();
        if(!unavailable.isEmpty()){if(mc.player!=null)mc.player.displayClientMessage(Component.literal(unavailable),false);return;}
        mc.setScreen(new ConfirmScreen(yes->{
            boolean valid=current()==source&&source.isConnected();
            if(yes&&valid)preference.reset();
            mc.setScreen(valid?parent:null);
            if(yes&&valid)accepted.run();
        },Component.literal("恢复本机 FC JNI Netplay？"),Component.literal(
                "仅 Windows x64、普通双手柄。JNI 原生故障可能使整个 Minecraft 崩溃；请先备份世界。个人/卡带归属不变，继续使用 JNI 独立档，不导入原 RetroArch 进度。参与和旁观自动跟随房间，同一客户端仅一个 JNI 会话。只影响后续启动，不改变正在运行的游戏。")));
    }
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("gameconsole-jni-netplay").executes(context->{opening=true;return 1;})
                .then(Commands.literal("off").executes(context->{preference.decline(current());opening=false;
                    var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal("本次连接不再启动新的 FC JNI 会话；当前游戏不变。重新输入 /gameconsole-jni-netplay 可恢复。"),false);return 1;})));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if(opening){opening=false;var mc=Minecraft.getInstance();if(mc.level!=null&&current()!=null)confirm(null,()->{
            if(mc.player!=null)mc.player.displayClientMessage(Component.literal("本机 FC JNI 已启用，参与和旁观会自动跟随 JNI 房间；当前游戏不切换。"),false);
        });}
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){preference.reset();opening=false;}
}
