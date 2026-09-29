package cn.piq.fcarcade.client;

import java.util.Arrays;
import java.util.UUID;

/** Local presentation only. Inputs are the worker's completed-frame masks, never key guesses. */
final class ControllerButtonAnimation {
    static final int A=1, B=2, SELECT=4, START=8, UP=16, DOWN=32, LEFT=64, RIGHT=128;
    private final double[] values=new double[8];
    private UUID lease;
    private long session=-1, previousNanos;
    private int port=-1, hand=-1;
    private boolean active;

    void update(UUID nextLease,long nextSession,int nextPort,int nextHand,int mask,boolean allowed,long nanos) {
        if(!allowed||nextLease==null||nextSession<0||nextPort<0||nextPort>1||nextHand<0||nextHand>1) { clear(); return; }
        boolean changed=!nextLease.equals(lease)||nextSession!=session||nextPort!=port||nextHand!=hand;
        if(changed) {
            clear();lease=nextLease;session=nextSession;port=nextPort;hand=nextHand;
            previousNanos=nanos;active=true;return; // Fresh lease/hand gets one neutral frame, never the old grip's depression.
        }
        double seconds=previousNanos==0?1/60.0:Math.clamp((nanos-previousNanos)/1e9,0,.1);
        previousNanos=nanos;active=true;
        for(int bit=0;bit<8;bit++) {
            double target=(mask&(1<<bit))!=0?1:0;
            double distance=seconds/(target>values[bit]?.025:.070);
            values[bit]=target>values[bit]?Math.min(target,values[bit]+distance):Math.max(target,values[bit]-distance);
        }
    }
    void clear() { Arrays.fill(values,0);lease=null;session=-1;port=hand=-1;previousNanos=0;active=false; }
    boolean matches(UUID id,long session,int port,int hand) {
        return active&&lease!=null&&lease.equals(id)&&this.session==session&&this.port==port&&this.hand==hand;
    }
    double value(int mask) { return values[Integer.numberOfTrailingZeros(mask)]; }
    double pitch() { return (value(DOWN)-value(UP))*6; }
    double yaw() { return (value(RIGHT)-value(LEFT))*6; }
}
