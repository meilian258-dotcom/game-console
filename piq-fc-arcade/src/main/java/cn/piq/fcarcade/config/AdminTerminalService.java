package cn.piq.fcarcade.config;

import cn.piq.fcarcade.access.PlayerContentAccess;
import cn.piq.fcarcade.cabinet.CabinetHostingConfig;
import cn.piq.fcarcade.registry.ModItems;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;

/** All mutations run under current server-side OP and held-item authority, never creative mode. */
public final class AdminTerminalService {
    private static final Map<ServerPlayer,Long> LAST=Collections.synchronizedMap(new WeakHashMap<>());
    private AdminTerminalService() {}
    private static boolean current(ServerPlayer p){
        var server=p.getServer();return server!=null&&server.isSameThread()&&!p.hasDisconnected()&&p.connection.getConnection().isConnected()
            &&server.getPlayerList().getPlayer(p.getUUID())==p;
    }
    private static boolean authorized(ServerPlayer p,InteractionHand hand){
        return current(p)&&p.hasPermissions(2)&&p.isAlive()&&!p.isSpectator()&&p.getItemInHand(hand).is(ModItems.ADMIN_TERMINAL.get());
    }
    static int mask(PlayerContentAccess.Options o){
        return(o.allPlayers()?1:0)|(o.serverRomUse()?2:0)|(o.serverCoverUse()?4:0)|(o.romUploads()?8:0)|(o.coverUploads()?16:0);
    }
    static PlayerContentAccess.Options options(int mask){return new PlayerContentAccess.Options((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0);}
    private static int supported(){return(CabinetHostingConfig.playerAllowed()?1:0)|(CabinetHostingConfig.localAllowed()?2:0)|(CabinetHostingConfig.enabled()?4:0);}
    static void handle(ServerPlayer player,AdminTerminalNetwork.Request request){
        if(!current(player))return;
        long now=System.nanoTime();Long last=LAST.get(player);
        if(last!=null&&now-last>=0&&now-last<200_000_000L)return;
        LAST.put(player,now); // bound even unauthorized reads, before any persistence access
        var hand=InteractionHand.values()[request.hand()];
        if(!authorized(player,hand)){
            PacketDistributor.sendToPlayer(player,new AdminTerminalNetwork.State(request.nonce(),false,0,-1,16,0,"仅 OP 可用，请手持管理终端。"));return;
        }
        var server=player.getServer();var before=PlayerContentAccess.options();
        int mode=GameConsoleAdminSettings.defaultMode(server),range=GameConsoleAdminSettings.defaultRange(server);
        String message="";
        if(request.action()!=AdminTerminalPolicy.READ){
            if(!AdminTerminalPolicy.same(request.expectedOptions(),request.expectedMode(),request.expectedRange(),mask(before),mode,range)){
                message="设置已被修改，已刷新；请重新选择。";
            }else if(!authorized(player,hand))return;
            else switch(request.action()){
                case AdminTerminalPolicy.ACCESS -> message=PlayerContentAccess.updateOptions(player,before,options(request.value()))?"玩家权限已保存。":"保存失败或权限已变化，请刷新。";
                case AdminTerminalPolicy.MODE -> {
                    if(request.value()>=0&&(supported()&(1<<request.value()))==0)message="服务器未开放此运行方式。";
                    else {GameConsoleAdminSettings.setDefaults(server,request.value(),range);message="已保存，仅新放置机器采用。";}
                }
                case AdminTerminalPolicy.RANGE -> {GameConsoleAdminSettings.setDefaults(server,mode,request.value());message="已保存，仅新家庭机采用；街机另用全服规则。";}
                case AdminTerminalPolicy.RETENTION -> message=cn.piq.fcarcade.server.ServerArcadeSessions.setPersonalRetentionDays(player,request.expectedRetention(),request.value())
                        ?request.value()==0?"个人存档自动清理已关闭。":"已保存；只清理长期未游玩玩家的个人存档。":"设置已变化，请刷新重试。";
                case AdminTerminalPolicy.TARGET -> {
                    // Existing clickable command menu preserves its own explicit Enter confirmation.
                    // Do not issue unsolicited screen-open packets behind the still-open terminal.
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),"gameconsole settings");
                    message="设置指令已发到聊天栏；关闭终端后点击并回车。";
                }
                case AdminTerminalPolicy.TRAFFIC -> {
                    String command=switch(request.value()){case 1->"gameconsole traffic watch";case 2->"gameconsole traffic off";default->"gameconsole traffic";};
                    server.getCommands().performPrefixedCommand(player.createCommandSourceStack(),command);
                    message=request.value()==2?"已关闭聊天流量监控。":"流量统计已发送到聊天栏。";
                }
                default -> throw new IllegalArgumentException("Terminal action");
            }
        }
        if(!authorized(player,hand))return;
        PacketDistributor.sendToPlayer(player,new AdminTerminalNetwork.State(request.nonce(),true,mask(PlayerContentAccess.options()),
            GameConsoleAdminSettings.defaultMode(server),GameConsoleAdminSettings.defaultRange(server),supported(),message,cn.piq.fcarcade.server.ServerArcadeSessions.personalRetentionDays(server)));
    }
}
