package cn.piq.fcarcade.netplay;

import cn.piq.fcarcade.session.ZapperInput;
import java.util.ArrayDeque;

/** Server-authorized absolute states. Keep key/trigger edges, coalesce only motion.
 * Native owner consumes once per frame. No local mouse samples enter this queue. */
public final class NetplayGunMailbox {
    public static final long TIMEOUT_NANOS=750_000_000L;
    public static final int CAPACITY=32;
    public record Sample(int buttons,int aim) {
        public Sample {if((buttons&~255)!=0)throw new IllegalArgumentException("Gun buttons");ZapperInput.validate(aim);}
        boolean released(){return buttons==0&&!ZapperInput.trigger(aim);}
        boolean sameEdges(Sample other){return buttons==other.buttons&&ZapperInput.trigger(aim)==ZapperInput.trigger(other.aim);}
        public int coordinates(){
            int x=((2*ZapperInput.x(aim)+1)*65536/512)-32768;
            int y=((2*ZapperInput.y(aim)+1)*65536/480)-32768;
            return (x<<16)|(y&65535);
        }
        public int flags(){return (ZapperInput.offscreen(aim)?1:0)|(ZapperInput.trigger(aim)?2:0);}
    }
    public static final Sample NEUTRAL=new Sample(0,ZapperInput.NEUTRAL);
    private final ArrayDeque<Sample> pending=new ArrayDeque<>();
    private Sample applied=NEUTRAL,latest=NEUTRAL;
    private long revision=-1,sequence=-1,receivedAt;
    private boolean blocked,received;
    public synchronized boolean offer(long rev,long seq,int buttons,int aim,long now){
        Sample next=new Sample(buttons,aim);
        if(rev<0||seq<0)throw new IllegalArgumentException("Gun sequence");
        if(rev<revision||seq<=sequence)return false;
        if(rev>revision){clearStates();revision=rev;blocked=false;}
        sequence=seq;receivedAt=now;received=true;
        if(blocked){if(!next.released())return false;blocked=false;clearStates();}
        if(next.equals(latest))return true;
        if(!pending.isEmpty()&&pending.peekLast().sameEdges(next))pending.removeLast();
        if(pending.size()>=CAPACITY){clearStates();blocked=true;return false;}
        pending.addLast(next);latest=next;return true;
    }
    public synchronized Sample next(long now){
        if(!received||now-receivedAt>TIMEOUT_NANOS){clearStates();blocked=true;return NEUTRAL;}
        if(!pending.isEmpty())applied=pending.removeFirst();
        return applied;
    }
    public synchronized void close(){clearStates();received=false;blocked=true;}
    private void clearStates(){pending.clear();applied=latest=NEUTRAL;}
}
