package cn.piq.retro.input;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static cn.piq.retro.input.GamepadState.Control;
import static cn.piq.retro.input.RetroButtons.Button;

/** Immutable mapping from physical/filtered controls to logical game buttons. */
public final class InputProfile {
    private final Map<Button, Set<Control>> bindings;

    public InputProfile(Map<Button, Set<Control>> bindings) {
        Objects.requireNonNull(bindings, "bindings");
        EnumMap<Button, Set<Control>> copy = new EnumMap<>(Button.class);
        bindings.forEach((button, controls) -> copy.put(Objects.requireNonNull(button), Set.copyOf(controls)));
        this.bindings = Map.copyOf(copy);
    }

    public static InputProfile defaults() {
        EnumMap<Button, Set<Control>> map = new EnumMap<>(Button.class);
        map.put(Button.B, Set.of(Control.SOUTH));
        map.put(Button.A, Set.of(Control.EAST));
        map.put(Button.Y, Set.of(Control.WEST));
        map.put(Button.X, Set.of(Control.NORTH));
        map.put(Button.L, Set.of(Control.LEFT_BUMPER));
        map.put(Button.R, Set.of(Control.RIGHT_BUMPER));
        map.put(Button.SELECT, Set.of(Control.SELECT));
        map.put(Button.START, Set.of(Control.START));
        map.put(Button.UP, Set.of(Control.DPAD_UP, Control.LEFT_STICK_UP));
        map.put(Button.DOWN, Set.of(Control.DPAD_DOWN, Control.LEFT_STICK_DOWN));
        map.put(Button.LEFT, Set.of(Control.DPAD_LEFT, Control.LEFT_STICK_LEFT));
        map.put(Button.RIGHT, Set.of(Control.DPAD_RIGHT, Control.LEFT_STICK_RIGHT));
        return new InputProfile(map);
    }

    public Map<Button, Set<Control>> bindings() { return bindings; }

    /** Empty controls disables a button. Several controls may bind to the same button. */
    public InputProfile with(Button button, Set<Control> controls) {
        EnumMap<Button, Set<Control>> copy = new EnumMap<>(Button.class);
        copy.putAll(bindings);
        copy.put(Objects.requireNonNull(button), Set.copyOf(controls));
        return new InputProfile(copy);
    }

    public int map(long activeControls) {
        if ((activeControls & ~GamepadState.ALL_CONTROL_MASK) != 0) throw new IllegalArgumentException("Unknown control bits");
        int result = 0;
        for (var binding : bindings.entrySet()) {
            for (Control control : binding.getValue()) {
                if ((activeControls & control.mask()) != 0) {
                    result |= binding.getKey().mask();
                    break;
                }
            }
        }
        return result;
    }
}
