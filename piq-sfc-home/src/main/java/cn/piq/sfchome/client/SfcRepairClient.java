package cn.piq.sfchome.client;

import cn.piq.sfchome.net.SfcRepairNetwork;
import cn.piq.sfchome.server.SfcRepairLedger;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

/** Game-thread transfer state. Core state import, digest and replay happen only on its existing worker. */
final class SfcRepairClient implements SfcRepairNetwork.Client {
    private static final SfcJoinClient.TransferGuard GUARD=new SfcJoinClient.TransferGuard();
    private static SfcRepairNetwork.Key transaction;private static Object connection;private static SfcPlayback owner;
    private static byte[] bytes;private static int at,nextReplay;private static String sha="";
    private static long began;private static boolean uploading,restored,completed,failed;
    @Override public boolean acceptsConnection(Object source){var c=Minecraft.getInstance().getConnection();return c!=null&&source==c.getConnection();}
    private static boolean current(SfcPlayback p,SfcRepairNetwork.Key k){return p!=null&&p.session.checksState()&&SfcHomeClient.isCurrent(p)&&p.matches(k.session(),k.epoch())&&p.session.controllerLease().equals(k.lease());}
    private static boolean matches(SfcRepairNetwork.Key k){return transaction!=null&&owner!=null&&current(owner,k)&&k.token().equals(transaction.token())&&connection==Minecraft.getInstance().getConnection()&&GUARD.matches(connection,owner,k.token(),transaction.frame());}
    static boolean suspended(SfcPlayback p){return p!=null&&(p.synchronizationPaused()||transaction!=null&&owner==p&&!uploading);}
    private static boolean begin(SfcRepairNetwork.Key k,boolean host){var p=SfcHomeClient.currentPlayback();Object c=Minecraft.getInstance().getConnection();
        if(!current(p,k)||p.session.executionHost()!=host||!GUARD.begin(c,p,k.token(),k.frame()))return false;
        transaction=k;owner=p;connection=c;bytes=null;at=0;nextReplay=k.frame();sha="";began=System.nanoTime();uploading=host;restored=completed=failed=false;return true;}
    @Override public void begin(SfcRepairNetwork.Begin p){if(!begin(p.key(),false))return;owner.beginRepair(p.key());SfcHomeClient.releaseRepairInput();}
    @Override public void request(SfcRepairNetwork.Request p){if(!begin(p.key(),true))return;var checkpoint=owner.checkpoint(p.key().frame());
        if(checkpoint==null){fail();return;}bytes=checkpoint.bytes();sha=checkpoint.sha();GUARD.dataReady();}
    @Override public void state(SfcRepairNetwork.State p){if(!matches(p.key())||uploading||restored||failed||!p.key().equals(transaction))return;
        if(bytes==null){if(p.offset()!=0){fail();return;}bytes=new byte[p.total()];sha=p.sha();}
        if(p.total()!=bytes.length||p.offset()!=at||!sha.equals(p.sha())){fail();return;}
        byte[] part=p.data();System.arraycopy(part,0,bytes,at,part.length);at+=part.length;
        if(at==bytes.length){byte[] state=bytes;bytes=null;restored=true;GUARD.dataReady();owner.restoreRepair(transaction,state,sha);}
    }
    static void restored(SfcPlayback playback,SfcRepairNetwork.Key key,String hash,boolean success){if(owner!=playback||!matches(key)||uploading||!GUARD.acknowledge())return;
        PacketDistributor.sendToServer(new SfcRepairNetwork.Restored(key,hash,success));if(!success)failed=true;}
    @Override public void replay(SfcRepairNetwork.Replay p){if(!matches(p.key())||uploading||!restored||failed||!GUARD.acknowledged())return;
        if(p.key().frame()!=nextReplay||!owner.replayRepair(p)){fail();return;}nextReplay+=p.p1().length;}
    @Override public void resume(SfcRepairNetwork.Resume p){if(!matches(p.key())||uploading||!restored||failed||p.key().frame()!=nextReplay){if(matches(p.key()))fail();return;}owner.resumeRepair(p.key());}
    static void done(SfcPlayback playback,SfcRepairNetwork.Key key,boolean success){if(owner!=playback||!matches(key)||uploading||completed)return;completed=success;failed=!success;PacketDistributor.sendToServer(new SfcRepairNetwork.Done(key,success));}
    @Override public void cancel(SfcRepairNetwork.Cancel p){if(!matches(p.key()))return;boolean healthy=uploading||completed;GUARD.finish(connection,owner,p.key().token());clearTransaction();
        if(healthy)SfcHomeClient.refreshInput();else SfcHomeClient.leave("本端重同步已取消，主机仍在运行");}
    private static void fail(){if(transaction==null||failed)return;failed=true;bytes=null;PacketDistributor.sendToServer(new SfcRepairNetwork.Done(transaction,false));}
    static void tick(){if(transaction==null)return;
        if(connection!=Minecraft.getInstance().getConnection()||!current(owner,transaction)){clear();return;}
        if(System.nanoTime()-began>30_000_000_000L){fail();if(!uploading)SfcHomeClient.leave("本端重同步超时，已退出控制；主机继续");else{GUARD.finish(connection,owner,transaction.token());clearTransaction();}return;}
        for(int budget=0;budget<2&&uploading&&!failed&&bytes!=null&&at<bytes.length;budget++){
            int end=Math.min(bytes.length,at+SfcRepairLedger.CHUNK);var payload=new SfcRepairNetwork.Upload(new SfcRepairNetwork.State(transaction,bytes.length,at,sha,Arrays.copyOfRange(bytes,at,end)));
            var listener=Minecraft.getInstance().getConnection();if(listener==null||!cn.piq.fcarcade.cabinet.CabinetMediaSender.sendPayload(listener.getConnection(),payload,end-at+2048,true))break;at=end;if(at==bytes.length)bytes=null;
        }
    }
    private static void clearTransaction(){transaction=null;connection=null;owner=null;bytes=null;at=nextReplay=0;sha="";began=0;uploading=restored=completed=failed=false;}
    static void clear(){clearTransaction();GUARD.clear();}
}
