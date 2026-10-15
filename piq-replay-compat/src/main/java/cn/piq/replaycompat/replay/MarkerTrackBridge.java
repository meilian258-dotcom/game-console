package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.api.ScreenFrame;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Mirrors only the documented 0.3 cut/split markers and binds to actual output saves, never file names. */
public final class MarkerTrackBridge {
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private MarkerTrackBridge() {}
    public static void begin() { CURRENT.remove(); }
    public static void input(ReplayFile source) {
        try {
            TrackIndex index = TrackFiles.index(source);
            if (index.frames().isEmpty()) {
                Map<String, byte[]> information = new LinkedHashMap<>();
                synchronized (source) {
                    for (String entry : List.of(TrackFiles.WARNING_ENTRY, TrackFiles.DIAGNOSTIC_ENTRY)) {
                        var value = source.get(entry);
                        if (value.isPresent()) try (InputStream in = value.get()) {
                            byte[] bytes = in.readNBytes(4097);
                            if (bytes.length > 4096) throw new IOException("Screen diagnostic byte quota");
                            information.put(entry, bytes);
                        }
                    }
                }
                if (information.isEmpty()) CURRENT.remove();
                else CURRENT.set(new Context(source, information));
                return;
            }
            var markers = new ArrayList<TrackCutPlan.Marker>();
            for (var marker : source.getMarkers().or(Collections.emptySet())) markers.add(new TrackCutPlan.Marker(marker.getTime(), marker.getName()));
            CURRENT.set(new Context(source, index, TrackCutPlan.create(source.getMetaData().getDuration(), markers)));
        } catch (Exception failure) {
            CURRENT.set(new Context(source, failure));
            ReplayBridge.warn("屏幕轨无法安全映射此录像的剪切标记；原始录像和轨道保留在Replay原始备份中。", failure);
        }
    }
    public static void beforeSave(ReplayFile output) {
        Context context = CURRENT.get();
        if (context == null || context.source == output) return;
        try {
            if (context.problem != null) throw new IOException("Unsupported screen-track marker sequence", context.problem);
            context.copy(output, context.output++);
        } catch (Exception | LinkageError failure) {
            String warning = "此剪切/分段录像的屏幕轨处理失败，未伪造或重启模拟器；请保留并使用Replay原始备份核对。";
            ReplayBridge.warn(warning, failure);
            try (OutputStream out = output.write(TrackFiles.WARNING_ENTRY)) { out.write(warning.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException writeFailure) { ReplayBridge.warn("屏幕轨失败提示也未能写入；请检查磁盘空间。", writeFailure); }
        }
    }
    public static void closing(ReplayFile file) { Context context = CURRENT.get(); if (context != null && context.source == file) CURRENT.remove(); }
    public static void end() { CURRENT.remove(); }

    private static final class Context {
        final ReplayFile source;
        final TrackIndex index;
        final TrackCutPlan plan;
        final Exception problem;
        final List<TrackIndex.Ref> ordered;
        final Map<String, byte[]> emptyInformation;
        int output;
        int cachedNumber = -1;
        List<ScreenFrame> cached = List.of();
        Context(ReplayFile source, TrackIndex index, TrackCutPlan plan) {
            this.source = source; this.index = index; this.plan = plan; this.problem = null; this.emptyInformation = null;
            var list = new ArrayList<>(index.frames());
            list.sort(Comparator.comparingLong(TrackIndex.Ref::time).thenComparingInt(TrackIndex.Ref::chunk).thenComparingInt(TrackIndex.Ref::frame));
            this.ordered = List.copyOf(list);
        }
        Context(ReplayFile source, Exception problem) { this.source = source; this.problem = problem; index = null; plan = null; ordered = List.of(); emptyInformation = null; }
        Context(ReplayFile source, Map<String, byte[]> information) {
            this.source = source; problem = null; index = null; plan = null; ordered = List.of(); emptyInformation = Map.copyOf(information);
        }
        ScreenFrame frame(TrackIndex.Ref ref) throws IOException {
            if (cachedNumber != ref.chunk()) { cached = TrackFiles.chunk(source, index, ref.chunk()); cachedNumber = ref.chunk(); }
            return TrackFiles.resolve(cached, ref);
        }
        void copy(ReplayFile destination, int outputIndex) throws IOException {
            // An empty capture has no timestamps to remap. Preserve its bounded reason
            // in every cut/split output instead of turning it into an unexplained old replay.
            if (emptyInformation != null) {
                synchronized (destination) {
                    for (var entry : emptyInformation.entrySet()) {
                        try (OutputStream out = destination.write(entry.getKey())) { out.write(entry.getValue()); }
                    }
                }
                return;
            }
            long duration = destination.getMetaData().getDuration();
            var writer = new TrackFiles.Writer(destination);
            var batch = new ArrayList<ScreenFrame>(8);
            var expiries = new ArrayList<Long>(8);
            var visible = new HashMap<String, String>();
            for (var segment : plan.output(outputIndex).segments()) {
                if (segment.outputFrom() > duration) continue;
                var seeds = index.select(segment.sourceFrom() - 1).stream().filter(ref -> ref.expiresAt() > segment.sourceFrom()).toList();
                Set<String> seeded = new HashSet<>();
                for (var seed : seeds) seeded.add(seed.source());
                // A cut may contain a close or a TTL expiry: explicitly clear the old output state.
                for (var old : new ArrayList<>(visible.entrySet())) {
                    if (!seeded.contains(old.getKey())) {
                        append(writer, batch, expiries, ScreenFrame.closed(old.getKey(), old.getValue(), segment.outputFrom()), segment.outputFrom());
                        visible.remove(old.getKey());
                    }
                }
                for (var seed : seeds) {
                    ScreenFrame image = frame(seed).at(segment.outputFrom());
                    append(writer, batch, expiries, image, segment.outputFrom() + seed.expiresAt() - segment.sourceFrom());
                    visible.put(image.source(), image.dimension());
                }
                for (var ref : ordered) {
                    if (ref.time() < segment.sourceFrom()) continue;
                    if (ref.time() >= segment.sourceTo()) break;
                    long time = segment.outputFrom() + ref.time() - segment.sourceFrom();
                    if (time <= duration) {
                        ScreenFrame image = frame(ref).at(time);
                        append(writer, batch, expiries, image, time + ref.expiresAt() - ref.time());
                        if (image.closed()) visible.remove(image.source()); else visible.put(image.source(), image.dimension());
                    }
                }
            }
            if (!batch.isEmpty()) writer.append(batch, expiries);
            writer.finish(); cached = List.of(); cachedNumber = -1;
        }
        void append(TrackFiles.Writer writer, List<ScreenFrame> batch, List<Long> expiries, ScreenFrame frame, long expiresAt) throws IOException {
            batch.add(frame); expiries.add(expiresAt);
            if (batch.size() == 8) { writer.append(batch, expiries); batch.clear(); expiries.clear(); }
        }
    }
}
