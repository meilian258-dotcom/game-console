package cn.piq.fcarcade.netplay;

import java.util.Arrays;

/** Computing host's server-fed four-port mailbox. Coins survive gameplay resets,
 * but are consumed once per displayed native frame, never during rollback replay. */
public final class NetplayCabinetInputs {
    private final boolean paid;
    public NetplayCabinetInputs(){this(true);}
    public NetplayCabinetInputs(boolean paid){this.paid=paid;}
    private final int[] masks=new int[4],coins=new int[4],phase=new int[4];
    private final long[] expires=new long[4];
    private long coinSequence;
    private boolean closed;
    public synchronized void input(int port,int mask,long now){
        check(port);if((mask&~4095)!=0)throw new IllegalArgumentException("Mask");
        if(closed)return;masks[port]=paid?mask&~4:mask;expires[port]=now+2_000_000_000L;
    }
    public synchronized boolean coin(int port,long sequence){
        check(port);if(closed||sequence<=coinSequence||sequence<1)return false;
        if(coins[port]>=64)throw new IllegalStateException("Netplay 投币队列已满");
        coinSequence=sequence;coins[port]++;return true;
    }
    public synchronized int[] next(long now){
        int[] result=new int[4];if(closed)return result;
        for(int p=0;p<4;p++){
            result[p]=now<expires[p]?masks[p]:0;
            if(phase[p]==0&&coins[p]>0){coins[p]--;phase[p]=6;}
            if(phase[p]>3)result[p]|=4; // Three down frames, three release frames.
            if(phase[p]>0)phase[p]--;
        }
        return result;
    }
    public synchronized void release(int port){check(port);masks[port]=0;expires[port]=0;}
    public synchronized void close(){closed=true;Arrays.fill(masks,0);Arrays.fill(coins,0);Arrays.fill(phase,0);}
    private static void check(int port){if(port<0||port>3)throw new IllegalArgumentException("Port");}
}
