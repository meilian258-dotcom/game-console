package cn.piq.fcarcade.cabinet;

import java.util.*;

/** Server-owned four-port edge FIFO and bounded authoritative frame history; no emulator. */
public final class CabinetSyncTimeline {
    public static final int MAX_HISTORY=7200, MAX_BATCH=120;
    private final ArrayDeque<Integer>[] edges;
    private final int[] submitted=new int[4],applied=new int[4];
    private final boolean[] blocked=new boolean[4];
    private final int[] coins=new int[4];
    private final boolean[] coinDown=new boolean[4];
    private final ArrayDeque<Step> history=new ArrayDeque<>();
    private long frame,clock;
    private final int fpsMilli;
    public record Step(long frame,int p1,int p2,int p3,int p4){
        public Step {if(frame<1||((p1|p2|p3|p4)&~4095)!=0)throw new IllegalArgumentException("Invalid sync step");}
        public int[] masks(){return new int[]{p1,p2,p3,p4};}
    }
    @SuppressWarnings("unchecked") public CabinetSyncTimeline(int fpsMilli){
        if(fpsMilli<40000||fpsMilli>80000)throw new IllegalArgumentException("Invalid frame rate");
        this.fpsMilli=fpsMilli;edges=new ArrayDeque[4];for(int i=0;i<4;i++)edges[i]=new ArrayDeque<>();
    }
    public boolean input(int port,int mask){
        checkPort(port);if((mask&~4095)!=0)return false;
        if(blocked[port]){if(mask==0)releaseGameplay(port);return mask==0;}
        if(submitted[port]==mask)return true;
        if(edges[port].size()>=32){releaseGameplay(port);blocked[port]=true;return false;}
        submitted[port]=mask;edges[port].addLast(mask);return true;
    }
    /** Separate from user edges, so a later keyboard packet cannot erase an accepted coin. */
    public boolean coin(int port){checkPort(port);if(blocked[port]||coins[port]>=8)return false;coins[port]++;return true;}
    /** GUI/focus/silence releases are not refunds or seat destruction: keep paid pulses. */
    public void releaseGameplay(int port){checkPort(port);edges[port].clear();submitted[port]=applied[port]=0;blocked[port]=false;}
    /** A departed seat must never give its pending inputs to the next occupant. */
    public void release(int port){releaseGameplay(port);coins[port]=0;coinDown[port]=false;}
    public List<Step> tick(){
        clock+=fpsMilli;int count=(int)(clock/20000);clock%=20000;var out=new ArrayList<Step>(count);
        for(int n=0;n<count;n++){
            for(int i=0;i<4;i++)if(!edges[i].isEmpty())applied[i]=edges[i].removeFirst();
            int[] masks=applied.clone();for(int i=0;i<4;i++){
                if(coinDown[i])coinDown[i]=false;
                else if(coins[i]>0){coins[i]--;coinDown[i]=true;masks[i]|=CabinetCoinPolicy.COIN_MASK;}
            }
            var step=new Step(++frame,masks[0],masks[1],masks[2],masks[3]);history.addLast(step);out.add(step);
            if(history.size()>MAX_HISTORY)history.removeFirst();
        }
        return List.copyOf(out);
    }
    public long frame(){return frame;}
    public List<Step> after(long cursor,int limit){
        if(cursor<0||cursor>frame||limit<1||limit>MAX_BATCH||!history.isEmpty()&&cursor<history.getFirst().frame()-1)
            throw new IllegalArgumentException("Sync history expired");
        var out=new ArrayList<Step>();for(var s:history)if(s.frame()>cursor){out.add(s);if(out.size()==limit)break;}return List.copyOf(out);
    }
    private static void checkPort(int p){if(p<0||p>3)throw new IllegalArgumentException("Port");}
}
