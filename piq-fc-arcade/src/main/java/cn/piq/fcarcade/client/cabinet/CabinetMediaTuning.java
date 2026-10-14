package cn.piq.fcarcade.client.cabinet;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Local transmission preference; does not alter emulator speed or server authority. */
public final class CabinetMediaTuning {
    public enum Quality { AUTO("自动 20 / 30 帧"), FPS20("省流 20 帧"), FPS30("优先 30 帧（仍受带宽限制）");
        public final String label;Quality(String label){this.label=label;}}
    private static volatile Quality quality=Quality.AUTO;
    private static final Set<CabinetMediaStream> streams=ConcurrentHashMap.newKeySet();
    private CabinetMediaTuning(){}
    public static Quality quality(){return quality;}
    public static void cycleQuality(){quality=Quality.values()[(quality.ordinal()+1)%Quality.values().length];}
    static void register(CabinetMediaStream stream){streams.add(stream);}
    static void remove(CabinetMediaStream stream){streams.remove(stream);}
    public static List<String> diagnostics(){
        var lines=new ArrayList<String>(CabinetClientBackends.mediaDiagnostics());
        var active=List.copyOf(streams);
        if(active.isEmpty())lines.addAll(List.of("暂无音画流：本机游玩仍可有核心统计", "接收端不测量远端核心速度"));
        else for(var stream:active){lines.addAll(stream.diagnostics());if(lines.size()>=8)break;}
        return List.copyOf(lines.subList(0,Math.min(8,lines.size())));
    }
}
