package cn.piq.retro.input;

import org.junit.jupiter.api.Test;

import static cn.piq.retro.input.GamepadState.Control.*;
import static org.junit.jupiter.api.Assertions.*;

class GamepadStateTest {
    private static final GamepadState.DeviceId PAD = new GamepadState.DeviceId("slot0:guid", 1);

    @Test void neutralAndDisconnectDoNotCarryKeys() {
        assertEquals(0, GamepadState.neutral(PAD).buttons());
        var state = new GamepadState(PAD, false, GamepadState.buttons(SOUTH), 1, -1, 1, -1, 1, 1);
        assertFalse(state.connected());
        assertEquals(0, state.buttons());
        assertEquals(0, state.leftX()); assertEquals(0, state.leftY());
        assertEquals(0, state.rightX()); assertEquals(0, state.rightY());
        assertEquals(0, state.leftTrigger()); assertEquals(0, state.rightTrigger());
    }

    @Test void deviceGenerationIsPartOfIdentity() {
        assertEquals(PAD, new GamepadState.DeviceId("slot0:guid", 1));
        assertNotEquals(PAD, new GamepadState.DeviceId("slot0:guid", 2));
        assertNotEquals(PAD, new GamepadState.DeviceId("slot1:guid", 1));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState.DeviceId("", 1));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState.DeviceId("guid", -1));
        assertThrows(NullPointerException.class, () -> new GamepadState.DeviceId(null, 0));
    }

    @Test void physicalMasksCannotSmuggleVirtualControls() {
        assertEquals(SOUTH.mask() | START.mask(), GamepadState.buttons(SOUTH, START, SOUTH));
        assertThrows(IllegalArgumentException.class, () -> GamepadState.buttons(LEFT_TRIGGER));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(PAD, true, LEFT_STICK_UP.mask(), 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(PAD, true, -1, 0, 0, 0, 0, 0, 0));
    }

    @Test void rejectsMalformedAxesInsteadOfLatchingAnInvalidDirection() {
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.01f, -1.01f}) {
            assertThrows(IllegalArgumentException.class, () -> new GamepadState(PAD, true, 0, bad, 0, 0, 0, 0, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(PAD, true, 0, 0, 0, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new GamepadState(PAD, true, 0, 0, 0, 0, 0, 0, 1.01f));
    }
}
