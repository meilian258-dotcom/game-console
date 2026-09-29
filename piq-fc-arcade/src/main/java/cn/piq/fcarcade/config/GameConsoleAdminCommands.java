package cn.piq.fcarcade.config;

import cn.piq.fcarcade.cabinet.CabinetHostingConfig;
import cn.piq.fcarcade.cabinet.CabinetSyncSettings;
import cn.piq.fcarcade.home.HomeSyncSettings;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Discoverable OP controls. Chat links only suggest commands; Enter is an explicit confirmation. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class GameConsoleAdminCommands {
    private static final String[] MODES={"auto","media","local","server"};
    private static final String[] LABELS={"机型自动默认","玩家音画串流","本地输入同步","服务端托管"};
    private GameConsoleAdminCommands() {}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var settings=Commands.literal("settings").requires(s->s.hasPermission(2)).executes(c->show(c.getSource()));
        var global=Commands.literal("global").executes(c->show(c.getSource()));
        var defaultSync=Commands.literal("sync");
        var target=Commands.literal("target").executes(c->target(c.getSource(),-1,null));
        var targetSync=Commands.literal("sync");
        for(int i=0;i<MODES.length;i++) {
            int mode=i-1;
            defaultSync.then(Commands.literal(MODES[i]).executes(c->update(c.getSource(),mode,null)));
            if(mode>=0)targetSync.then(Commands.literal(MODES[i]).executes(c->target(c.getSource(),mode,null)));
        }
        global.then(defaultSync).then(Commands.literal("range").then(Commands.argument("blocks",IntegerArgumentType.integer(GameConsoleAdminPolicy.MIN_RANGE,GameConsoleAdminPolicy.MAX_RANGE))
                .executes(c->update(c.getSource(),null,IntegerArgumentType.getInteger(c,"blocks")))));
        target.then(targetSync).then(Commands.literal("range").then(Commands.argument("blocks",IntegerArgumentType.integer(GameConsoleAdminPolicy.MIN_RANGE,GameConsoleAdminPolicy.MAX_RANGE))
                .executes(c->target(c.getSource(),-1,IntegerArgumentType.getInteger(c,"blocks")))));
        var arcade=Commands.literal("arcade").requires(s->s.hasPermission(2));
        for(String name:new String[]{"service","pgm"})arcade.then(Commands.literal(name)
                .executes(c->pgm(c.getSource(),false))
                .then(Commands.literal("confirm").executes(c->pgm(c.getSource(),true))));
        event.getDispatcher().register(Commands.literal("gameconsole").then(settings.then(global).then(target)).then(arcade));
    }
    private static int pgm(CommandSourceStack source,boolean confirm) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if(!authorized(source))return 0;
        if(!confirm){source.sendSuccess(()->Component.literal("[街机通用维护 · FBNeo Netplay]\n仅 OP 主柜 1P 可请求，影响本局所有玩家；请在标题/演示画面操作并让其他人松键。\n关闭聊天后发送 Test/Diagnostic 组合键，按实际菜单提示调整，用菜单 EXIT 返回。没有 Test 菜单的游戏不会响应；旧 MAME 串流暂不支持。\n这不是通用 DIP 编辑器，也不新增 NVRAM 保存；重开后设置可能需要重设。")
                .append(choice("确认发送维护按键","/gameconsole arcade service confirm")),false);return 1;}
        String error=cn.piq.fcarcade.cabinet.CabinetRooms.pgmService(source.getPlayerOrException());
        if(error!=null){source.sendFailure(Component.literal(error));return 0;}
        source.sendSuccess(()->Component.literal("已授权本次维护组合键；关闭聊天栏、保持游戏窗口焦点并等待约 4 秒。请以实际菜单确认是否支持；失焦/打开界面会取消按键。"),false);return 1;
    }
    private static boolean authorized(CommandSourceStack source) { return source.hasPermission(2)&&source.getServer().isSameThread(); }
    private static int update(CommandSourceStack source,Integer mode,Integer range) {
        if(!authorized(source))return 0;
        if(mode!=null&&((mode==0&&!CabinetHostingConfig.playerAllowed())||(mode==1&&!CabinetHostingConfig.localAllowed())||(mode==2&&!CabinetHostingConfig.enabled()))) {
            source.sendFailure(Component.literal("全局默认未修改：该执行方式已被 piq-sync-server.toml 禁用。此命令不会替您开启运行环境或执行权限。"));return 0;
        }
        var server=source.getServer();
        GameConsoleAdminSettings.setDefaults(server,mode==null?GameConsoleAdminSettings.defaultMode(server):mode,range==null?GameConsoleAdminSettings.defaultRange(server):range);
        source.sendSuccess(()->Component.literal("已保存新机器默认值；仅之后新放置的机器采用。现有机器、会话和存档不变；不支持该方式的机型保留自己的默认。"),true);
        return show(source);
    }
    private static int target(CommandSourceStack source,int mode,Integer range) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if(!authorized(source))return 0;
        var player=source.getPlayerOrException();
        var hit=cn.piq.fcarcade.home.DeviceDebugService.loadedTarget(player);
        if(hit!=null) {
            if(HomeSyncSettings.adminCommand(player,hit,mode,range)||CabinetSyncSettings.adminCommand(player,hit,mode,range))return 1;
        }
        source.sendFailure(Component.literal("请在交互距离内瞄准游戏机机身再执行；不选电视、手柄或未加载区块。单机设置需 OP2、空闲且有交互权限。"));return 0;
    }
    private static int show(CommandSourceStack source) {
        if(!authorized(source))return 0;
        int mode=GameConsoleAdminSettings.defaultMode(source.getServer()),range=GameConsoleAdminSettings.defaultRange(source.getServer());
        source.sendSuccess(()->Component.literal("[方块电玩 · OP 设置]\n新机器默认："+LABELS[mode+1]+"；旁观接收范围 "+range+" 格。\n只影响新放置机器；已有机器保留。旧 FC 街机只支持原本地同步；附属依自身能力采用默认。\n范围是旁观音画/本地重放的接收播放范围，不是 Minecraft 区块渲染距离；不会强制加载区块。\n点下面选项预填命令，回车确认。单机命令以执行时瞄准的游戏机为准。"),false);
        MutableComponent modes=Component.literal("新机器同步：");
        for(int i=0;i<MODES.length;i++)modes.append(choice(LABELS[i],"/gameconsole settings global sync "+MODES[i]));
        source.sendSuccess(()->modes,false);
        source.sendSuccess(()->Component.literal("新机器旁观范围：").append(choice("8 格","/gameconsole settings global range 8")).append(choice("16 格","/gameconsole settings global range 16"))
                .append(choice("32 格","/gameconsole settings global range 32")).append(choice("64 格","/gameconsole settings global range 64")),false);
        source.sendSuccess(()->Component.literal("单机：").append(choice("查询/家用机GUI","/gameconsole settings target"))
                .append(choice("本地同步","/gameconsole settings target sync local")).append(choice("玩家串流","/gameconsole settings target sync media"))
                .append(choice("服务端托管","/gameconsole settings target sync server")).append(choice("旁观范围","/gameconsole settings target range 16"))
                .append("\n单机模式/范围仅空闲可改；连接的两台街机按整组处理。更多范围可输入 4～64。"),false);
        source.sendSuccess(()->Component.literal("街机运行中：").append(choice("PGM 维护菜单（说明）","/gameconsole arcade pgm")),false);
        return 1;
    }
    private static Component choice(String label,String command) {
        return Component.literal(" ["+label+"] ").withStyle(style->style.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal(command+"\n仅预填，不立即执行"))));
    }
}
