// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.client;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

/** Wall-clock measurements, shared by the client thread and its own core worker. */
final class SfcStartupProgress {
    enum Stage {
        CACHE("检查本地 ROM 缓存"),DOWNLOAD("从服务器接收 ROM"),CACHE_WRITE("校验并缓存 ROM"),
        HARDWARE("等待主机与电视数据同步"),PREVIOUS_CORE("等待已有 SFC 核心退出"),
        CORE_CREATE("初始化 SFC 模拟核心"),ROM_LOAD("加载 ROM"),INITIAL_STATE("校验初始模拟状态"),
        AUDIO("初始化声音"),READY("本机已就绪，等待服务器开局"),RUNNING("游戏已启动");
        final String label;Stage(String label){this.label=label;}
    }
    private final LongSupplier clock;
    private final long started;
    private final EnumMap<Stage,Long> elapsed=new EnumMap<>(Stage.class);
    private Stage stage=Stage.CACHE;
    private long changed;
    private int percent=-1;
    SfcStartupProgress(){this(System::nanoTime);}
    SfcStartupProgress(LongSupplier clock){this.clock=clock;started=changed=clock.getAsLong();}
    synchronized void enter(Stage next){
        if(stage==next||stage==Stage.RUNNING)return;
        long now=clock.getAsLong();elapsed.merge(stage,Math.max(0,now-changed),Long::sum);
        stage=next;changed=now;percent=-1;
    }
    synchronized void download(int received,int total){if(stage==Stage.DOWNLOAD&&total>0)percent=(int)Math.min(100,100L*received/total);}
    synchronized Stage stage(){return stage;}
    synchronized String message(){
        long now=clock.getAsLong();
        return "SFC · "+stage.label+(percent>=0?" "+percent+"%":"")+String.format(Locale.ROOT,"（本阶段 %.1f 秒 / 总 %.1f 秒）",Math.max(0,now-changed)/1e9,Math.max(0,now-started)/1e9);
    }
    synchronized Map<Stage,Long> timings(){var copy=new EnumMap<>(elapsed);copy.merge(stage,Math.max(0,clock.getAsLong()-changed),Long::sum);return Map.copyOf(copy);}
}
