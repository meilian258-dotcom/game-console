// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.home.flow;

import cn.piq.retro.flow.DeviceSessionFlow;
import cn.piq.retro.flow.DeviceSessionFlow.Stage;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static cn.piq.fcarcade.home.flow.HomeLaunchNetwork.*;

/** Reusable, server-authoritative home launch orchestration. Storage/core ownership stays in adapters. */
@EventBusSubscriber(modid="piq_fc_arcade")
public final class HomeLaunchServer {
    public record Key(ResourceLocation system,ResourceLocation dimension,BlockPos pos,UUID hardware){
        public Key{Objects.requireNonNull(system);Objects.requireNonNull(dimension);pos=Objects.requireNonNull(pos).immutable();Objects.requireNonNull(hardware);}
    }
    public record Definition(String label,String title,int maxPlayers,int saveMode,boolean privatePlay,String saveHint){
        public Definition{text(label,64);text(title,128);text(saveHint,240);if(maxPlayers<1||maxPlayers>2||saveMode<0||saveMode>2)throw new IllegalArgumentException("Launch definition");}
    }
    public record Launch<P>(P save,Choice choice,boolean allowSecondPort){}
    public interface Adapter<P> {
        Definition definition();
        /** Original physical/content snapshot, distance, permission and connection; only called before READY. */
        boolean valid();
        void list(Consumer<List<Row>> success,Consumer<String> failure);
        /** Null choice is the explicit no-save branch. Must never mutate old progress while selecting. */
        void select(Choice choice,Consumer<P> success,Consumer<String> failure);
        /** Called exactly once, only after save validation and the applicable independent join confirmation. */
        void load(Launch<P> launch,Handle handle);
        /** Preparation cancelled/failed, or bounded runtime close expired. Must be idempotent. */
        void cancelled(String reason);
    }
    private static final Map<MinecraftServer,Map<Key,Pending<?>>> STATES=new IdentityHashMap<>();
    private static final long PREPARE_TICKS=1200,LOAD_TICKS=1200,CLOSE_TICKS=1600;
    private static long tick(MinecraftServer server){return Integer.toUnsignedLong(server.getTickCount());}
    private static boolean online(Pending<?> p){return p.player.getServer()==p.server&&!p.player.hasDisconnected()&&p.connection.isConnected()&&p.player.connection.getConnection()==p.connection&&p.server.getPlayerList().getPlayer(p.player.getUUID())==p.player;}
    private static Map<Key,Pending<?>> entries(MinecraftServer s){return STATES.computeIfAbsent(s,k->new HashMap<>());}
    private static boolean current(Pending<?> p){var rows=STATES.get(p.server);return rows!=null&&rows.get(p.key)==p&&!p.flow.terminal();}
    private static boolean valid(Pending<?> p){try{return online(p)&&p.adapter.valid();}catch(RuntimeException invalid){return false;}}
    public static boolean busy(MinecraftServer server,Key key){var rows=STATES.get(server);return rows!=null&&rows.containsKey(key);}
    public static boolean pending(MinecraftServer server,Key key){var rows=STATES.get(server);var p=rows==null?null:rows.get(key);return p!=null&&p.flow.stage()!=Stage.READY&&p.flow.stage()!=Stage.STOPPING;}
    public static Handle start(ServerPlayer player,Key key,Adapter<?> adapter){return begin(player,key,adapter);}
    private static <P> Handle begin(ServerPlayer player,Key key,Adapter<P> adapter){
        var server=player.getServer();if(server==null||!server.isSameThread())throw new IllegalStateException("Launch authority thread required");
        var rows=entries(server);if(rows.size()>=32||rows.containsKey(key)||rows.values().stream().anyMatch(p->p.connection==player.connection.getConnection()))return null;
        final Pending<P> p;try{p=new Pending<P>(player,key,adapter);}catch(RuntimeException invalid){player.displayClientMessage(Component.literal("开局描述无效，未启动游戏："+cut(message(invalid),120)),false);return null;}
        if(!valid(p))return null;rows.put(key,p);view(p,"正在检查开局条件…");
        if(p.definition.saveMode()==0){p.flow.prepared();select(p,null);}
        else {long rev=p.flow.revision();try{adapter.list(result->onAuthority(p,rev,()->{
                var copy=List.copyOf(result);if(copy.isEmpty()||copy.size()>3)throw new IllegalArgumentException("Home save list must contain 1–3 slots");
                new View(p.token,p.flow.revision(),key.system(),p.definition.label(),p.definition.title(),p.definition.saveMode(),p.definition.maxPlayers(),p.flow.stage(),copy,"");
                p.rows=copy;p.flow.prepared();view(p,p.definition.saveHint());
            }),error->onAuthority(p,rev,()->fail(p,"读取存档失败，原档保留："+error)));}
            catch(RuntimeException error){fail(p,"开局准备失败："+message(error));}}
        return p.handle;
    }
    private static void onAuthority(Pending<?> p,long revision,Runnable work){p.server.execute(()->{
        if(!current(p)||!p.flow.accepts(revision))return;if(tick(p.server)>=p.deadline||!valid(p)){cancel(p,"设备、卡带、权限、连接或等待期限已变化");return;}
        try{work.run();}catch(RuntimeException error){fail(p,"开局失败，原进度保留："+message(error));}
    });}
    static void action(ServerPlayer player,Action action){var server=player.getServer();var entries=STATES.get(server);if(entries==null)return;
        var p=entries.values().stream().filter(q->q.token.equals(action.token())&&q.player==player&&q.connection==player.connection.getConnection()).findFirst().orElse(null);
        if(p==null||p.flow.stage()==Stage.READY||p.flow.stage()==Stage.STOPPING)return;
        // Esc may race a validation reply; an older revision may cancel this same token, never a new token.
        if(action.operation()==CANCEL&&action.revision()<=p.flow.revision()){cancel(p,"开局已取消，原进度保留");return;}
        if(!p.flow.accepts(action.revision()))return;
        if(action.operation()==CANCEL){cancel(p,"开局已取消，原进度保留");return;}
        if(tick(p.server)>=p.deadline||!valid(p)){cancel(p,"开局授权或等待期限已失效");return;}
        try{act(p,action);}catch(RuntimeException invalid){view(p,"选择无效："+message(invalid));}
    }
    private static <P> void act(Pending<P> p,Action action){
        if(action.operation()==SELECT&&p.flow.stage()==Stage.SAVE_SELECTION){
            var choice=action.choice();var row=p.rows.stream().filter(r->r.slot()==choice.slot()).findFirst().orElseThrow();
            if(!row.version().equals(choice.version())||choice.resume()&&(!row.occupied()||!row.compatible())||choice.name().isBlank())throw new IllegalArgumentException("存档或版本已变化");
            p.flow.select(choice.savePlayers());p.choice=choice;view(p,"正在校验所选存档…");select(p,choice);
        }else if((action.operation()==ALLOW||action.operation()==DENY)&&p.flow.stage()==Stage.JOIN_CONFIRM){p.flow.join(action.operation()==ALLOW);load(p);}
        else if(action.operation()==BACK&&p.flow.stage()==Stage.JOIN_CONFIRM&&p.definition.saveMode()!=0){p.flow.back();p.plan=null;p.choice=null;view(p,p.definition.saveHint());}
    }
    private static <P> void select(Pending<P> p,Choice choice){long rev=p.flow.revision();try{
        p.adapter.select(choice,plan->onAuthority(p,rev,()->{p.plan=Objects.requireNonNull(plan);p.flow.selected();
            if(p.flow.stage()==Stage.JOIN_CONFIRM)view(p,"是否允许第二名玩家加入？本局许可不会更改存档人数标签。");else load(p);
        }),error->onAuthority(p,rev,()->{if(p.definition.saveMode()==0)fail(p,"开局检查失败："+error);else{p.flow.invalidSelection();view(p,"存档检查失败，原档未改动："+error);}}));
    }catch(RuntimeException error){fail(p,"开局检查失败："+message(error));}}
    private static <P> void load(Pending<P> p){if(!current(p)||!valid(p)){cancel(p,"开局条件已变化");return;}
        p.deadline=tick(p.server)+LOAD_TICKS;view(p,"正在加载游戏与恢复进度，尚未授予操作权限…");
        try{p.adapter.load(new Launch<>(p.plan,p.choice,p.flow.allowSecondPort()),p.handle);}catch(RuntimeException error){fail(p,"加载失败，原进度保留："+message(error));}
    }
    private static void view(Pending<?> p,String message){if(online(p))HomeLaunchNetwork.send(p.player,new View(p.token,p.flow.revision(),p.key.system(),p.definition.label(),p.definition.title(),p.definition.saveMode(),p.definition.maxPlayers(),p.flow.stage(),p.rows,cut(message,240)));}
    private static void remove(Pending<?> p){var rows=STATES.get(p.server);if(rows!=null){rows.remove(p.key,p);if(rows.isEmpty())STATES.remove(p.server);}}
    private static void cancel(Pending<?> p,String reason){if(!current(p))return;p.flow.cancel();remove(p);view(p,reason);p.adapter.cancelled(reason);}
    private static void fail(Pending<?> p,String reason){if(!current(p))return;p.flow.fail();remove(p);view(p,reason);p.adapter.cancelled(reason);}
    public static void cancel(MinecraftServer server,Key key,String reason){var rows=STATES.get(server);var p=rows==null?null:rows.get(key);if(p!=null&&p.flow.stage()!=Stage.READY&&p.flow.stage()!=Stage.STOPPING)cancel(p,reason);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post e){var rows=STATES.get(e.getServer());if(rows==null)return;
        for(var p:List.copyOf(rows.values())){if(p.flow.stage()==Stage.READY)continue;
            if(p.flow.stage()==Stage.STOPPING){if(tick(p.server)>=p.deadline)fail(p,"结束等待超时；最近确认存档保留，最后进度未确认");}
            else if(tick(p.server)>=p.deadline||!valid(p))cancel(p,"开局超时或授权已失效，原进度保留");
        }
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent e){var rows=STATES.remove(e.getServer());if(rows!=null)for(var p:rows.values()){p.flow.fail();p.adapter.cancelled("服务器已停止");}}
    public static final class Handle {
        private final Pending<?> p;private Handle(Pending<?> p){this.p=p;}
        private void authority(){if(!p.server.isSameThread())throw new IllegalStateException("Launch authority thread required");}
        public UUID token(){return p.token;}public Stage stage(){return p.flow.stage();}public boolean current(){return HomeLaunchServer.current(p);}
        public boolean ready(){authority();if(!current()||p.flow.stage()!=Stage.LOADING||tick(p.server)>=p.deadline||!valid(p))return false;p.flow.ready();view(p,"");return true;}
        public void fail(String reason){authority();HomeLaunchServer.fail(p,reason);}
        public boolean beginStopping(){authority();if(!current()||p.flow.stage()!=Stage.READY&&p.flow.stage()!=Stage.LOADING)return false;p.flow.stopping();p.deadline=tick(p.server)+CLOSE_TICKS;return true;}
        public void finished(boolean success,String message){authority();if(!current()||p.flow.stage()!=Stage.STOPPING)return;p.flow.finished(success);view(p,message);remove(p);}
    }
    private static final class Pending<P>{
        final MinecraftServer server;final ServerPlayer player;final Connection connection;final Key key;final Adapter<P> adapter;final Definition definition;
        final UUID token=UUID.randomUUID();final DeviceSessionFlow flow;final Handle handle;List<Row> rows=List.of();Choice choice;P plan;long deadline;
        Pending(ServerPlayer player,Key key,Adapter<P> adapter){this.player=player;server=player.getServer();connection=player.connection.getConnection();this.key=key;this.adapter=Objects.requireNonNull(adapter);definition=adapter.definition();flow=new DeviceSessionFlow(definition.maxPlayers(),definition.saveMode()!=0,definition.privatePlay());handle=new Handle(this);deadline=tick(server)+PREPARE_TICKS;}
    }
    private static String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static String cut(String s,int max){if(s==null)return "操作失败";s=s.replaceAll("[\\p{Cntrl}]"," ");return s.length()>max?s.substring(0,max):s;}
    private HomeLaunchServer(){}
}
