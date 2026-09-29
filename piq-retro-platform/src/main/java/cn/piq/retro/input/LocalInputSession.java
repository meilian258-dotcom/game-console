package cn.piq.retro.input;

import java.util.ArrayDeque;
import java.util.Objects;

import static cn.piq.retro.input.GamepadState.Control;

/**
 * One explicitly selected gamepad + one keyboard source for ONE local gameplay owner.
 * It never creates a second local player. Owner comparison is identity, not equals().
 * All methods are synchronized; callers should still deliver snapshots in capture order.
 */
public final class LocalInputSession {
    public static final int MAX_QUEUED_EDGES = 256;
    private final ArrayDeque<Integer> edges = new ArrayDeque<>();
    private final StickDeadzone leftStick, rightStick;
    private final StickDeadzone.Trigger leftTrigger = new StickDeadzone.Trigger(), rightTrigger = new StickDeadzone.Trigger();
    private Object owner;
    private GamepadState.DeviceId selectedDevice;
    private InputProfile profile = InputProfile.defaults();
    private boolean focused, keyboardArmed, gamepadArmed;
    private int keyboard, gamepad, current, delivered;
    private long overflows;

    public LocalInputSession() { this(StickDeadzone.DEFAULT_ENTER, StickDeadzone.DEFAULT_EXIT); }

    /** A settings change constructs a fresh session, requiring neutral input before use. */
    public LocalInputSession(float deadzoneEnter, float deadzoneExit) {
        leftStick = new StickDeadzone(deadzoneEnter, deadzoneExit);
        rightStick = new StickDeadzone(deadzoneEnter, deadzoneExit);
    }

    /** A new owner starts unfocused and disarmed. The same owner may reacquire idempotently. */
    public synchronized boolean acquire(Object requestedOwner) {
        Objects.requireNonNull(requestedOwner, "owner");
        if (owner == requestedOwner) return true;
        if (owner != null) return false;
        owner = requestedOwner;
        focused = false;
        clearAll();
        return true;
    }

    public synchronized boolean release(Object requestedOwner) {
        if (!owns(requestedOwner)) return false;
        clearAll(); focused = false; owner = null;
        return true;
    }

    /** A switch invalidates pad edges, but a genuinely held keyboard button remains held. */
    public synchronized boolean selectDevice(Object requestedOwner, GamepadState.DeviceId device) {
        if (!owns(requestedOwner)) return false;
        if (Objects.equals(selectedDevice, device)) return true;
        selectedDevice = device;
        clearGamepad();
        return true;
    }

    public synchronized boolean profile(Object requestedOwner, InputProfile newProfile) {
        if (!owns(requestedOwner)) return false;
        Objects.requireNonNull(newProfile, "profile");
        if (profile == newProfile) return true;
        profile = newProfile;
        clearGamepad();
        return true;
    }

    public synchronized boolean focus(Object requestedOwner, boolean hasFocus) {
        if (!owns(requestedOwner)) return false;
        if (focused == hasFocus) return true;
        focused = hasFocus;
        if (!hasFocus) clearAll();
        return true;
    }

    public synchronized boolean keyboard(Object requestedOwner, int logical12) {
        if (!owns(requestedOwner)) return false;
        RetroButtons.requireValid(logical12);
        if (!focused) return false;
        if (!keyboardArmed) {
            if (logical12 == 0) keyboardArmed = true;
            return true;
        }
        keyboard = logical12;
        publish();
        return true;
    }

    public synchronized boolean gamepad(Object requestedOwner, GamepadState state) {
        if (!owns(requestedOwner)) return false;
        Objects.requireNonNull(state, "state");
        if (!Objects.equals(selectedDevice, state.device())) return false;
        if (!state.connected()) { clearGamepad(); return true; }
        if (!focused) return false;
        if (!gamepadArmed) {
            if (neutral(state)) gamepadArmed = true;
            return true;
        }
        long controls = state.buttons();
        var l = leftStick.sample(state.leftX(), state.leftY());
        var r = rightStick.sample(state.rightX(), state.rightY());
        if (l.up()) controls |= Control.LEFT_STICK_UP.mask();
        if (l.down()) controls |= Control.LEFT_STICK_DOWN.mask();
        if (l.left()) controls |= Control.LEFT_STICK_LEFT.mask();
        if (l.right()) controls |= Control.LEFT_STICK_RIGHT.mask();
        if (r.up()) controls |= Control.RIGHT_STICK_UP.mask();
        if (r.down()) controls |= Control.RIGHT_STICK_DOWN.mask();
        if (r.left()) controls |= Control.RIGHT_STICK_LEFT.mask();
        if (r.right()) controls |= Control.RIGHT_STICK_RIGHT.mask();
        if (leftTrigger.sample(state.leftTrigger())) controls |= Control.LEFT_TRIGGER.mask();
        if (rightTrigger.sample(state.rightTrigger())) controls |= Control.RIGHT_TRIGGER.mask();
        gamepad = profile.map(controls);
        publish();
        return true;
    }

    /** Immediate latest state. For emulator input, poll preserves captured short taps. */
    public synchronized int current(Object requestedOwner) { return owns(requestedOwner) ? current : 0; }

    /** Consume one merged state transition per emulated input frame; keep held state if empty. */
    public synchronized int poll(Object requestedOwner) {
        if (!owns(requestedOwner)) return 0;
        if (!edges.isEmpty()) delivered = edges.removeFirst();
        return delivered;
    }

    /** Safety reset: drops pending transitions and requires fresh neutral samples from both sources. */
    public synchronized boolean clear(Object requestedOwner) {
        if (!owns(requestedOwner)) return false;
        clearAll();
        return true;
    }

    public synchronized int queuedEdges(Object requestedOwner) { return owns(requestedOwner) ? edges.size() : 0; }
    public synchronized long overflowCount() { return overflows; }
    public synchronized boolean gamepadArmed(Object requestedOwner) { return owns(requestedOwner) && gamepadArmed; }

    private boolean owns(Object requestedOwner) { return requestedOwner != null && owner == requestedOwner; }

    private boolean neutral(GamepadState state) {
        return state.buttons() == 0 && leftStick.centered(state.leftX(), state.leftY())
                && rightStick.centered(state.rightX(), state.rightY())
                && leftTrigger.released(state.leftTrigger()) && rightTrigger.released(state.rightTrigger());
    }

    private void publish() {
        int merged = RetroButtons.neutralizeOpposites(keyboard | gamepad);
        if (merged == current) return;
        if (edges.size() >= MAX_QUEUED_EDGES) {
            overflows++;
            clearAll();
            return;
        }
        current = merged;
        edges.addLast(merged);
    }

    private void clearGamepad() {
        gamepad = 0; gamepadArmed = false;
        resetFilters();
        // Pending snapshots combine both sources: none may replay a disconnected pad's press.
        // Safety resets deliberately discard queued short taps, retaining the live keyboard state.
        edges.clear();
        current = RetroButtons.neutralizeOpposites(keyboard);
        delivered = current;
    }

    private void clearAll() {
        keyboard = 0; gamepad = 0; current = 0; delivered = 0;
        keyboardArmed = false; gamepadArmed = false;
        edges.clear();
        resetFilters();
    }

    private void resetFilters() { leftStick.reset(); rightStick.reset(); leftTrigger.reset(); rightTrigger.reset(); }
}
