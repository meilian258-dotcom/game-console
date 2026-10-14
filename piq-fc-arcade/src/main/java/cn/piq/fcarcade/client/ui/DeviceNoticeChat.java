package cn.piq.fcarcade.client.ui;

import java.util.LinkedHashMap;
import java.util.List;

/** Bounded local-chat presentation; raw diagnostic history is never changed or sent. */
final class DeviceNoticeChat {
    private static final long INTERVAL = 1_000_000_000L;
    private static final long REPEAT_INTERVAL = 10_000_000_000L;
    private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
    private long seen;
    private long sentAt;
    private boolean sent;

    DeviceNotices.Entry poll(List<DeviceNotices.Entry> newestFirst, long now) {
        if (sent && now - sentAt < INTERVAL) return null;
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            var entry = newestFirst.get(i);
            if (entry.id() <= seen) continue;
            seen = entry.id();
            String key = entry.device() + '\n' + entry.summary();
            Long previous = recent.get(key);
            if (previous != null && now - previous < REPEAT_INTERVAL) continue;
            recent.remove(key);
            recent.put(key, now);
            while (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
            sent = true;
            sentAt = now;
            return entry;
        }
        return null;
    }

    void discardThrough(DeviceNotices.Entry last) {
        if (last != null) seen = Math.max(seen, last.id());
        recent.clear();
        sent = false;
    }

    static String line(DeviceNotices.Entry entry) {
        return "[方块电玩 · " + entry.device() + "] " + entry.summary()
                + "（详情：模组设置 → 运行环境 → 复制诊断）";
    }
}
