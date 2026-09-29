package cn.piq.fcarcade.client.ui;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/** Client diagnostics only: no world, input, save, download or connection mutations. */
public final class DeviceNotices {
    private static final System.Logger LOGGER = System.getLogger("PIQ-DeviceNotices");
    private static final ArrayDeque<Entry> ENTRIES = new ArrayDeque<>();
    private static long sequence;
    private static long lastAt;
    private static String lastKey = "";
    public record Entry(long id, Instant time, String device, String summary, String detail) {}
    private DeviceNotices() {}

    public static Entry record(String device, String detail) { return record(device, detail, null); }
    public static synchronized Entry record(String device, String detail, Throwable failure) {
        String source = device == null || device.isBlank() ? "模拟器" : device;
        String context = detail == null || detail.isBlank() ? "操作未完成" : detail;
        StringWriter trace = new StringWriter();
        if (failure != null) failure.printStackTrace(new PrintWriter(trace));
        String full = context + (trace.getBuffer().isEmpty() ? "" : "\n" + trace);
        String key = source + '\n' + full;
        long now = System.nanoTime();
        if (key.equals(lastKey) && now - lastAt < 2_000_000_000L && !ENTRIES.isEmpty()) return ENTRIES.getFirst();
        lastKey = key; lastAt = now;
        // The log deliberately gets the complete detail and original Throwable, never a UI-truncated message.
        if (failure == null) LOGGER.log(System.Logger.Level.WARNING,"[" + source + "] " + context);
        else LOGGER.log(System.Logger.Level.WARNING,"[" + source + "] " + context,failure);
        // The actual failure has priority over incidental ROM/DLL names in a normal startup log.
        String summary=failure==null?summarize(full):summarize(failure.toString());
        if(summary.equals("操作未完成，请查看运行环境"))summary=summarize(full);
        Entry entry = new Entry(++sequence, Instant.now(), source, summary,
                full.length() <= 16_384 ? full : full.substring(0, 16_384) + "\n[显示已截断；完整记录见客户端日志]");
        ENTRIES.addFirst(entry);
        while (ENTRIES.size() > 32) ENTRIES.removeLast();
        return entry;
    }
    public static synchronized List<Entry> snapshot() { return List.copyOf(ENTRIES); }
    public static synchronized Entry last() { return ENTRIES.peekFirst(); }
    public static synchronized void clear() { ENTRIES.clear(); lastKey = ""; lastAt = 0; }

    /** Stable, path-free summaries. Detailed paths/core errors remain available in the diagnostics page/log. */
    public static String summarize(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (cn.piq.retro.storage.RuntimeWorkspace.diskFull(value)) return "磁盘空间不足，请清理运行目录所在磁盘";
        if(value.contains("netplay")){
            if(contains(value,"积压","顺序异常"))return "联机数据积压或顺序异常，请重新加入";
            if(contains(value,"连接已结束","已结束"))return "本局联机连接已结束";
            if(contains(value,"协议不匹配","音画边界异常"))return "Netplay 组件不匹配，请核对配套版本";
            if(contains(value,"rom 校验失败"))return "游戏文件校验失败，请核对服务器游戏";
            if(contains(value,"启动退出","核心输出已结束"))return "Netplay 核心已退出，请复制诊断";
            if(contains(value,"timeout","timed out","超时"))return "Netplay 连接或同步超时，请重新加入";
            if(value.contains("仅支持 windows x64"))return "Netplay 需要 Windows x64 客户端";
        }
        if (value.contains("fc native worker") || value.contains("fc libretro failed")) return "FC 核心运行失败，请复制诊断";
        if (value.contains("neogeo.zip") && contains(value, "missing", "not found", "nosuchfile", "缺", "找不到", "不存在", "无法读取")) return "缺少 Neo Geo BIOS，请检查 neogeo.zip";
        if (contains(value, "accessdenied", "access denied", "permission", "权限", "无权", "拒绝访问")) return "没有操作或文件访问权限";
        if (contains(value, "timeout", "timed out", "超时")) return contains(value, "sync", "同步", "传输") ? "同步超时，请检查连接后重试" : "准备超时，请稍后重试";
        if (contains(value, "runtime", "运行库", "运行环境", "unsatisfiedlink", "dll", "vc++", "vcruntime", "msvcp")) return "运行环境不可用，请检查本机依赖";
        if (contains(value, "bios", "固件")) return "BIOS 不可用，请检查所需文件";
        if (contains(value, "rom", "游戏文件", "游戏路径", "游戏包", "卡带")) return "游戏文件或卡带不可用，请检查配置";
        if (contains(value, "存档", "save", "battery", "sram")) return "存档操作未完成，请查看详情";
        if (contains(value, "sync", "同步", "core state", "核心类型")) return "同步未完成，请查看详情";
        if (contains(value, "占用", "请先退出", "请先关闭", "已经持有", "已持有", "正在运行", "席位", "手柄", "控制")) return "暂时无法操作，请查看设备状态";
        if (contains(value, "断开", "连接", "connection", "世界", "维度")) return "设备或连接不可用，请重试";
        if (contains(value, "启动", "开机", "load", "核心", "core")) return "设备未能启动，请查看运行环境";
        return "操作未完成，请查看运行环境";
    }
    private static boolean contains(String value, String... choices) {
        for (String choice : choices) if (value.contains(choice)) return true;
        return false;
    }
}
