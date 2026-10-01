// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Explicit local compatibility switch; no server authority or persistent world mutation. */
public final class MdCoreChoice {
    private static MdProfile.Core selected=MdProfile.Core.GENESIS_PLUS_GX;
    private MdCoreChoice(){}
    public static MdProfile.Core selected(){return selected;}
    public static void register(){
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e)->e.getDispatcher().register(
            Commands.literal("gameconsole-md").executes(c->status())
                .then(Commands.literal("core")
                    .then(Commands.literal("genesis").executes(c->choose(MdProfile.Core.GENESIS_PLUS_GX)))
                    .then(Commands.literal("blastem").executes(c->choose(MdProfile.Core.BLASTEM))))));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e)->selected=MdProfile.Core.GENESIS_PLUS_GX);
    }
    private static int choose(MdProfile.Core core){
        var player=Minecraft.getInstance().player;
        boolean powered=false;
        // Also cover the interval while the shared client is fetching cartridge content.
        if(player!=null){
            var provider=new MdClient.Provider();
            for(int i=0;i<player.getInventory().getContainerSize();i++)
                if(provider.locate(player,player.getInventory().getItem(i))!=null){powered=true;break;}
        }
        if(MdEngine.active()||powered){message("请先正常关机并等待保存完成，再切换 MD 核心。");return 0;}
        selected=core;return status();
    }
    private static int status(){
        message("MD 下次启动："+MdProfile.profile(selected).name()+"；本机本次连接生效，新旧核心存档独立，不自动转换。"
            +(selected==MdProfile.Core.GENESIS_PLUS_GX?" Genesis Plus GX 禁止商业用途。":" BlastEm：读取原 alpha.1/.2 兼容档。"));return 1;
    }
    private static void message(String text){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(text),false);}
}
