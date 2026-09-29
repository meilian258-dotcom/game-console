package cn.piq.sfchome.server;
import java.util.ArrayDeque;
/** At most 32 ordered transitions, one consumed per emulated frame. Overflow stops the session. */
public final class SfcInputTimeline {
    private final ArrayDeque<Integer> queue=new ArrayDeque<>();
    private int sequence=-1,held,latest;
    private boolean waitingForNeutral;
    public boolean offer(int seq,int mask,boolean release){
        if(seq<=sequence||mask<0||mask>4095||release&&mask!=0)return false;
        sequence=seq;
        if(release){queue.clear();held=latest=0;waitingForNeutral=false;return true;}
        // A newer sequence alone does not prove a delayed held-key heartbeat is fresh.
        // After a stall consume it, but require a neutral sample before accepting new presses.
        if(waitingForNeutral){if(mask==0)waitingForNeutral=false;return true;}
        if(mask==latest)return true;
        if(queue.size()>=32){queue.clear();held=latest=0;return false;}
        latest=mask;queue.addLast(mask);return true;
    }
    public int next(){if(!queue.isEmpty())held=queue.removeFirst();return held;}
    public int pending(){return queue.size();}
    public void clear(){queue.clear();held=latest=0;}
    public void neutralizeStale(){clear();waitingForNeutral=true;}
}
