// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfcarcade.core;

/** Stable button ordering used by the Java/native core boundary. */
public enum SfcButton {
    B(0),
    Y(1),
    SELECT(2),
    START(3),
    UP(4),
    DOWN(5),
    LEFT(6),
    RIGHT(7),
    A(8),
    X(9),
    L(10),
    R(11);

    private final int bit;

    SfcButton(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public int mask() {
        return 1 << bit;
    }
}

