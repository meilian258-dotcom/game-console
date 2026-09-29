package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcJoinNetwork;
import cn.piq.sfchome.server.SfcJoinGate;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Render-thread transfer coordinator. Core save/load runs only on the existing owning worker. */
final class SfcJoinClient implements SfcJoinNetwork.Client {
    private static SfcJoinNetwork.Capture transaction;
    private static Object connection;
    private static byte[] bytes;private static int at;private static String sha="";private static boolean uploading;
    private static long began;
    private static final TransferGuard GUARD=new TransferGuard();
    private static Object offeredSession;
    private static final Set<UUID> approvals=new HashSet<>();
    @Override public boolean acceptsConnection(Object source){var c=Minecraft.getInstance().getConnection();return SfcHomeClient.SessionOrder.sameConnection(source,c==null?null:c.getConnection());}
    private static boolean current(long id,int epoch){var p=SfcHomeClient.currentPlayback();return p!=null&&p.matches(id,epoch)&&SfcHomeClient.isCurrent(p);}
    private static boolean canAnswer(cn.piq.sfchome.net.SfcHomeNetwork.Session s,Object c){var mc=Minecraft.getInstance();return s!=null&&s.executionHost()&&SfcHomeClient.sessionCurrent(s,c)&&mc.player!=null&&mc.player.isAlive()&&!mc.player.isSpectator();}
    @Override public void offer(SfcJoinNetwork.Offer p){
        var mc=Minecraft.getInstance();Object c=mc.getConnection();var s=SfcHomeClient.currentSession();
        if(!canAnswer(s,c)||s.sessionId()!=p.session()||s.epoch()!=p.epoch()||offeredSession==s)return;
        offeredSession=s;
        if(mc.screen!=null){PacketDistributor.sendToServer(new SfcJoinNetwork.Allow(p.session(),p.epoch(),false));cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow("已有界面打开，本局保持私有；关机再开可重新选择");return;}
        mc.setScreen(new SfcJoinScreen(p.session(),p.epoch(),null,"允许手柄申请？","主机无需拿手柄也会运行。是否允许其他玩家申请空闲手柄？你批准后才同步当前进度。","允许申请","保持私有",()->canAnswer(s,c),yes->{if(canAnswer(s,c))PacketDistributor.sendToServer(new SfcJoinNetwork.Allow(p.session(),p.epoch(),yes));}));
    }
    @Override public void approval(SfcJoinNetwork.Approval p){
        var mc=Minecraft.getInstance();Object c=mc.getConnection();var s=SfcHomeClient.currentSession();
        if(!canAnswer(s,c)||s.sessionId()!=p.session()||s.epoch()!=p.epoch()||approvals.contains(p.token()))return;
        if(approvals.size()>=256){PacketDistributor.sendToServer(new SfcJoinNetwork.Decision(p.session(),p.epoch(),p.token(),false));return;}
        approvals.add(p.token());
        if(mc.screen!=null||!current(p.session(),p.epoch())){PacketDistributor.sendToServer(new SfcJoinNetwork.Decision(p.session(),p.epoch(),p.token(),false));cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow("加入申请暂未接受：请先关闭当前界面，再让申请者重试");return;}
        mc.setScreen(new SfcJoinScreen(p.session(),p.epoch(),p.token(),"手柄领取申请",p.applicant()+(s.playerHosted()?" 想加入你的游戏。对方只接收音画并发送手柄输入，不会启动第二个模拟器。":" 想从当前进度加入。准备完成后会短暂同步进度，不会重开游戏。"),"接受申请","拒绝",()->canAnswer(s,c),yes->{if(canAnswer(s,c))PacketDistributor.sendToServer(new SfcJoinNetwork.Decision(p.session(),p.epoch(),p.token(),yes));}));
    }
    @Override public void capture(SfcJoinNetwork.Capture p){
        var playback=SfcHomeClient.currentPlayback();if(!current(p.session(),p.epoch())||!playback.session.checksState()||!playback.session.executionHost())return;
        Object c=Minecraft.getInstance().getConnection();if(!GUARD.begin(c,playback,p.token(),p.frame()))return;
        transaction=p;connection=c;bytes=null;at=0;sha="";began=System.nanoTime();uploading=true;playback.capture(p);
    }
    static void captured(SfcPlayback playback,SfcJoinNetwork.Capture p,byte[] value,String hash){if(!accepts(playback,p)||!GUARD.dataReady())return;bytes=value;sha=hash;at=0;}
    private static boolean accepts(SfcPlayback playback,SfcJoinNetwork.Capture p){return transaction!=null&&transaction.equals(p)&&SfcHomeClient.isCurrent(playback)&&GUARD.matches(Minecraft.getInstance().getConnection(),playback,p.token(),p.frame());}
    static void applied(SfcPlayback playback,SfcJoinNetwork.Capture p,String hash,boolean success){
        if(!accepts(playback,p)||!GUARD.acknowledge())return;
        PacketDistributor.sendToServer(new SfcJoinNetwork.Applied(p.session(),p.epoch(),p.token(),p.frame(),hash,success));bytes=null;
    }
    @Override public void state(SfcJoinNetwork.State p){
        var playback=SfcHomeClient.currentPlayback();if(!current(p.session(),p.epoch())||!playback.session.checksState()||playback.session.executionHost())return;
        var request=new SfcJoinNetwork.Capture(p.session(),p.epoch(),p.token(),p.frame());
        if(transaction==null){if(p.offset()!=0||!GUARD.begin(Minecraft.getInstance().getConnection(),playback,p.token(),p.frame()))return;transaction=request;connection=Minecraft.getInstance().getConnection();began=System.nanoTime();uploading=false;bytes=new byte[p.total()];sha=p.sha();at=0;}
        if(!accepts(playback,request)||uploading||GUARD.completedData()||GUARD.acknowledged())return;
        if(bytes==null||p.total()!=bytes.length||p.offset()!=at||!sha.equals(p.sha())){applied(playback,request,"",false);return;}
        byte[] part=p.data();System.arraycopy(part,0,bytes,at,part.length);at+=part.length;
        if(at==bytes.length){byte[] complete=bytes;bytes=null;GUARD.dataReady();if(!SfcJoinGate.sha(complete).equals(sha)){applied(playback,request,"",false);return;}playback.restore(request,complete,sha);}
    }
    @Override public void result(SfcJoinNetwork.Result p){
        var mc=Minecraft.getInstance();
        if(current(p.session(),p.epoch())){
            var playback=SfcHomeClient.currentPlayback();
            if(GUARD.finish(mc.getConnection(),playback,p.token())){playback.cancelJoin(p.token());clearTransaction();SfcHomeClient.refreshInput();}
            if(mc.screen instanceof SfcJoinScreen screen&&screen.session==p.session()&&screen.epoch==p.epoch()&&p.token().equals(screen.token))screen.expire();
        }
        cn.piq.fcarcade.client.ui.DeviceNoticesClient.workflow(p.message());
    }
    static void tick(){
        if(transaction==null)return;var p=transaction;
        if(connection==null||Minecraft.getInstance().getConnection()!=connection||!current(p.session(),p.epoch())){clear();return;}
        if(System.nanoTime()-began>30_000_000_000L){var playback=SfcHomeClient.currentPlayback();applied(playback,p,"",false);playback.cancelJoin(p.token());GUARD.finish(connection,playback,p.token());clearTransaction();return;}
        for(int budget=0;budget<2&&uploading&&bytes!=null&&at<bytes.length;budget++){int end=Math.min(bytes.length,at+SfcJoinGate.CHUNK);var payload=new SfcJoinNetwork.Upload(new SfcJoinNetwork.State(p.session(),p.epoch(),p.token(),p.frame(),bytes.length,at,sha,Arrays.copyOfRange(bytes,at,end)));
            var listener=Minecraft.getInstance().getConnection();if(listener==null||!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(listener.getConnection(),payload,end-at+2048,true))break;at=end;if(at==bytes.length)bytes=null;}
    }
    private static void clearTransaction(){transaction=null;connection=null;bytes=null;at=0;sha="";uploading=false;began=0;}
    static void clear(){clearTransaction();GUARD.clear();offeredSession=null;approvals.clear();var mc=Minecraft.getInstance();if(mc.screen instanceof SfcJoinScreen screen)screen.expire();}
    static void stopped(long id,int epoch){var mc=Minecraft.getInstance();if(mc.screen instanceof SfcJoinScreen screen&&screen.session==id&&screen.epoch==epoch)screen.expire();}
    /** Bounded, fail-closed token history scoped to the exact playback and connection. */
    static final class TransferGuard {
        private Object connection,owner;private UUID active;private int frame;private boolean dataReady,acknowledged,exhausted;
        private final Set<UUID> retired=new HashSet<>();
        private boolean scope(Object c,Object o){return c!=null&&o!=null&&c==connection&&o==owner;}
        private void bind(Object c,Object o){if(!scope(c,o)){clear();connection=c;owner=o;}}
        boolean begin(Object c,Object o,UUID token,int frame){
            if(c==null||o==null||token==null||frame<0)return false;bind(c,o);
            if(exhausted||active!=null||retired.contains(token))return false;
            active=token;this.frame=frame;dataReady=false;acknowledged=false;return true;
        }
        boolean matches(Object c,Object o,UUID token,int frame){return scope(c,o)&&active!=null&&active.equals(token)&&this.frame==frame;}
        boolean dataReady(){if(active==null||dataReady||acknowledged)return false;dataReady=true;return true;}
        boolean completedData(){return dataReady;}
        boolean acknowledge(){if(active==null||acknowledged)return false;acknowledged=true;return true;}
        boolean acknowledged(){return acknowledged;}
        boolean finish(Object c,Object o,UUID token){
            if(c==null||o==null||token==null)return false;
            if(connection==null)bind(c,o);else if(!scope(c,o))return false;
            boolean matched=token.equals(active);
            if(matched){active=null;dataReady=false;acknowledged=false;}
            if(retired.size()<256)retired.add(token);else exhausted=true;
            return matched;
        }
        void clear(){connection=owner=null;active=null;frame=0;dataReady=false;acknowledged=false;exhausted=false;retired.clear();}
    }
}
