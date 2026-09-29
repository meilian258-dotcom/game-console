package cn.piq.retro.input;

/** Canonical libretro/SFC twelve-bit order, shared by existing cabinet backends. */
public final class RetroButtons {
    public static final int B = 1, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3,
            UP = 1 << 4, DOWN = 1 << 5, LEFT = 1 << 6, RIGHT = 1 << 7,
            A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11, MASK = 0xfff;

    public enum Button {
        B, Y, SELECT, START, UP, DOWN, LEFT, RIGHT, A, X, L, R;
        public int mask() { return 1 << ordinal(); }
    }

    private RetroButtons() { }

    public static int requireValid(int buttons) {
        if ((buttons & ~MASK) != 0) throw new IllegalArgumentException("Not a logical twelve-bit mask");
        return buttons;
    }

    /** Simultaneous opposites are neutral. Apply after source merging, not to stored sources. */
    public static int neutralizeOpposites(int buttons) {
        requireValid(buttons);
        if ((buttons & (UP | DOWN)) == (UP | DOWN)) buttons &= ~(UP | DOWN);
        if ((buttons & (LEFT | RIGHT)) == (LEFT | RIGHT)) buttons &= ~(LEFT | RIGHT);
        return buttons;
    }
}
