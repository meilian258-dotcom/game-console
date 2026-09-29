package cn.piq.fcarcade.client;

import cn.piq.fcarcade.network.ModTrafficCounter;
import cn.piq.fcarcade.network.TrafficCategory;
import java.util.*;

/** Pure presentation/selection. Modes describe admitted sessions, never idle device preferences. */
public final class NetworkDiagnosticsView {
    public enum Mode {
        NETPLAY("RetroArch Netplay"), LOCAL_INPUT("本地输入同步"), PLAYER_MEDIA("玩家音画串流"), SERVER_MEDIA("服务端托管音画"),
        LEGACY_RELAY("旧FC输入转发"), WATCH_MEDIA("旁观音画（主机模式未下发）"), LOCAL_ONLY("单机运行"), PRIVATE_LOCAL("私人模式（游戏数据仅本机）"), WATCH_NETPLAY("Netplay 本地旁观");
        private final String label;Mode(String label){this.label=label;}public String label(){return label;}
    }
    public enum Role {
        CONTROLLING("正在控制"), SEATED("持有席位"), WATCHING("正在旁观"), COMPUTING("后台执行主机");
        private final String label;Role(String label){this.label=label;}public String label(){return label;}
    }
    public record Device(String name,Mode mode,Role role,double distanceSquared) {
        public Device { Objects.requireNonNull(name);Objects.requireNonNull(mode);Objects.requireNonNull(role);
            if(name.isBlank()||name.length()>96||!Double.isFinite(distanceSquared)||distanceSquared<0)throw new IllegalArgumentException("Diagnostic device"); }
    }
    private NetworkDiagnosticsView() {}
    public static Device choose(List<Device> devices) {
        return devices.stream().filter(Objects::nonNull).min(Comparator.comparing(Device::role)
                .thenComparingDouble(Device::distanceSquared).thenComparing(Device::name).thenComparing(Device::mode)).orElse(null);
    }
    public static String bytes(long bytes) {
        if(bytes<0)throw new IllegalArgumentException("Negative bytes");
        if(bytes<1024)return bytes+" B";
        double value=bytes;String[] units={"KiB","MiB","GiB","TiB","PiB","EiB"};int unit=0;
        value/=1024;while(value>=1024&&unit<units.length-1){value/=1024;unit++;}
        return String.format(Locale.ROOT,"%.2f %s",value,units[unit]);
    }
    /** Eight lines at most so both the 224px settings page and optional HUD remain bounded. */
    public static List<String> lines(ModTrafficCounter.Sample sample,boolean memory,List<Device> devices) {
        var lines=new ArrayList<String>();var selected=choose(devices);
        lines.add(selected==null?"当前模式：无活动设备":"当前模式："+selected.mode().label());
        lines.add(selected==null?"设备：没有控制或旁观会话":"设备："+selected.name()+" · "+selected.role().label()
                +(devices.size()>1?"（另有 "+(devices.size()-1)+" 个会话）":""));
        lines.add(memory?"模组载荷 ↑ 0 KiB/s  ↓ 0 KiB/s（同机内部）":String.format(Locale.ROOT,
                "模组载荷 ↑ %.1f KiB/s  ↓ %.1f KiB/s",sample.uploadBytesPerSecond()/1024,sample.downloadBytesPerSecond()/1024));
        lines.add("本次计数 ↑ "+bytes(sample.uploadedBytes())+"  ↓ "+bytes(sample.downloadedBytes())+" · 合计 "+bytes(sample.totalBytes()));
        for(var category:TrafficCategory.values()){
            var c=sample.categories().getOrDefault(category,new ModTrafficCounter.CategoryRate(0,0,0,0));
            String line=String.format(Locale.ROOT,"%s ↑ %.1f ↓ %.1f KiB/s · 累计 %s",category.label(),c.up()/1024,c.down()/1024,bytes(c.totalBytes()));
            if(category==TrafficCategory.MEDIA&&!sample.streams().isEmpty())line+=String.format(Locale.ROOT," · 视频 %.1f FPS / %d 路",
                    sample.streams().stream().mapToDouble(ModTrafficCounter.StreamRate::fps).sum(),sample.streams().size());
            if(category==TrafficCategory.OTHER&&memory)line="同机内部连接不经网络，不计入流量。";
            lines.add(line);
        }
        return List.copyOf(lines);
    }
}
