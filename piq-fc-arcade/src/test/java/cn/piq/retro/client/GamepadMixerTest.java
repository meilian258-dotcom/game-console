package cn.piq.retro.client;

import cn.piq.retro.input.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static cn.piq.retro.input.RetroButtons.*;
import static cn.piq.retro.input.GamepadState.Control.*;
import static org.junit.jupiter.api.Assertions.*;

class GamepadMixerTest {
    private static final GamepadState.DeviceId PAD = new GamepadState.DeviceId("0:guid", 1);
    private static GamepadConfig config() { return GamepadConfig.defaults().enabled(true).device(PAD.id()); }
    private static GamepadState pad(GamepadState.Control... controls) { return new GamepadState(PAD, true, GamepadState.buttons(controls), 0, 0, 0, 0, 0, 0); }

    @Test void disabledInactiveOrMissingDeviceNeverChangesKeyboardIncludingSocd() {
        var mixer = new GamepadMixer(); Object owner = new Object();
        mixer.configure(GamepadConfig.defaults().enabled(false));
        for (int input = 0; input < 4096; input++) {
            assertEquals(input, mixer.mix(owner, "SFC", input, true, pad(SOUTH)));
            assertEquals(input, mixer.mix(owner, "ARCADE", input, false, null));
        }
        mixer.configure(config());
        for (int input = 0; input < 4096; input++) {
            assertEquals(input, mixer.mix(owner, "SFC", input, true, null));
            assertEquals(input, mixer.mix(owner, "ARCADE", input, false, pad(SOUTH)));
        }
        for (int input = 0; input < 256; input++) assertEquals(input, mixer.mix(owner, "NES", input, false, pad(SOUTH)));
    }

