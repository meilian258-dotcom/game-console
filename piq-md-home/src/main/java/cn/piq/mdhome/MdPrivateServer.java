// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.home.*;
import cn.piq.fcarcade.home.content.*;
import cn.piq.fcarcade.home.flow.*;
import cn.piq.mdhome.save.MdPublicSaves;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Private local saves never become public save evidence; only lifecycle completion crosses this boundary. */
@EventBusSubscriber(modid=MdMod.ID)
public final class MdPrivateServer {
    private static final Map<MinecraftServer,Map<UUID,Session>> STATES=new IdentityHashMap<>();
    private static Map<UUID,Session> sessions(MinecraftServer s){return STATES.computeIfAbsent(s,k->new HashMap<>());}
    private static Session session(MdConsole c){return c.getLevel() instanceof net.minecraft.server.level.ServerLevel l?sessions(l.getServer()).get(c.hardwareId()):null;}
    public static boolean busy(MdConsole c){return session(c)!=null;}
    public static boolean pending(MdConsole c){var s=session(c);return s!=null&&!s.ready;}
    public static boolean start(ServerPlayer p,MdConsole c,HomeSystems.Connection link,ContentCardStore.Entry entry){
        var server=p.getServer();if(server==null||sessions(server).size()>=16||session(c)!=null)return false;
        var s=new Session(p,c,link,entry);if(!valid(s))return false;sessions(server).put(c.hardwareId(),s);
        var flow=HomeLaunchServer.start(p,MdPublicSaves.key(c),new HomeLaunchServer.Adapter<Boolean>(){
            public HomeLaunchServer.Definition definition(){String title=ContentCardData.title(s.snapshot);return new HomeLaunchServer.Definition("MD · 私人",title.isBlank()?entry.displayName():title,1,0,true,"私人单人；自动恢复原本机档，不上传、不迁移");}
            public boolean valid(){return session(c)==s&&!s.closing&&c.usable(p)&&MdPrivateServer.valid(s);}
            public void list(Consumer<List<HomeLaunchNetwork.Row>> success,Consumer<String> failure){failure.accept("私人模式不读取服务器槽位");}
            public void select(HomeLaunchNetwork.Choice choice,Consumer<Boolean> success,Consumer<String> failure){success.accept(Boolean.TRUE);}
            public void load(HomeLaunchServer.Launch<Boolean> launch,HomeLaunchServer.Handle handle){
                s.flow=handle;c.clearLoan(1);c.privatePreparing(s.generation,p.getUUID());
                s.content=ContentCards.play(p,MdMod.SYSTEM,c.getBlockPos(),entry,()->session(c)==s&&!s.closing&&MdPrivateServer.valid(s),ready->{
                    if(session(c)!=s||s.closing)return;
                    if(ready&&handle.ready()){s.ready=true;c.privatePower(s.generation,p.getUUID());MdPublicNetwork.send(p,new MdPublicNetwork.PrivateActivated(s.content));HomeInteractionSounds.play(p.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.POWER_ON);}
                    else stop(c,"私人开局失败或核心已停止，旧本机档保留");
                });
                if(s.content==null){handle.fail("MD 内容下载服务忙，原本机档保留");return;}
                MdPublicNetwork.send(p,new MdPublicNetwork.PrivateStart(s.content,true));
            }
            public void cancelled(String reason){stop(c,reason);}
        });
        if(flow==null){sessions(server).remove(c.hardwareId(),s);return false;}if(s.flow==null)s.flow=flow;return true;
    }
    private static boolean valid(Session s){return MdPublicServer.current(s.host)&&s.host.connection.getConnection()==s.connection
            &&s.host.isAlive()&&!s.host.isSpectator()&&s.host.serverLevel()==s.link.level()
            &&!s.console.isRemoved()&&HomeSystems.isCurrent(s.link)&&s.link.television().powered()
            &&HomeHardware.mayUse(s.host,s.console.getBlockPos())&&HomeHardware.mayUse(s.host,s.link.television().getBlockPos())
            &&MdPublicServer.cardUnchanged(s.card,s.snapshot,s.console.cartridge())
            &&Objects.equals(ContentCardData.read(s.card,MdMod.SYSTEM),s.entry);}
    public static void stop(MdConsole c,String reason){var s=session(c);if(s==null||s.closing)return;s.closing=true;s.wasReady=s.ready;s.ready=false;
        s.closeDeadline=Integer.toUnsignedLong(s.server.getTickCount())+1600;if(s.flow!=null&&!s.flow.beginStopping()&&s.flow.current())HomeLaunchServer.cancel(s.server,MdPublicSaves.key(c),reason);
        if(s.content!=null&&MdPublicServer.current(s.host)&&s.host.connection.getConnection()==s.connection){
            // The content runtime replies only after stopCartridgeAndSave's original owner future completes.
            ContentCards.stop(s.host,s.content);MdPublicNetwork.send(s.host,new MdPublicNetwork.PrivateStart(s.content,false));
        }else finish(s,!s.wasReady,reason);
    }
    public static void finished(ServerPlayer player,MdPublicNetwork.PrivateFinished reply){
        var s=sessions(player.getServer()).values().stream().filter(q->q.host==player&&q.connection==player.connection.getConnection()&&Objects.equals(q.content,reply.content())).findFirst().orElse(null);
        if(s==null)return;if(!s.closing)stop(s.console,"私人核心已结束");if(session(s.console)!=s)return;
        boolean success=reply.closed()&&(!s.wasReady||ContentCardData.saveMode(s.snapshot)==0||reply.saved());
        finish(s,success,success?(s.wasReady?ContentCardData.saveMode(s.snapshot)==0?"MD 私人游戏已关闭；本局不存档":"MD 私人游戏已关闭；客户端确认本机保存完成":"MD 私人开局已取消，旧本机档保留"):"MD 私人保存或关闭未确认；请检查本机提示，旧档保留");
    }
    private static void finish(Session s,boolean success,String reason){if(session(s.console)!=s)return;sessions(s.server).remove(s.console.hardwareId(),s);s.console.publicStopped();if(s.flow!=null)s.flow.finished(success,reason);}
    public static void reset(ServerPlayer player,MdConsole c){var s=session(c);if(s!=null&&s.host==player&&s.ready&&!s.closing&&valid(s)&&ContentCards.reset(player,s.content))HomeInteractionSounds.play(player.serverLevel(),c.getBlockPos(),HomeInteractionSounds.Action.RESET);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){var rows=STATES.get(event.getServer());if(rows==null)return;for(var s:List.copyOf(rows.values())){
        if(s.closing){if(Integer.toUnsignedLong(s.server.getTickCount())>=s.closeDeadline)finish(s,false,"MD 本机结束等待超时；未确认最后进度");}
        else if(!valid(s))stop(s.console,"私人主机、卡带、玩家或电视连接已失效");
    }}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){var rows=STATES.remove(event.getServer());if(rows!=null)for(var s:rows.values())s.console.publicStopped();}
    private static final class Session {
        final MinecraftServer server;final ServerPlayer host;final Connection connection;final MdConsole console;final HomeSystems.Connection link;final ContentCardStore.Entry entry;
        final ItemStack card,snapshot;final UUID generation=UUID.randomUUID();HomeLaunchServer.Handle flow;UUID content;boolean ready,wasReady,closing;long closeDeadline;
        Session(ServerPlayer p,MdConsole c,HomeSystems.Connection l,ContentCardStore.Entry e){server=p.getServer();host=p;connection=p.connection.getConnection();console=c;link=l;entry=e;card=c.cartridge();snapshot=card.copy();}
    }
    private MdPrivateServer(){}
}
