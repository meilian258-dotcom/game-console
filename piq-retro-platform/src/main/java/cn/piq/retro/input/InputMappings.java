package cn.piq.retro.input;

import static cn.piq.retro.input.RetroButtons.*;

/** Stateless backend conversions. Existing SFC/arcade cores consume canonical twelve-bit input. */
public final class InputMappings {
    private InputMappings() { }

    /** NES order: A, B, Select, Start, Up, Down, Left, Right. Extra SFC buttons are ignored. */
    public static int nes8(int logical12) {
        requireValid(logical12);
        return (logical12 & 0xfc) | ((logical12 & A) >>> 8) | ((logical12 & B) << 1);
    }

    public static int sfc12(int logical12) { return requireValid(logical12); }
    public static int arcade12(int logical12) { return requireValid(logical12); }

    /**
     * Explicit opt-in layout, NOT the existing arcade core format: U,D,L,R in bits 0..3;
     * actions 1..6 (Y,B,A,X,L,R) in bits 4..9; Start bit 10; Coin/Select bit 11.
     */
    public static int arcade6Explicit(int logical12) {
        requireValid(logical12);
        int result = (logical12 & (UP | DOWN | LEFT | RIGHT)) >>> 4;
        int[] actions = {Y, B, A, X, L, R, START, SELECT};
        for (int i = 0; i < actions.length; i++) if ((logical12 & actions[i]) != 0) result |= 1 << (i + 4);
        return result;
    }
}