    @Test void defaultAutomaticSelectionRequiresNeutralAndRearmsForDifferentDevice() {
        var mixer = new GamepadMixer(); Object owner = new Object();
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(B, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        var second = new GamepadState.DeviceId("1:second", 1);
        var held = new GamepadState(second, true, SOUTH.mask(), 0, 0, 0, 0, 0, 0);
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, held));
        mixer.mix(owner, "SFC", 0, true, GamepadState.neutral(second));
        assertEquals(B, mixer.mix(owner, "SFC", 0, true, held));
        assertEquals(0, mixer.mix(owner, "SFC", 0, false, held));
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, held));
    }

    @Test void heldKeyboardIsImmediateButHeldGamepadMustFirstRelease() {
        var mixer = new GamepadMixer(); mixer.configure(config()); Object owner = new Object();
        assertEquals(A, mixer.mix(owner, "SFC", A, true, pad(SOUTH)));
        assertFalse(mixer.armed(owner));
        assertEquals(A, mixer.mix(owner, "SFC", A, true, pad()));
        assertTrue(mixer.armed(owner));
        assertEquals(A | B, mixer.mix(owner, "SFC", A, true, pad(SOUTH)));
        assertEquals(B, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        assertEquals(B, mixer.mix(owner, "SFC", B, true, pad()));
    }

    @Test void nesConversionNeverRenamesThePhysicalSouthButtonAsNesA() {
        var mixer = new GamepadMixer(); mixer.configure(config()); Object owner = new Object();
        mixer.mix(owner, "NES", 0, true, pad());
        assertEquals(2, mixer.mix(owner, "NES", 0, true, pad(SOUTH)));
        assertEquals(3, mixer.mix(owner, "NES", 1, true, pad(SOUTH)));
        assertEquals(1, mixer.mix(owner, "NES", 0, true, pad(EAST)));
        assertEquals(0, mixer.mix(owner, "NES", 0, true, pad(NORTH)));
    }

    @Test void pauseAndConfigureDoNotGiveAnotherOwnerThePhysicalController() {
        var mixer = new GamepadMixer(); mixer.configure(config()); Object owner = new String("same"), other = new String("same");
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(B, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        mixer.pause(owner);
        assertEquals(A, mixer.mix(other, "SFC", A, true, pad(SOUTH)));
        mixer.configure(config().deadzone(.4f, .3f));
        assertEquals(A, mixer.mix(other, "SFC", A, true, pad(SOUTH)));
        mixer.release(other); assertEquals(A, mixer.mix(other, "SFC", A, true, pad(SOUTH)));
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(B, mixer.mix(owner, "SFC", 0, true, pad(SOUTH)));
        mixer.release(owner);
        assertEquals(0, mixer.mix(other, "SFC", 0, true, pad(SOUTH)));
        mixer.mix(other, "SFC", 0, true, pad());
        assertEquals(B, mixer.mix(other, "SFC", 0, true, pad(SOUTH)));
    }

    @Test void disconnectWrongDeviceAndNewGenerationCannotResumeHeldInput() {
        var mixer = new GamepadMixer(); mixer.configure(config()); Object owner = new Object();
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(A | B, mixer.mix(owner, "SFC", A, true, pad(SOUTH)));
        assertEquals(A, mixer.mix(owner, "SFC", A, true, GamepadState.disconnected(PAD)));
        assertEquals(A, mixer.mix(owner, "SFC", A, true, pad(SOUTH)));
        var other = new GamepadState(new GamepadState.DeviceId("1:other", 1), true, SOUTH.mask(), 0, 0, 0, 0, 0, 0);
        assertEquals(A, mixer.mix(owner, "SFC", A, true, other));
        var newer = new GamepadState(new GamepadState.DeviceId(PAD.id(), 2), true, SOUTH.mask(), 0, 0, 0, 0, 0, 0);
        assertEquals(A, mixer.mix(owner, "SFC", A, true, newer));
        mixer.mix(owner, "SFC", A, true, GamepadState.neutral(newer.device()));
        assertEquals(A | B, mixer.mix(owner, "SFC", A, true, newer));
    }

    @Test void profileChangesOnlyTheSelectedSystemAndRequireNeutral() {
        var config = config().profile("SFC", InputProfile.defaults().with(Button.B, Set.of(NORTH)));
        var mixer = new GamepadMixer(); mixer.configure(config); Object owner = new Object();
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(B | X, mixer.mix(owner, "SFC", 0, true, pad(NORTH)));
        assertEquals(0, mixer.mix(owner, "ARCADE", 0, true, pad(NORTH)));
        mixer.mix(owner, "ARCADE", 0, true, pad());
        assertEquals(X, mixer.mix(owner, "ARCADE", 0, true, pad(NORTH)));
    }

    @Test void configDeadzoneIsUsedAndRequiresNeutralAfterSave() {
        var mixer = new GamepadMixer(); mixer.configure(config().deadzone(.6f, .4f)); Object owner = new Object();
        mixer.mix(owner, "SFC", 0, true, pad());
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, new GamepadState(PAD, true, 0, .5f, 0, 0, 0, 0, 0)));
        assertEquals(RIGHT, mixer.mix(owner, "SFC", 0, true, new GamepadState(PAD, true, 0, .65f, 0, 0, 0, 0, 0)));
        assertEquals(RIGHT, mixer.mix(owner, "SFC", 0, true, new GamepadState(PAD, true, 0, .5f, 0, 0, 0, 0, 0)));
        assertEquals(0, mixer.mix(owner, "SFC", 0, true, new GamepadState(PAD, true, 0, .4f, 0, 0, 0, 0, 0)));
    }

    @Test void fastKeyboardAndGamepadUpdatesReturnImmediatelyWithoutAnotherFrameQueue() {
        var mixer = new GamepadMixer(); mixer.configure(config()); Object owner = new Object();
        mixer.mix(owner, "SFC", 0, true, pad());
        for (int i = 0; i < 500; i++) {
            assertEquals(A | B, mixer.mix(owner, "SFC", A, true, pad(SOUTH)));
            assertEquals(0, mixer.mix(owner, "SFC", 0, true, pad()));
        }
    }
}
