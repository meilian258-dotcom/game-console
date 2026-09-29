package cn.piq.fcarcade.access;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** OP2-only settings. Listing a library never grants OP or access to global ROM save settings. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class PlayerContentCommands {
    private PlayerContentCommands() {}
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root = Commands.literal("content").requires(s -> s.hasPermission(2)).executes(c -> show(c.getSource()));
        root.then(Commands.literal("rom").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> update(c.getSource(),
                p -> new PlayerContentPolicy(p.allPlayers(), BoolArgumentType.getBool(c, "enabled"), p.coverUploads(), p.allowedPlayers(), p.serverRomUse(), p.serverCoverUse())))));
        root.then(Commands.literal("cover").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> update(c.getSource(),
                p -> new PlayerContentPolicy(p.allPlayers(), p.romUploads(), BoolArgumentType.getBool(c, "enabled"), p.allowedPlayers(), p.serverRomUse(), p.serverCoverUse())))));
        root.then(Commands.literal("use-rom").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> update(c.getSource(),
                p -> new PlayerContentPolicy(p.allPlayers(), p.romUploads(), p.coverUploads(), p.allowedPlayers(), BoolArgumentType.getBool(c, "enabled"), p.serverCoverUse())))));
        root.then(Commands.literal("use-cover").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(c -> update(c.getSource(),
                p -> new PlayerContentPolicy(p.allPlayers(), p.romUploads(), p.coverUploads(), p.allowedPlayers(), p.serverRomUse(), BoolArgumentType.getBool(c, "enabled"))))));
        root.then(Commands.literal("access")
                .then(Commands.literal("all").executes(c -> update(c.getSource(), p -> new PlayerContentPolicy(true, p.romUploads(), p.coverUploads(), p.allowedPlayers(), p.serverRomUse(), p.serverCoverUse()))))
                .then(Commands.literal("listed").executes(c -> update(c.getSource(), p -> new PlayerContentPolicy(false, p.romUploads(), p.coverUploads(), p.allowedPlayers(), p.serverRomUse(), p.serverCoverUse())))));
        for (boolean allow : new boolean[]{true, false}) {
            root.then(Commands.literal(allow ? "allow" : "deny").then(Commands.argument("players", EntityArgument.players()).executes(c -> {
                Set<UUID> ids = new HashSet<>();
                for (var player : EntityArgument.getPlayers(c, "players")) ids.add(player.getUUID());
                return changePlayers(c.getSource(), ids, allow);
            })));
            root.then(Commands.literal(allow ? "allowuuid" : "denyuuid").then(Commands.argument("uuid", StringArgumentType.word()).executes(c -> {
                try { return changePlayers(c.getSource(), Set.of(PlayerContentPolicy.parsePlayer(StringArgumentType.getString(c, "uuid"))), allow); }
                catch (IllegalArgumentException invalid) { c.getSource().sendFailure(Component.literal(invalid.getMessage())); return 0; }
            })));
        }
        root.then(Commands.literal("list").executes(c -> {
            if (!c.getSource().hasPermission(2)) return 0;
            var ids = PlayerContentAccess.policy().allowedPlayers().stream().map(UUID::toString).sorted().toList();
            c.getSource().sendSuccess(() -> Component.literal("授权 UUID（"+ids.size()+"）：\n"+String.join("\n", ids)), false); return ids.size();
        }));
        event.getDispatcher().register(Commands.literal("gameconsole").then(root));
    }
    private static int changePlayers(CommandSourceStack source, Set<UUID> ids, boolean allow) {
        return update(source, p -> {
            Set<UUID> next = new HashSet<>(p.allowedPlayers());
            if (allow) next.addAll(ids); else next.removeAll(ids);
            return new PlayerContentPolicy(p.allPlayers(), p.romUploads(), p.coverUploads(), next, p.serverRomUse(), p.serverCoverUse());
        });
    }
    private static int update(CommandSourceStack source, UnaryOperator<PlayerContentPolicy> edit) {
        if (!source.hasPermission(2) || !source.getServer().isSameThread()) return 0;
        try {
            var expected = PlayerContentAccess.policy();
            if (!PlayerContentAccess.update(source, expected, edit.apply(expected))) {
                source.sendFailure(Component.literal("设置未保存：服务端配置未加载或写入失败，请检查服务器日志。")); return 0;
            }
        } catch (IllegalArgumentException invalid) { source.sendFailure(Component.literal(invalid.getMessage())); return 0; }
        source.sendSuccess(() -> Component.literal("资源权限已保存；卡带和街机共用。未提交上传会重新校验，已提交文件不删除；正常游玩不受影响。请刷新或重新打开工作台。"), true);
        return show(source);
    }
    private static int show(CommandSourceStack source) {
        if (!source.hasPermission(2)) return 0;
        var p = PlayerContentAccess.policy();
        source.sendSuccess(() -> Component.literal("[方块电玩] 卡带游戏库访问："+(p.allPlayers()?"所有玩家":"仅名单玩家")
                +"；名单 "+p.allowedPlayers().size()+" 人\n使用服务器ROM："+(p.serverRomUse()?"开":"关")
                +"；使用服务器封面："+(p.serverCoverUse()?"开":"关")+"\n玩家 ROM 上传："+(p.romUploads()?"开":"关")
                +"；玩家封面上传："+(p.coverUploads()?"开":"关")+"；OP2 始终可管理。\n"
                +"/gameconsole content rom|cover|use-rom|use-cover true|false\n/gameconsole content access all|listed\n"
                +"/gameconsole content allow|deny <在线玩家>（仅修改名单，all 模式仍允许所有人）\n"
                +"/gameconsole content allowuuid|denyuuid <UUID>；list 查看名单。\n授权玩家持卡右键卡带电脑查看服务器游戏库。"), false);
        return 1;
    }
}
