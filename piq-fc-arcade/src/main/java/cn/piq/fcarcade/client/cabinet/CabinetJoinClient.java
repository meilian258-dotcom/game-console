package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetJoinNetwork;
import cn.piq.fcarcade.client.ui.DeviceConfirmScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.UUID;
import java.util.function.Consumer;

/** FC-style private/allow/approve workflow, without replacing the ongoing core or other GUI. */
@EventBusSubscriber(modid="piq_fc_arcade",value=Dist.CLIENT)
public final class CabinetJoinClient implements CabinetJoinNetwork.ClientSink {
    private static final CabinetPromptGuard GUARD=new CabinetPromptGuard();
    private static ConsentScreen active;
    private static Object connection;
    @Override public boolean acceptsConnection(Connection source){var c=Minecraft.getInstance().getConnection();return c!=null&&c.getConnection()==source&&source.isConnected();}
    @Override public void offer(CabinetJoinNetwork.Offer p){
        if(CabinetClientBackends.directFcJoin(p.room())){
            show(p.room(),p.hostMember(),p.token(),"本局允许 2P 加入吗？","允许后可直接加入；不允许则无法加入。游戏中不会再弹出申请。",
                    "允许 2P","不允许 2P",yes->CabinetJoinNetwork.send(new CabinetJoinNetwork.Allow(p.room(),p.hostMember(),p.token(),yes)));
            return;
        }
        show(p.room(),p.hostMember(),p.token(),"开启多人模式？","允许其他玩家申请加入这局游戏吗？你可以先玩，批准申请后对方才会入座。",
                "允许申请","保持私有",yes->CabinetJoinNetwork.send(new CabinetJoinNetwork.Allow(p.room(),p.hostMember(),p.token(),yes)));
    }
    @Override public void approval(CabinetJoinNetwork.Approval p){
        show(p.room(),p.hostMember(),p.token(),"多人加入申请",p.applicantName()+" 想作为 P"+(p.port()+1)+" 加入当前游戏。接受后共享当前画面，不会重开本局。",
                "接受申请","拒绝",yes->CabinetJoinNetwork.send(new CabinetJoinNetwork.Decision(p.room(),p.hostMember(),p.token(),yes)));
    }
    private static void show(UUID room,UUID host,UUID token,String title,String body,String yes,String no,Consumer<Boolean> reply){
        var mc=Minecraft.getInstance();Object c=mc.getConnection();
        if(!CabinetClientBackends.ownsRoom(room,host,c)||!GUARD.claim(c,room,host,token,System.nanoTime()))return;
        connection=c;
        if(mc.screen!=null){GUARD.finish(c,room,host,token);reply.accept(false);cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow("请先关闭当前界面；本次多人邀请未接受");return;}
        active=new ConsentScreen(c,room,host,token,title,body,yes,no,reply);mc.setScreen(active);
    }
    @Override public void result(CabinetJoinNetwork.Result p){
        var screen=active;
        if(screen==null||!screen.room.equals(p.room())||!screen.token.equals(p.token())||Minecraft.getInstance().getConnection()!=screen.owner)return;
        screen.expire();cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(p.reason());
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post ignored){
        if(connection!=Minecraft.getInstance().getConnection()){if(active!=null)active.expire();GUARD.clear();connection=null;}
        if(active!=null&&!active.live())active.expire();
    }
    private static final class ConsentScreen extends DeviceConfirmScreen {
        final Object owner;final UUID room,host,token;
        final Consumer<Boolean> reply;
        ConsentScreen(Object c,UUID r,UUID h,UUID t,String title,String body,String yes,String no,Consumer<Boolean> reply){
            super(value->answer(c,r,h,t,value,reply),Component.literal(title),Component.literal(body),Component.literal(yes),Component.literal(no));
            owner=c;room=r;host=h;token=t;this.reply=reply;
        }
        boolean live(){return CabinetClientBackends.ownsRoom(room,host,owner)&&GUARD.live(owner,room,host,token,System.nanoTime());}
        void expire(){GUARD.finish(owner,room,host,token);if(active==this)active=null;if(Minecraft.getInstance().screen==this)Minecraft.getInstance().setScreen(null);}
        @Override public void removed(){
            // Replacing a GUI is cancellation, never implicit acceptance. Expire without network if stale.
            if(active==this){boolean cancel=live();GUARD.finish(owner,room,host,token);active=null;if(cancel)reply.accept(false);}
            super.removed();
        }
        private static void answer(Object c,UUID r,UUID h,UUID t,boolean yes,Consumer<Boolean> reply){
            var screen=active;
            boolean valid=screen!=null&&screen.owner==c&&screen.room.equals(r)&&screen.host.equals(h)&&screen.token.equals(t)
                    &&Minecraft.getInstance().screen==screen&&screen.live();
            if(screen!=null&&screen.owner==c&&screen.token.equals(t))screen.expire();
            if(valid)reply.accept(yes);
        }
    }
}
