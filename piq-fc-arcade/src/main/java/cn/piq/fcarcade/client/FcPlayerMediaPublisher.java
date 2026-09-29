package cn.piq.fcarcade.client;

import cn.piq.fcarcade.cabinet.*;
import cn.piq.fcarcade.client.cabinet.WatchMediaStream;
import cn.piq.retro.api.RetroFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded encoder tap of the existing host core, never a second emulator. */
final class FcPlayerMediaPublisher implements ClientNesWorker.MediaTap, AutoCloseable {
    private final ClientArcadeSession owner;
    private final FcMediaFrames frames = new FcMediaFrames();
    private final AtomicReference<RetroFrame> last = new AtomicReference<>();
    private volatile Publication current;
    private long revision = -1;
    private boolean blocked;
    private volatile boolean failed;
    FcPlayerMediaPublisher(ClientArcadeSession owner) { this.owner = owner; }

    void demand(WatchNetwork.HostDemand demand) {
        if (failed || !owner.acceptsMediaDemand(demand.descriptor()) || demand.revision() < revision) return;
        Publication p = current;
        if (demand.revision() == revision) {
            if (p == null || !p.descriptor.equals(demand.descriptor()) || blocked) return;
            if (!demand.needed()) { suspend(); return; }
        } else {
            revision = demand.revision();
            blocked = !demand.needed();
            if (!demand.needed()) { suspend(); return; }
        }
        var client = Minecraft.getInstance().getConnection();
        if (client == null) return;
        if (p == null || p.connection != client.getConnection() || !p.descriptor.equals(demand.descriptor())) {
            dispose();
            try { p = new Publication(demand.descriptor(), client.getConnection()); }
            catch(RuntimeException|LinkageError failure){failed=true;blocked=true;return;}
            current = p;
        }
        if (p.failed) { suspend(); blocked = true; return; }
        p.needed = true;
        p.stream.sending(true);
        p.refreshed = System.nanoTime();
    }
    @Override public boolean enabled() {
        Publication p = current;
        return p != null && p.needed && !p.failed;
    }
    @Override public void failed(Throwable failure){failed=true;Publication p=current;if(p!=null)p.failed=true;}
    /** Core thread: copying/resampling only, no Minecraft/network/codec calls. */
    @Override public void frame(byte[] rgba, float[] mono, int count) {
        Publication p = current;
        if (p == null || !p.needed || p.failed) return;
        try {
            RetroFrame frame = frames.copy(rgba, mono, count);
            if (p != current || !p.needed) return;
            last.set(FcMediaFrames.silent(frame));
            p.lastFrame = System.nanoTime();
            p.stream.offer(frame);
        } catch (RuntimeException | LinkageError failure) { p.failed = true; }
    }
    void tick() {
        Publication p = current;
        if (p == null) return;
        var client = Minecraft.getInstance().getConnection();
        if (client == null || client.getConnection() != p.connection || !p.connection.isConnected()
                || !owner.acceptsMediaDemand(p.descriptor)) { close(); return; }
        if (p.failed || p.stream.error() != null) { p.failed = true; suspend(); blocked = true; return; }
        long now = System.nanoTime();
        if (now - p.refreshed > 5_000_000_000L) { suspend(); return; }
        if (!p.needed) return;
        try {
            if (now - p.lastFrame >= 1_000_000_000L) {
                RetroFrame still = last.get();
                if (still != null) { p.stream.offer(still); p.lastFrame = now; }
            }
            for (int i = 0; i < 8; i++) {
                var batch = p.stream.pollOutbound();
                if (batch == null) break;
                boolean admitted = CabinetMediaSender.watchServerbound(p.connection, batch);
                p.stream.transportResult(batch, admitted);
                if (!admitted) break;
            }
        } catch (RuntimeException | LinkageError failure) { p.failed = true; suspend(); blocked = true; }
    }
    private void suspend() {
        Publication p = current;
        if (p != null) { p.needed = false; p.stream.sending(false); }
        last.set(null);
    }
    String error(){Publication p=current;return failed||p!=null&&p.failed?"FC主持音画发布失败；本机已停止运行，请正常关机后重开。":null;}
    private void dispose() {
        Publication p = current; current = null;
        if (p != null) p.stream.close();
        last.set(null);
    }
    @Override public void close() { dispose(); blocked = true; }
    private static final class Publication {
        final WatchDescriptor descriptor;
        final Connection connection;
        final WatchMediaStream stream;
        volatile boolean needed, failed;
        volatile long refreshed = System.nanoTime(), lastFrame = System.nanoTime();
        Publication(WatchDescriptor descriptor, Connection connection) {
            this.descriptor = descriptor; this.connection = connection;
            stream = new WatchMediaStream(descriptor.source(), descriptor.hostLease(), true);
        }
    }
}
