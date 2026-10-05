// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Current-core information and explicit refusal of retired command/config identifiers. */
public final class MdCoreChoice {
    private MdCoreChoice(){}
    public static void register(){
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e)->e.getDispatcher().register(
            Commands.literal("gameconsole-md").executes(c->status())
                .then(Commands.literal("core").executes(c->status())
                    .then(Commands.argument("id",StringArgumentType.word())
                        .suggests((context,builder)->builder.suggest("genesis").buildFuture())
                        .executes(c->choose(StringArgumentType.getString(c,"id")))))));
    }
    private static int choose(String id){
        try{MdProfile.resolve(id);return status();}
        catch(IllegalArgumentException e){message(e.getMessage());return 0;}
    }
    private static int status(){
        message("MD 串流／私人模式固定使用 Genesis Plus GX；JNI Netplay 使用独立 PIQ Netplay 核心。"
            +"核心与运行方式不是同一设置；旧档不迁移。BlastEm 已退役。Genesis Plus GX 禁止商业用途。");return 1;
    }
    private static void message(String text){var p=Minecraft.getInstance().player;if(p!=null)p.displayClientMessage(Component.literal(text),false);}
}
