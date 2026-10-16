// PIQ-authored code: additional permission in piq-fc-arcade/MD-ARCADE-LINKING-EXCEPTION.txt.
// PIQ-authored code: additional permission in piq-fc-arcade/SNES9X-LINKING-EXCEPTION.txt.
package cn.piq.fcarcade.cabinet;

/** Server-fed Netplay mailbox admission; never a queue or client-supplied clock. */
final class CabinetInputBudget {
    static final int PACKETS_PER_SECOND=240, PACKET_BURST=128;
    static final int NETPLAY_CHANGES_PER_SECOND=120, NETPLAY_BURST=64;
    private final CabinetGameBudget packets, netplayChanges;

    CabinetInputBudget(long nowNanos) {
        packets=new CabinetGameBudget(PACKETS_PER_SECOND,PACKET_BURST,nowNanos);
        // The server-fed Netplay path uses a latest-mask mailbox, not the MEDIA
        // FIFO. This is bounded abuse protection, not a 120-edge/s FIFO promise.
        netplayChanges=new CabinetGameBudget(NETPLAY_CHANGES_PER_SECOND,NETPLAY_BURST,nowNanos);
    }

    boolean input(boolean changed,long nowNanos) {
        if(!packet(nowNanos))return false;
        if(!changed)return true; // Hold-state refreshes do not spend edge credit.
        if(!netplayChanges.permits(1,nowNanos))return false;
        netplayChanges.charge(1,nowNanos);return true;
    }

    /** Resets spend only transport credit; the caller must release even on denial. */
    boolean reset(long nowNanos) { return packet(nowNanos); }

    private boolean packet(long nowNanos) {
        if(!packets.permits(1,nowNanos))return false;
        packets.charge(1,nowNanos);return true;
    }
}
