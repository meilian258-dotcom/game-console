package cn.piq.computer.stream;

/** Monotonic-clock token bucket. No disk/network/MC dependency. */
public final class StreamBudget {
    public static final int[] RATES={96*1024,192*1024,384*1024};
    private final int rate,burst;
    private double tokens;
    private long at;
    public StreamBudget(int tier,long now){rate=RATES[Math.clamp(tier,0,2)];burst=65536;tokens=burst;at=now;}
    public synchronized boolean take(int bytes,long now){
        if(bytes<0||bytes>burst)return false;
        tokens=Math.min(burst,tokens+Math.max(0,now-at)*rate/1e9);at=Math.max(at,now);
        if(tokens<bytes)return false;tokens-=bytes;return true;
    }
}
