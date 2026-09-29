// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

import java.util.Objects;

/** Immutable low-12-bit SFC joypad state. */
public record SfcControllerState(int mask) {
    public static final int VALID_MASK = (1 << SfcButton.values().length) - 1;
    public static final SfcControllerState NONE = new SfcControllerState(0);

    public SfcControllerState {
        if ((mask & ~VALID_MASK) != 0) {
            throw new IllegalArgumentException("Unsupported SFC input bits: 0x" + Integer.toHexString(mask));
        }
    }

    public static SfcControllerState of(SfcButton... buttons) {
        Objects.requireNonNull(buttons, "buttons");
        int mask = 0;
        for (SfcButton button : buttons) {
            mask |= Objects.requireNonNull(button, "button").mask();
        }
        return new SfcControllerState(mask);
    }

    public boolean pressed(SfcButton button) {
        return (mask & Objects.requireNonNull(button, "button").mask()) != 0;
    }

    public SfcControllerState with(SfcButton button, boolean pressed) {
        int buttonMask = Objects.requireNonNull(button, "button").mask();
        return new SfcControllerState(pressed ? mask | buttonMask : mask & ~buttonMask);
    }
}

