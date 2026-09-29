package cn.piq.fcarcade.client.cabinet;

import cn.piq.fcarcade.cabinet.CabinetMediaPacket;
import cn.piq.retro.api.RetroFrame;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded media-only bridge for addons. It has no seat, ROM, input or network authority. */
public final class WatchMediaStream implements AutoCloseable {
    private final CabinetMediaStream stream;
    private volatile boolean closed;
    public WatchMediaStream(UUID source, UUID hostLease, boolean sender) {
        stream = new CabinetMediaStream(Objects.requireNonNull(source), Objects.requireNonNull(hostLease), sender);
    }
    public void sending(boolean enabled) { if (!closed) stream.sending(enabled); }
    /** Ownership of frame arrays transfers to the media worker; never mutate them afterwards. */
    public void offer(RetroFrame frame) { if (!closed) stream.offer(Objects.requireNonNull(frame)); }
    public void accept(CabinetMediaPacket part) { if (!closed) stream.accept(Objects.requireNonNull(part)); }
    public List<CabinetMediaPacket> pollOutbound() { return closed ? null : stream.pollOutbound(); }
    public void transportResult(List<CabinetMediaPacket> batch,boolean admitted) { if (!closed) stream.transportResult(batch,admitted); }
    public RetroFrame pollVideo() { return closed ? null : stream.pollVideo(); }
    public short[] pollAudio() { return closed ? null : stream.pollAudio(); }
    public String error() { return closed ? null : stream.error(); }
    @Override public void close() { closed = true; stream.close(); }
}
