package cn.piq.fcarcade.network;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Read-only OP2 diagnostics. No custom query packets or emulator/saving authority. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class ServerTrafficDiagnostics {
    private static MinecraftServer server;
    private static final Map<UUID,Connection> watchers=new HashMap<>();
    private ServerTrafficDiagnostics() {}
    @SubscribeEvent public static void starting(ServerStartingEvent e){
        server=e.getServer();watchers.clear();ServerTrafficMeter.install(new ServerTrafficMeter.Session(System::nanoTime));
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){
        if(server==e.getServer()){ServerTrafficMeter.install(null);watchers.clear();server=null;}
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent e){
        e.getDispatcher().register(Commands.literal("gameconsole").requires(s->s.hasPermission(2))
            .then(Commands.literal("traffic").requires(s->s.hasPermission(2)).executes(c->show(c.getSource()))
                .then(Commands.literal("watch").executes(c->{
                    if(!authorized(c.getSource()))return 0;
                    var p=c.getSource().getPlayerOrException();
                    if(watchers.size()>=64&&!watchers.containsKey(p.getUUID())){c.getSource().sendFailure(Component.literal("监控订阅已达上限"));return 0;}
                    watchers.put(p.getUUID(),p.connection.getConnection());
                    c.getSource().sendSuccess(()->Component.literal("已开启每5秒汇总；/gameconsole traffic off 关闭。"),false);
                    return show(c.getSource());
                }))
                .then(Commands.literal("off").executes(c->{
                    if(!authorized(c.getSource()))return 0;
                    var p=c.getSource().getPlayerOrException();watchers.remove(p.getUUID());
                    c.getSource().sendSuccess(()->Component.literal("已关闭全服流量订阅；累计统计不清零。"),false);return 1;
                }))));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){
        if(server!=e.getServer())return;
        var meter=ServerTrafficMeter.current();if(meter==null)return;
        if(server.getTickCount()%20==0)meter.sample();
        // Drop authorization immediately on the server tick, not after the next broadcast.
        watchers.entrySet().removeIf(entry->{var p=server.getPlayerList().getPlayer(entry.getKey());
            return p==null||!p.hasPermissions(2)||p.hasDisconnected()||p.connection.getConnection()!=entry.getValue();});
        if(server.getTickCount()%100==0)for(var id:watchers.keySet())show(server.getPlayerList().getPlayer(id).createCommandSourceStack());
    }
    private static int show(CommandSourceStack source){
        var meter=ServerTrafficMeter.current();
        if(!authorized(source)||meter==null)return 0;
        source.sendSuccess(()->Component.literal("[方块电玩] 全服已埋点载荷（服务端视角；本次启动累计）\n"+line("合计",meter.total())),false);
        for(var group:ServerTrafficMeter.Group.values()){
            String name=switch(group){case FC_HOME->"FC/学习机及公共协议";case SFC_HOME->"SFC家用";case CABINET->"共享街机（SFC/原生/GBA等）";case WATCH->"旁观公共流";case NETPLAY->"Netplay（FC/SFC/街机）";};
            source.sendSuccess(()->Component.literal(line(name,meter.group(group))),false);
        }
        source.sendSuccess(()->Component.literal("压缩前载荷；非网卡/TCP总量。不含原版、其他MOD、旧SFC core9独立协议；本地内存连接不计。共享流不强行归属某一机种。"),false);
        return 1;
    }
    private static boolean authorized(CommandSourceStack source){
        return source.hasPermission(2)&&source.getServer()!=null&&source.getServer()==server&&source.getServer().isSameThread();
    }
    static String line(String name,ModTrafficCounter.Sample s){
        return String.format(Locale.ROOT,"%s ↑ %.2f KiB/s ↓ %.2f KiB/s；累计发送 %.2f MiB / 接收 %.2f MiB / 总计 %.2f MiB",
            name,s.uploadBytesPerSecond()/1024,s.downloadBytesPerSecond()/1024,s.uploadedBytes()/1048576.0,s.downloadedBytes()/1048576.0,s.totalBytes()/1048576.0);
    }
}
