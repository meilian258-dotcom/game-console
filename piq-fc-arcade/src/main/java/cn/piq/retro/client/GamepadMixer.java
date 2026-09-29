package cn.piq.retro.client;

import cn.piq.retro.input.*;

/** Pure adapter. Keyboard delivery is immediate, never gated by gamepad neutral/reconnect. */
public final class GamepadMixer {
    private GamepadConfig config = GamepadConfig.defaults();
    private LocalInputSession pad = new LocalInputSession();
    private Object owner;
    private String system;

    public synchronized void configure(GamepadConfig value) {
        config = java.util.Objects.requireNonNull(value);
        if (owner != null) pad.release(owner);
        system = null;
        pad = new LocalInputSession(value.deadzoneEnter(), value.deadzoneExit());
        if (owner != null) pad.acquire(owner);
    }

    public synchronized int mix(Object candidate, String kind, int keyboardNative, boolean active, GamepadState sample) {
        int keyboard = canonical(kind, keyboardNative);
        if (candidate == null || !active || !config.enabled()) { pause(candidate); return keyboardNative; }
        if (owner != null && owner != candidate) return keyboardNative;
        if (owner == null) { owner = candidate; pad.acquire(candidate); }
        if (!kind.equals(system)) { system = kind; pad.profile(candidate, config.profiles().get(kind)); }
        pad.focus(candidate, true);
        if (sample == null || (!config.deviceKey().isEmpty() && !sample.device().id().equals(config.deviceKey()))) { pad.selectDevice(candidate, null); return keyboardNative; }
        pad.selectDevice(candidate, sample.device()); pad.gamepad(candidate, sample);
        int physical = pad.current(candidate);
        pad.poll(candidate); // Host captures this returned edge; do not retain a second delayed queue.
        if (physical == 0) return keyboardNative;
        int merged = RetroButtons.neutralizeOpposites(keyboard | physical);
        return "NES".equals(kind) ? InputMappings.nes8(merged) : merged;
    }

    public synchronized void pause(Object candidate) { if (owner == candidate && candidate != null) pad.focus(candidate, false); }
    public synchronized void release(Object candidate) {
        if (owner == candidate && candidate != null) { pad.release(candidate); owner = null; system = null; }
    }
    public synchronized boolean armed(Object candidate) { return owner == candidate && pad.gamepadArmed(candidate); }

    static int canonical(String kind, int nativeMask) {
        if (!GamepadConfig.SYSTEMS.contains(kind)) throw new IllegalArgumentException("Unknown system");
        if (!"NES".equals(kind)) return RetroButtons.requireValid(nativeMask);
        if ((nativeMask & ~255) != 0) throw new IllegalArgumentException("Invalid NES mask");
        return (nativeMask & 0xfc) | ((nativeMask & 1) << 8) | ((nativeMask & 2) >>> 1);
    }
}
