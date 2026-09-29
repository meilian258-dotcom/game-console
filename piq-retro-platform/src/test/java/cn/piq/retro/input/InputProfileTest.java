package cn.piq.retro.input;

import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Set;

import static cn.piq.retro.input.GamepadState.Control.*;
import static cn.piq.retro.input.RetroButtons.*;
import static org.junit.jupiter.api.Assertions.*;

class InputProfileTest {
    @Test void physicalPositionNotPrintedLetterDeterminesDefaultSfcButton() {
        var profile = InputProfile.defaults();
        assertEquals(B, profile.map(SOUTH.mask())); // Xbox A / PS cross / Nintendo B
        assertEquals(A, profile.map(EAST.mask())); // Xbox B / PS circle / Nintendo A
        assertEquals(Y, profile.map(WEST.mask()));
        assertEquals(X, profile.map(NORTH.mask()));
        assertEquals(2, InputMappings.nes8(profile.map(SOUTH.mask())));
        assertEquals(1, InputMappings.nes8(profile.map(EAST.mask())));
    }

    @Test void defaultsMapDpadAndLeftStickButLeaveExtraHardwareUnbound() {
        var profile = InputProfile.defaults();
        assertEquals(UP, profile.map(DPAD_UP.mask() | LEFT_STICK_UP.mask()));
        assertEquals(DOWN, profile.map(DPAD_DOWN.mask() | LEFT_STICK_DOWN.mask()));
        assertEquals(LEFT, profile.map(DPAD_LEFT.mask() | LEFT_STICK_LEFT.mask()));
        assertEquals(RIGHT, profile.map(DPAD_RIGHT.mask() | LEFT_STICK_RIGHT.mask()));
        assertEquals(L | R, profile.map(LEFT_BUMPER.mask() | RIGHT_BUMPER.mask()));
        assertEquals(RetroButtons.SELECT | RetroButtons.START, profile.map(GamepadState.Control.SELECT.mask() | GamepadState.Control.START.mask()));
        assertEquals(0, profile.map(GUIDE.mask() | LEFT_STICK_CLICK.mask() | RIGHT_STICK_CLICK.mask()
                | LEFT_TRIGGER.mask() | RIGHT_TRIGGER.mask() | RIGHT_STICK_UP.mask()));
    }

    @Test void profilesDeepCopyAndRebindWithoutChangingTheirSource() {
        var controls = new HashSet<>(Set.of(SOUTH));
        var bindings = new EnumMap<Button, Set<GamepadState.Control>>(Button.class);
        bindings.put(Button.B, controls);
        var profile = new InputProfile(bindings);
        controls.clear(); bindings.clear();
        assertEquals(B, profile.map(SOUTH.mask()));
        assertThrows(UnsupportedOperationException.class, () -> profile.bindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> profile.bindings().get(Button.B).clear());
        var changed = profile.with(Button.B, Set.of(LEFT_TRIGGER, NORTH));
        assertEquals(B, profile.map(SOUTH.mask()));
        assertEquals(0, changed.map(SOUTH.mask()));
        assertEquals(B, changed.map(LEFT_TRIGGER.mask()));
        assertEquals(B, changed.map(NORTH.mask()));
        assertEquals(0, changed.with(Button.B, Set.of()).map(NORTH.mask()));
    }

    @Test void sourceMappingDoesNotDestroyOppositesBeforeMerge() {
        assertEquals(UP | DOWN, InputProfile.defaults().map(DPAD_UP.mask() | DPAD_DOWN.mask()));
        assertThrows(IllegalArgumentException.class, () -> InputProfile.defaults().map(-1));
    }
}
