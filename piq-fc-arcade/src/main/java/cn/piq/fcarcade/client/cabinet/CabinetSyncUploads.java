package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetSyncCore;
import cn.piq.fcarcade.cabinet.CabinetSyncState;
import java.util.UUID;

/** Client-thread, bounded snapshot mailbox: one transaction plus the newest replacement only. */
final class CabinetSyncUploads implements AutoCloseable {
    static final long OFFER_RETRY_TICKS=40, DEADLINE_TICKS=1000;
    static final class Upload {
        final UUID token=UUID.randomUUID();
        final long frame,deadline;
        final byte[] state;
        final String hash;
        int offset;
        boolean begun,granted;
        long nextOffer;
        Upload(CabinetSyncWorker.Event event,long now){
            frame=event.frame();state=event.state();hash=event.hash();deadline=now+DEADLINE_TICKS;nextOffer=now;
        }
        boolean needsOffer(long now){return !granted&&now>=nextOffer&&now<deadline;}
        void offered(long now){begun=true;nextOffer=now+OFFER_RETRY_TICKS;}
    }
    private CabinetSyncWorker.Event latest;
    private Upload current;
    private long newestFrame=-1;
    private boolean closed;

    void offer(CabinetSyncWorker.Event event){
        if(closed)return;
        if(event==null||event.kind()!=CabinetSyncWorker.Event.SNAPSHOT||event.frame()<0
                ||event.state()==null||event.state().length<1||event.state().length>CabinetSyncCore.MAX_STATE_BYTES
                ||!CabinetSyncState.validHash(event.hash()))throw new IllegalArgumentException("Invalid snapshot mailbox event");
        if(event.frame()<=newestFrame)return;
        newestFrame=event.frame();latest=event;
    }
    Upload current(long now){
        if(closed)return null;
        if(current!=null&&now>=current.deadline){
            // Never renew a token indefinitely. A later real checkpoint may start a fresh transaction.
            current=null;
            if(latest==null)throw new IllegalStateException("同步快照传输超时；未重新开始游戏");
        }
        if(current==null&&latest!=null){current=new Upload(latest,now);latest=null;}
        return current;
    }
    void grant(UUID token,long now){
        if(!closed&&current!=null&&current.begun&&current.token.equals(token)&&now<current.deadline)
            current.granted=true;
    }
    void complete(Upload upload){
        if(!closed&&current==upload&&upload.granted&&upload.offset==upload.state.length)current=null;
    }
    @Override public void close(){closed=true;latest=null;current=null;}
}
