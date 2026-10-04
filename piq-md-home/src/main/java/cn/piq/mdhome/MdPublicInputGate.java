// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;
import java.util.UUID;

/** Per-loan monotonic/rate gate. Physical possession remains checked by the server each call. */
public final class MdPublicInputGate {
    public enum Result { REJECT, RATE_LIMIT, ACCEPT }
    private final Object connection;private final UUID loan;private final long wire;private final int port;
    private long sequence=-1,window;private int count;
    public MdPublicInputGate(Object connection,UUID loan,long wire,int port){this.connection=java.util.Objects.requireNonNull(connection);this.loan=java.util.Objects.requireNonNull(loan);if(wire<=0||port<0||port>1)throw new IllegalArgumentException("MD input identity");this.wire=wire;this.port=port;}
    public Result accept(Object connection,long wire,int port,UUID loan,long sequence,int mask,long now){
        if(connection!=this.connection||wire!=this.wire||port!=this.port||!this.loan.equals(loan)||sequence<0||sequence<=this.sequence||(mask&~4095)!=0)return Result.REJECT;
        if(now-window>=1_000_000_000L||now<window){window=now;count=0;}
        // Consume sequence even on rate failure: packets refused in a burst cannot be replayed later.
        this.sequence=sequence;return ++count>180?Result.RATE_LIMIT:Result.ACCEPT;
    }
}
