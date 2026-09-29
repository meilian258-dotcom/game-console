package cn.piq.fcarcade.core;

public enum NesButton {
    A(1 << 0),
    B(1 << 1),
    SELECT(1 << 2),
    START(1 << 3),
    UP(1 << 4),
    DOWN(1 << 5),
    LEFT(1 << 6),
    RIGHT(1 << 7);

    private final int mask;

    NesButton(int mask) {
        this.mask = mask;
    }

    public int mask() {
        return mask;
    }
}
