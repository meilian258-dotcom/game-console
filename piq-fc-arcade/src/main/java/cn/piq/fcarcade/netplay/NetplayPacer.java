package cn.piq.fcarcade.netplay;

/** Per-direction TCP reader backpressure. Both directions stay below the server's 2 MiB/s cap. */
final class NetplayPacer {
    static final long BYTES_PER_SECOND=768*1024;
    private long next;
    long delay(int bytes,long now){
        if(bytes<1||bytes>NetplayChunk.LIMIT)throw new IllegalArgumentException("Fragment size");
        long at=Math.max(next,now);next=at+(bytes+40L)*1_000_000_000L/BYTES_PER_SECOND;
        return at-now;
    }
}
