// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.gba.client;

import java.util.Objects;
import java.util.UUID;

/** Pure local-item lifetime fence. It is not a cabinet lease or a network authority. */
public final class GbaHandheldGate {
    public static final int INPUT_MASK = 0xDFD;
    private final Object connection, level;
    private final UUID player;
    private final int slot;
    public GbaHandheldGate(Object connection,Object level,UUID player,int slot) {
        this.connection=Objects.requireNonNull(connection);this.level=Objects.requireNonNull(level);
        this.player=Objects.requireNonNull(player);
        if(slot<0||slot>8)throw new IllegalArgumentException("Handheld requires a main-hand hotbar slot");
        this.slot=slot;
    }
    public boolean valid(Object connection,Object level,UUID player,int slot,boolean connected,
                         boolean alive,boolean spectator,boolean sameItemAndComponents,int count) {
        return this.connection==connection&&this.level==level&&this.player.equals(player)&&this.slot==slot
                &&connected&&alive&&!spectator&&sameItemAndComponents&&count==1;
    }
    public static int input(int mask){return mask&INPUT_MASK;}
    /** Holding right click, or duplicate air/block callbacks, must not toggle twice. */
    public static final class UseGate {
        private boolean pressed;
        public boolean press(){if(pressed)return false;pressed=true;return true;}
        public void observe(boolean physicallyDown){if(!physicallyDown)pressed=false;}
    }
}
