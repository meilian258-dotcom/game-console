package cn.piq.sfchome.client;

/** Pure dispatch condition; elapsed ticks are advanced by ClientTickEvent only. */
final class SfcInputSendPolicy {
    static final int HEARTBEAT_TICKS=2;
    static final long STALL_NANOS=500_000_000L;
    private SfcInputSendPolicy() {}
    static boolean shouldSend(boolean force,int mask,int previousMask,int elapsedTicks) {
        return force||mask!=previousMask||elapsedTicks>=HEARTBEAT_TICKS;
    }
    static boolean resumingAfterStall(long previousSample,long now){return previousSample!=0&&now-previousSample>=STALL_NANOS;}
}
