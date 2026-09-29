package cn.piq.sfchome.server;
/** Counts retained upload buffers, including queued/in-flight IO after FINISH. */
public final class SfcTransferBudget {
    public static final long LIMIT=64L*1024*1024;
    private long reserved;
    public boolean reserve(long bytes){if(bytes<=0||bytes>LIMIT||reserved>LIMIT-bytes)return false;reserved+=bytes;return true;}
    public void release(long bytes){if(bytes<=0||bytes>reserved)throw new IllegalStateException("Unbalanced buffer budget");reserved-=bytes;}
    public long reserved(){return reserved;}
}
