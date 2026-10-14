package cn.piq.replaycompat.replay;

import cn.piq.replaycompat.audio.AudioBridge;
import com.replaymod.replaystudio.replay.ReplayFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/** Audio-only recordings also participate in Replay's actual cut/split output saves. */
public final class AudioMarkerBridge {
    public static final String WARNING_ENTRY = "piq-audio/v1/warning.txt";
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private AudioMarkerBridge() {}
    public static void begin() { CURRENT.remove(); }
    public static void input(ReplayFile source) {
        try {
            synchronized (source) {
                var entry = source.get("piq-audio/v1/index.bin");
                if (!entry.isPresent()) { CURRENT.remove(); return; }
                entry.get().close();
            }
            var markers = new ArrayList<TrackCutPlan.Marker>();
            for (var marker : source.getMarkers().or(Collections.emptySet()))
                markers.add(new TrackCutPlan.Marker(marker.getTime(), marker.getName()));
            CURRENT.set(new Context(source, TrackCutPlan.create(source.getMetaData().getDuration(), markers), null));
        } catch (Exception failure) {
            CURRENT.set(new Context(source, null, failure));
        }
    }
    public static void beforeSave(ReplayFile output) {
        Context context = CURRENT.get();
        if (context == null || context.source == output) return;
        try {
            if (context.failure != null) throw new IOException("Unsupported audio-track marker sequence", context.failure);
            if (!AudioBridge.copySegments(context.source, output, context.plan.output(context.output++)))
                throw new IOException("Audio track copy failed");
        } catch (Exception | LinkageError failure) {
            String warning = "此剪切/分段录像的设备音轨处理失败；请保留并使用Replay原始备份核对。";
            ReplayBridge.warn(warning, failure);
            try (var out = output.write(WARNING_ENTRY)) { out.write(warning.getBytes(StandardCharsets.UTF_8)); }
            catch (IOException writeFailure) { ReplayBridge.warn("音轨失败提示未能写入；请检查磁盘空间。", writeFailure); }
        }
    }
    public static void closing(ReplayFile file) {
        Context context = CURRENT.get();
        if (context != null && context.source == file) CURRENT.remove();
    }
    public static void end() { CURRENT.remove(); }
    private static final class Context {
        final ReplayFile source;
        final TrackCutPlan plan;
        final Exception failure;
        int output;
        Context(ReplayFile source, TrackCutPlan plan, Exception failure) {
            this.source = source; this.plan = plan; this.failure = failure;
        }
    }
}
