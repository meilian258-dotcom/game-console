package cn.piq.fcarcade.client.zapper;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring guardrails; these do not claim a two-client Minecraft visual test. */
class ZapperMediaVisual54Test {
    @Test void corelessQueryUsesOnlyAuthorizedCapturedLocalGunInput() throws Exception {
        String s=section(source("client/ClientArcadeSession"),"boolean visualZapperTrigger(","private boolean isWorldAndBlockValid()");
        for(String gate:new String[]{"!connected()","sessionId!=b.sessionId()","epoch!=b.epoch()","!arcadePos.equals(b.tvPos())",
                "!dimension.location().equals(b.dimension())","!workerReady","awaitingSnapshot"})assertTrue(s.contains(gate),gate);
        assertTrue(s.contains("if(serverHosted()||netplay!=null)return authorizedZapper(b)&&heldZapper()&&inputCapture.armed()"));
        assertTrue(s.contains("ZapperClient.sampledTrigger(b)"));
        assertTrue(s.indexOf("if(serverHosted()||netplay!=null)")<s.indexOf("worker.appliedZapperTrigger()"));
        for(String forbidden:new String[]{"startWorker(","new ClientNesWorker","physicalDown(","GLFW","sendZapperInput"})
            assertFalse(s.contains(forbidden),forbidden);
    }
    @Test void localQueryRequiresExactBindingConnectionFocusAndExistingSample() throws Exception {
        String s=source("client/zapper/ZapperClient");
        String query=section(s,"public static boolean sampledTrigger(","private static boolean physicalDown()");
        assertTrue(query.contains("expected!=null&&binding!=null&&binding.equals(expected)&&authorized()&&focused()"));
        assertTrue(query.contains("INPUT.visualTrigger("));
        assertFalse(query.contains("INPUT.sample("));assertFalse(query.contains("send("));
        String authorization=section(s,"private static boolean authorized()","private static boolean focused()");
        assertTrue(authorization.contains("connectionCurrent()"));
        assertTrue(authorization.contains("ZapperData.matches(mc.player.getMainHandItem(),binding)"));
        assertTrue(authorization.contains("ClientArcadeEvents.authorizedZapper(binding)"));
        String focus=section(s,"private static boolean focused()","/** Read-only local presentation query");
        for(String gate:new String[]{"mc.screen==null","mc.isWindowActive()","!mc.isPaused()","mc.getCameraEntity()==mc.player"})
            assertTrue(focus.contains(gate),gate);
    }
    @Test void mediaVisualRetainsUniqueHeldGunAndLeaseCaptureChecks() throws Exception {
        String s=source("client/ClientArcadeSession");
        String held=section(s,"private boolean heldZapper()","boolean visualZapperTrigger(");
        assertTrue(held.contains("ControllerCapture.unique("));assertTrue(held.contains("b.lease().equals(controllerLease)"));
        String authorized=section(s,"boolean authorizedZapper(","void refreshKeyboardCapture()");
        assertTrue(authorized.contains("workerReady&&zapperControlsEnabled&&keyboardAuthorized()"));
        assertTrue(authorized.contains("binding.lease().equals(controllerLease)"));
        assertTrue(authorized.contains("role.controllerIndex()>=0"));
    }
    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));
    }
    private static String section(String source,String first,String next) {
        int start=source.indexOf(first),end=source.indexOf(next,start);
        assertTrue(start>=0&&end>start);return source.substring(start,end);
    }
}
