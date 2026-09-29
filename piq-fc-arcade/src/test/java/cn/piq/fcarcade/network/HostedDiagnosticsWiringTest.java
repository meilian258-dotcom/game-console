package cn.piq.fcarcade.network;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedDiagnosticsWiringTest {
    private static String source(String path)throws Exception {return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+path+".java"));}
    @Test void blankConsoleCaseNoLongerOpensSettingsButPhysicalButtonsKeepPriority()throws Exception {
        String source=source("home/HomeApplianceService");String method=source.substring(source.indexOf("public static InteractionResult interactConsole"),source.indexOf("public static InteractionResult interactTv"));
        assertTrue(method.contains("tryButton(player,pos,InteractionHand.MAIN_HAND,hit)"));assertFalse(method.contains("HomeSyncSettings.open"));
        assertTrue(source("home/DeviceDebugService").contains("HomeSyncSettings.openDebug"));
    }
    @Test void globalMutationChecksCurrentOperatorExpectedValueAndOnlyChangesVideo()throws Exception {
        String n=source("network/HostedDiagnosticsNetwork");
        assertTrue(n.contains("!server.isSameThread()"));assertTrue(n.contains("getPlayer(player.getUUID())!=player"));
        assertTrue(n.contains("player.hasPermissions(2)"));assertTrue(n.indexOf("LAST.put(player,now)")<n.indexOf("saveVideoFps(request.desired())"));
        assertTrue(n.contains("request.expected()!=current"));assertTrue(n.contains("request.nonce()"));
        assertFalse(n.contains("ServerCoreRegistry.open"));assertFalse(n.contains("offerInput("));
        for(String path:new String[]{"cabinet/HostedCabinetWorker","server/FcHomeHostedRun"}) {
            String worker=source(path);assertTrue(worker.contains("videoPacer.take(now,CabinetHostingConfig.videoFps())"));assertTrue(worker.contains("HostedMediaQueue"));assertTrue(worker.contains("CabinetMediaCodec.encodePcm"));
        }
    }
    @Test void uiDoesNotAcceptStaleResponsesAndStatsDescribeTheirScope()throws Exception {
        String ui=source("client/NetworkDiagnosticsScreen");assertTrue(ui.contains("!value.nonce().equals(nonce)"));assertTrue(ui.contains("connection.isConnected()"));
        assertTrue(ui.contains("压缩前"));assertTrue(ui.contains("非渲染帧"));assertTrue(ui.contains("默认 20 FPS"));assertTrue(ui.contains("state.editable()"));
        assertTrue(ui.contains("不含旧SFC街机core9协议"));
        assertTrue(source("client/NetworkDiagnosticsClient").contains("clientCollector(active,memoryConnection?null:counter)"));
    }
    @Test void hudToggleNeverResetsConnectionCounterAndModesReadOnlyUseAdmittedSessions()throws Exception {
        String client=source("client/NetworkDiagnosticsClient");
        assertTrue(client.contains("toggleHud() { showHud=!showHud; }"));assertTrue(client.contains("counter=SESSION.connect(active)"));
        assertTrue(client.contains("resetCounters(){attach();if(counter!=null)counter.reset();}"));
        assertTrue(source("client/NetworkDiagnosticsScreen").contains("b->NetworkDiagnosticsClient.resetCounters()"));
        assertTrue(client.contains("counter=SESSION.connect(null)"));
        String attach=client.substring(client.indexOf("private static void attach()"),client.indexOf("public static boolean showHud()"));
        assertFalse(attach.contains(".level"));
        String session=source("client/ClientArcadeSession");int start=session.indexOf("NetworkDiagnosticsView.Device diagnosticDevice()");
        String method=session.substring(start,session.indexOf("String startScoreCalibration()",start));
        assertTrue(method.contains("serverHosted()"));assertTrue(method.contains("ArcadeMode.LOCKSTEP"));assertTrue(method.contains("minecraft.getConnection()!=keyboardConnection"));
        assertFalse(method.contains("synchronizationMode()"));
        assertTrue(source("client/cabinet/CabinetClientBackends").contains("switch(room.mode())"));
        assertTrue(source("client/watch/WatchClient").contains("主机同步" )||source("client/watch/WatchClient").contains("no host synchronization mode"));
        assertTrue(source("client/NetworkDiagnosticsScreen").contains("流量监控"));assertTrue(source("client/NetworkDiagnosticsScreen").contains("隐藏悬浮窗/切换维度不清零"));
    }
}
