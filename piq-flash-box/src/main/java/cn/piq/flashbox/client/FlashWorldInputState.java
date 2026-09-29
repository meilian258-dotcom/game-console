package cn.piq.flashbox.client;

import java.util.HashSet;
import java.util.Set;

/** Pure local capture gate. No Minecraft, native calls, network or emulator profile. */
public final class FlashWorldInputState {
    public record Masks(int p1, int p2) {}
    private final Set<Integer> held = new HashSet<>();
    private final Set<Integer> drainKeys = new HashSet<>();
    private final Set<Integer> drainButtons = new HashSet<>();
    private boolean active, armed, focusDrain;

    public boolean active() { return active; }
    public boolean armed() { return armed; }
    public boolean draining() { return !drainKeys.isEmpty() || !drainButtons.isEmpty(); }

    /** Even an empty entry waits for a fresh physical sample before accepting input. */
    public void begin(Set<Integer> keys) {
        active = true;
        armed = false;
        held.clear();
        held.addAll(keys);
    }

    public void sample(Set<Integer> keys, Set<Integer> buttons) {
        // A focus-loss synthetic RELEASE does not prove the physical key was lifted.
        if (!focusDrain) {
            drainKeys.retainAll(keys);
            drainButtons.retainAll(buttons);
        }
        if (!draining()) focusDrain = false;
        if (!active) return;
        held.clear();
        held.addAll(keys);
        if (!armed && keys.isEmpty() && buttons.isEmpty() && !draining()) armed = true;
    }

    /** Return true for the release too: no trailing event escapes to a world keybind. */
    public boolean key(int key, int action) {
        return key(key, action, true);
    }

    public boolean key(int key, int action, boolean focused) {
        boolean blocked = drainKeys.contains(key) || modifierDraining();
        if (action == 0 && focused) drainKeys.remove(key);
        else if (blocked && action != 0) drainKeys.add(key);
        if (active) {
            if (action == 0) held.remove(key); else held.add(key);
            return true;
        }
        return blocked;
    }

    public boolean mouse(int button, int action) {
        return mouse(button, action, true);
    }

    public boolean mouse(int button, int action, boolean focused) {
        boolean blocked = drainButtons.contains(button) || modifierDraining();
        if (action == 0 && focused) drainButtons.remove(button);
        else if (blocked && action != 0) drainButtons.add(button);
        if (active) return true;
        return blocked;
    }

    public void end(Set<Integer> keys, Set<Integer> buttons) {
        end(keys, buttons, false);
    }

    public void end(Set<Integer> keys, Set<Integer> buttons, boolean lostFocus) {
        drainKeys.addAll(held);
        drainKeys.addAll(keys);
        drainButtons.addAll(buttons);
        held.clear();
        active = false;
        armed = false;
        focusDrain |= lostFocus;
    }

    private boolean modifierDraining() {
        // GLFW left/right Shift/Ctrl/Alt/Super and vanilla's physical F3 combinations.
        return drainKeys.contains(292) || drainKeys.stream().anyMatch(key -> key >= 340 && key <= 347);
    }

    public Masks masks() {
        if (!active || !armed) return new Masks(0, 0);
        // GLFW key IDs; exactly the two five-bit ports already used by the Flash helper.
        return new Masks(mask(263, 262, 265, 264, 32), mask(65, 68, 87, 83, 340));
    }

    private int mask(int left, int right, int up, int down, int action) {
        return (held.contains(left) ? 1 : 0) | (held.contains(right) ? 2 : 0)
                | (held.contains(up) ? 4 : 0) | (held.contains(down) ? 8 : 0)
                | (held.contains(action) ? 16 : 0);
    }
}
