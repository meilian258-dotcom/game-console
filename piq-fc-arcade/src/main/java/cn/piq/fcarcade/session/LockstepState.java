package cn.piq.fcarcade.session;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LockstepState {
    public static final int FRAMES_PER_SERVER_TICK = 3;

    private final ControllerInputTransitions[] controllers = {
            new ControllerInputTransitions(), new ControllerInputTransitions()};
    private final Map<UUID, Integer> lastSequences = new HashMap<>();
    private final Map<UUID, Integer> gunSequences = new HashMap<>();
    private final ZapperInputQueue gun = new ZapperInputQueue();
    private int epoch;
    private long frame;
    private long releaseRevision;
    /** Monotonic safety barrier for queued Netplay input; unrelated to simulation epoch. */
    public long releaseRevision(){return releaseRevision;}

    public void restart() {
        epoch++;
        frame = 0;
        clearInputs();
        lastSequences.clear();
        gunSequences.clear();
    }

    public void clearInputs() {
        releaseRevision++;
        controllers[0].clear();
        controllers[1].clear();
        gun.clear();
    }

    /** A stowed home controller must not latch its last buttons or mute the other port. */
    public void clearController(int controller) {
        if (controller >= 0 && controller < controllers.length) {controllers[controller].clear();releaseRevision++;}
    }

    /** A reconnect creates a fresh client sequence, without resetting any other controller. */
    public void forgetPlayer(UUID playerId) {
        lastSequences.remove(playerId);
        gunSequences.remove(playerId);
    }

    public void clearZapper(){gun.clear();releaseRevision++;}
    public boolean canAcceptZapper(UUID player,int packetEpoch,int sequence,int state,boolean forceRelease){
        if(player==null||packetEpoch!=epoch||sequence<0||sequence<=gunSequences.getOrDefault(player,-1))return false;
        ZapperInput.validate(state);return !forceRelease||state==ZapperInput.NEUTRAL;
    }
    public boolean acceptZapper(UUID player,int packetEpoch,int sequence,int state,boolean forceRelease,boolean rateLimited){
        if(!canAcceptZapper(player,packetEpoch,sequence,state,forceRelease))return false;
        gunSequences.put(player,sequence);
        if(forceRelease){clearZapper();return true;}
        if(rateLimited){gun.failClosed();releaseRevision++;return false;}
        boolean accepted=gun.offer(state);if(!accepted)releaseRevision++;return accepted;
    }

    public boolean acceptInput(
            UUID playerId,
            int packetEpoch,
            int controller,
            int sequence,
            int buttonMask
    ) {
        return acceptInput(playerId, packetEpoch, controller, sequence, buttonMask, false, false);
    }

    /** Invalid/stale packets cannot release or overload a newer controller state. */
    public boolean acceptInput(UUID playerId, int packetEpoch, int controller,
                               int sequence, int buttonMask, boolean forceRelease, boolean rateLimited) {
        if (packetEpoch != epoch
                || playerId == null || controller < 0 || controller >= controllers.length
                || sequence < 0 || (buttonMask & ~0xFF) != 0 || (forceRelease && buttonMask != 0)) {
            return false;
        }
        int previousSequence = lastSequences.getOrDefault(playerId, -1);
        if (sequence <= previousSequence) return false;
        lastSequences.put(playerId, sequence);
        if (forceRelease) {
            clearController(controller);
            return true;
        }
        if (rateLimited) {
            controllers[controller].failClosed();releaseRevision++;
            return false;
        }
        boolean accepted=controllers[controller].offer(buttonMask);if(!accepted)releaseRevision++;return accepted;
    }

    /** The server calls this exactly three times per tick, broadcasting every frame. */
    public FrameStep advanceFrame() {
        frame++;
        return new FrameStep(
                epoch,
                frame,
                controllers[0].nextFrame(),
                controllers[1].nextFrame(), gun.nextFrame());
    }

    public int epoch() {
        return epoch;
    }

    public long targetFrame() {
        return frame;
    }

    public record FrameStep(
            int epoch,
            long targetFrame,
            int playerOneMask,
            int playerTwoMask,
            int zapperState
    ) {
        public FrameStep(int epoch,long frame,int one,int two){this(epoch,frame,one,two,ZapperInput.NEUTRAL);}
        public FrameStep { ZapperInput.validate(zapperState); }
    }
}
