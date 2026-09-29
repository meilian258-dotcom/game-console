package cn.piq.fcarcade.session;

import java.util.ArrayDeque;

/** Keep every trigger edge; coalesce only motion with the same trigger level. */
public final class ZapperInputQueue {
    public static final int MAX_PENDING=32;
    private final ArrayDeque<Integer> pending=new ArrayDeque<>();
    private int held=ZapperInput.NEUTRAL,latest=ZapperInput.NEUTRAL;
    private boolean blocked;
    public boolean offer(int state) {
        ZapperInput.validate(state);
        if(blocked){if(!ZapperInput.trigger(state)){blocked=false;held=latest=state;}return false;}
        if(state==latest)return true;
        if(!pending.isEmpty()&&ZapperInput.trigger(pending.peekLast())==ZapperInput.trigger(state))pending.removeLast();
        if(pending.size()>=MAX_PENDING){failClosed();return false;}
        pending.addLast(state);latest=state;return true;
    }
    public int nextFrame(){if(!pending.isEmpty())held=pending.removeFirst();return held;}
    public void clear(){pending.clear();held=latest=ZapperInput.NEUTRAL;blocked=false;}
    public void failClosed(){clear();blocked=true;}
    public int pending(){return pending.size();}
}
