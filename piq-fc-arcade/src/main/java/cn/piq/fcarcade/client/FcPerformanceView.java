package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.performance.FramePerformance;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Presentation only. Unknown remote measurements must never look like local zeroes. */
final class FcPerformanceView {
    record Entry(String key, NetworkDiagnosticsView.Device device, String state,
                 FramePerformance.Sample sample, long frame, long pendingFrames, int audioBuffers, double audioMs) {}
    private FcPerformanceView() {}
    static List<String> lines(Entry e) {
        if (e == null) return List.of("没有活动的 FC 会话", "先启动 FC 游戏，再打开性能面板。", "本面板不会启动或接管模拟器。");
        var lines = new ArrayList<String>();
        lines.add(e.device.name()); lines.add(e.device.mode().label() + " · " + e.device.role().label());
        lines.add("状态：" + e.state);
        if (e.sample == null) {
            lines.add("本机无模拟数据；远端核心性能未上报。");
            lines.add("接收视频帧率和流量请查看“网络”。");
        } else {
            var s = e.sample;
            lines.add(!s.enabled() ? "采样已关闭" : !s.ready() ? "采样中…（约 1 秒）" : fmt("模拟帧率：%.0f FPS · 目标约 60", s.fps()));
            lines.add(!s.ready() ? "核心单帧：等待采样" : fmt("核心单帧：平均 %.2f / 最大 %.2f ms", s.averageMs(), s.maximumMs()));
            lines.add("已模拟：" + e.frame + " 帧" + (e.pendingFrames < 0 ? "" : " · 待模拟：" + e.pendingFrames + " 帧"));
            if (e.audioBuffers >= 0) lines.add(e.audioMs < 0 ? "输出待取：" + e.audioBuffers + " 包"
                    : fmt("音频待取：%d 包 / %.1f ms", e.audioBuffers, e.audioMs));
        }
        return List.copyOf(lines);
    }
    private static String fmt(String pattern, Object... args) { return String.format(Locale.ROOT, pattern, args); }
}
