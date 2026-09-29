package cn.piq.sfchome.client;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Only decoded video is proof that a media controller can see the game it controls. */
final class SfcMediaFreshness {
    static final long STARTUP_NANOS=60_000_000_000L;
    static final long STALL_NANOS=3_000_000_000L;
    static final long TIMEOUT_NANOS=15_000_000_000L;
    enum Phase { WAITING, LIVE, STALLED, TIMED_OUT }
    record Status(Phase phase,long interruption) { boolean paused(){return phase!=Phase.LIVE;} }
    private final LongSupplier clock;
    private long began,lastVideo,interruption;
    private boolean seenVideo,stalled,timedOut;

    SfcMediaFreshness(){this(System::nanoTime);}
    SfcMediaFreshness(LongSupplier clock){this.clock=Objects.requireNonNull(clock);began=clock.getAsLong();}
    synchronized Status status(){return sample(clock.getAsLong());}
    private Status sample(long now){
        long age=Math.max(0,now-(seenVideo?lastVideo:began));
        if(!timedOut&&age>=(seenVideo?TIMEOUT_NANOS:STARTUP_NANOS))timedOut=true;
        if(seenVideo&&!stalled&&age>=STALL_NANOS){stalled=true;interruption++;}
        return new Status(timedOut?Phase.TIMED_OUT:!seenVideo?Phase.WAITING:stalled?Phase.STALLED:Phase.LIVE,interruption);
    }
    /** Called after successful complete-frame decode, never for PCM or fragments. */
    synchronized boolean decodedVideo(){
        long now=clock.getAsLong();
        if(sample(now).phase()==Phase.TIMED_OUT)return false;
        lastVideo=now;seenVideo=true;stalled=false;return true;
    }
    synchronized void reset(){
        began=clock.getAsLong();lastVideo=0;seenVideo=false;stalled=false;timedOut=false;interruption++;
    }
}
