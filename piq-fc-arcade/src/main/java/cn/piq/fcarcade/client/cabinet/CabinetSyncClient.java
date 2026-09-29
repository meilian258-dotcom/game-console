package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.*;
import java.util.*;
import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.PacketDistributor;

/** Client-thread network mailbox. It never reads/runs a core; only its dedicated worker does. */
final class CabinetSyncClient implements AutoCloseable {
    private final CabinetRoomNetwork.Assignment room;private final Connection connection;private final CabinetSyncWorker worker;
    private final Set<UUID> seen=new HashSet<>();
    private boolean closed,active;private CabinetSyncNetwork.StatePart incomingInfo;private CabinetSyncState incoming;
    private UUID awaitingAck;private final CabinetSyncUploads outgoing=new CabinetSyncUploads();private long tick;private long nextResync;private boolean resyncPending;
    CabinetSyncClient(CabinetRoomNetwork.Assignment room,Connection connection,CabinetSyncWorker worker){this.room=room;this.connection=connection;this.worker=worker;}
    boolean active(){return !closed&&active;}
    private boolean scope(UUID id,UUID member,int epoch){return !closed&&connection.isConnected()&&id.equals(room.room())&&member.equals(room.member())&&epoch==1;}
    void frames(CabinetSyncNetwork.Frames p){if(!scope(p.room(),p.member(),p.epoch()))return;if(!worker.frames(p.steps()))resync();}
    boolean restore(CabinetSyncNetwork.Restore packet){
        var p=packet.part();if(room.port()==0||!scope(p.room(),p.member(),p.epoch()))return false;
        if(p.begin()){
            if(seen.contains(p.token())||incomingInfo!=null)return false;
            if(seen.size()>=32){throw new IllegalStateException("Too many repair transactions; reconnect to start a fresh room");}
            seen.add(p.token());active=false;resyncPending=false;awaitingAck=p.token();incomingInfo=p;incoming=new CabinetSyncState(p.token(),p.total(),p.hash());worker.beginRestore(p.frame());return true;
        }
        var info=incomingInfo;
        if(info==null||incoming==null||!info.token().equals(p.token())||info.frame()!=p.frame()||info.goal()!=p.goal()||info.total()!=p.total()||!info.hash().equals(p.hash()))return false;
        if(!incoming.append(p.token(),p.offset(),p.bytes()))return false;
        if(incoming.complete()){
            // Hash and core load run on the worker, not on the client thread.
            byte[] state=incoming.bytesAfterAssembly();incoming=null;incomingInfo=null;
            worker.restore(info.token(),info.frame(),info.goal(),state,info.hash());
        }
        return false;
    }
    void active(CabinetSyncNetwork.Active p){if(scope(p.room(),p.member(),p.epoch())&&(room.port()==0?p.token().equals(room.room()):p.token().equals(awaitingAck))){active=true;resyncPending=false;awaitingAck=null;}}
    void grant(CabinetSyncNetwork.UploadGrant p){if(scope(p.room(),p.member(),p.epoch()))outgoing.grant(p.token(),tick);}
    void resync(){if(closed)return;resyncPending=true;active=false;if(tick<nextResync)return;nextResync=tick+40;PacketDistributor.sendToServer(new CabinetSyncNetwork.Resync(room.room(),room.member(),1));}
    void tick(){
        if(closed||!connection.isConnected())return;tick++;
        if(resyncPending&&tick>=nextResync)resync();
        for(int i=0;i<12;i++){
            var e=worker.pollEvent();if(e==null)break;
            switch(e.kind()){
                case CabinetSyncWorker.Event.HELLO->{String content=CabinetSharedGames.resolvedContentId(room.member());if(!CabinetSyncState.validHash(content))throw new IllegalStateException("Shared ROM content grant expired");
                    PacketDistributor.sendToServer(new CabinetSyncNetwork.Hello(room.room(),room.member(),1,e.hash(),content,e.compatibility(),e.fpsMilli(),new String(e.state(),java.nio.charset.StandardCharsets.US_ASCII)));}
                case CabinetSyncWorker.Event.DIGEST->PacketDistributor.sendToServer(new CabinetSyncNetwork.Digest(room.room(),room.member(),1,e.frame(),e.hash()));
                case CabinetSyncWorker.Event.SNAPSHOT->{if(room.port()==0)outgoing.offer(e);}
                case CabinetSyncWorker.Event.RESTORED->{if(e.token().equals(awaitingAck))PacketDistributor.sendToServer(new CabinetSyncNetwork.Ack(room.room(),room.member(),1,e.token(),e.frame()));}
                case CabinetSyncWorker.Event.RESYNC->resync();
                default->throw new IllegalStateException("Unknown sync event");
            }
        }
        var upload=outgoing.current(tick);if(upload==null)return;
        if(upload.needsOffer(tick)){var p=new CabinetSyncNetwork.StatePart(room.room(),room.member(),1,upload.token,upload.frame,upload.frame,upload.state.length,0,upload.hash,true,new byte[0]);if(!CabinetSyncSender.send(connection,new CabinetSyncNetwork.Upload(p),512))return;upload.offered(tick);}
        if(!upload.granted)return;
        for(int i=0;i<2&&upload.offset<upload.state.length;i++){
            int n=Math.min(CabinetSyncState.CHUNK,upload.state.length-upload.offset);byte[] bytes=Arrays.copyOfRange(upload.state,upload.offset,upload.offset+n);
            var p=new CabinetSyncNetwork.StatePart(room.room(),room.member(),1,upload.token,upload.frame,upload.frame,upload.state.length,upload.offset,upload.hash,false,bytes);
            if(!CabinetSyncSender.send(connection,new CabinetSyncNetwork.Upload(p),n+512))break;upload.offset+=n;
        }
        if(upload.offset==upload.state.length)outgoing.complete(upload);
    }
    @Override public void close(){closed=true;active=false;outgoing.close();incoming=null;incomingInfo=null;seen.clear();}
}
