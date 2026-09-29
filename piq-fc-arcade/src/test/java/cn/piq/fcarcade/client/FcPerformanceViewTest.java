package cn.piq.fcarcade.client;

import cn.piq.fcarcade.client.performance.FramePerformance;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcPerformanceViewTest {
    private static final NetworkDiagnosticsView.Device DEVICE=new NetworkDiagnosticsView.Device("FC @ 0, 0, 0",
            NetworkDiagnosticsView.Mode.LOCAL_INPUT,NetworkDiagnosticsView.Role.CONTROLLING,0);
    @Test void remoteCoreDoesNotFabricateZeroFps() {
        String text=String.join("\n",FcPerformanceView.lines(new FcPerformanceView.Entry("1",DEVICE,"接收音画",null,0,-1,-1,-1)));
        assertTrue(text.contains("远端核心性能未上报"));assertFalse(text.contains("0 FPS"));assertFalse(text.contains("平均"));
    }
    @Test void localWindowIsBoundedAndNamesQueueNotNetworkLatency() {
        var lines=FcPerformanceView.lines(new FcPerformanceView.Entry("1",DEVICE,"运行中",new FramePerformance.Sample(true,true,60,2.25,8),100,3,2,33.33));
        assertEquals(7,lines.size());assertTrue(lines.get(3).contains("60 FPS"));assertTrue(lines.get(4).contains("2.25"));
        assertTrue(lines.get(5).contains("待模拟：3 帧"));assertTrue(lines.get(6).contains("33.3 ms"));
    }
    @Test void emptyAndWarmupAreExplicit() {
        assertTrue(FcPerformanceView.lines(null).getFirst().contains("没有活动"));
        var lines=FcPerformanceView.lines(new FcPerformanceView.Entry("1",DEVICE,"加载中",new FramePerformance.Sample(true,false,0,0,0),0,-1,0,-1));
        assertTrue(lines.get(3).contains("采样中"));assertFalse(lines.get(5).contains("待模拟"));
    }
    @Test void diagnosticSourcesDoNotCreateCoreConsumeQueuesOrRequestAuthority() throws Exception {
        for(String name:new String[]{"FcPerformanceClient","FcPerformanceScreen","FcPerformanceView"}){
            String s=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+name+".java"));
            for(String forbidden:new String[]{"runFrame(","pollFrame(","drain(","NesCores.create(","HomeSyncNetwork.request(","PacketDistributor","Files.write","loadPersistentState("})
                assertFalse(s.contains(forbidden),name+" must not "+forbidden);
        }
    }
    @Test void newFooterButtonsFitSmallestSupportedLayout() {
        for(int width:new int[]{320,400,854,1920}){
            var l=HomeSyncSettingsLayout.fit(width,240);int w=(l.width()-44)/5;
            assertTrue(w>=48);assertTrue(34+4*w+w<=l.width()-10);
            assertTrue(148+20<174);assertTrue(45+7*12+9<148);
        }
    }
}
