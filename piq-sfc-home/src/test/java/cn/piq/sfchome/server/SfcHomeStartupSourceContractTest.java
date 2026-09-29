package cn.piq.sfchome.server;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SfcHomeStartupSourceContractTest {
    private String source(String path)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/sfchome/"+path+".java"));}
    @Test void airUseOnlyHintsAndBodyReturnSharesGate()throws Exception{
        String s=source("server/SfcHomeServer");
        String air=s.substring(s.indexOf("public static void useController("),s.indexOf("public static InteractionResult useControllerOn("));
        assertTrue(air.contains("st.interactions.allow"));
        assertFalse(air.contains("release("));
        assertFalse(air.contains("returnController("));
        String use=s.substring(s.indexOf("public static InteractionResult useControllerOn("),s.indexOf("private static String returnMessage"));
        int button=use.indexOf("HomeApplianceService.tryButton");
        int gate=use.indexOf("st.interactions.allow");
        int authority=use.indexOf("!validLease(p,lease,true)||lease.stack!=item");
        int firstReturn=use.indexOf("returnController(p.getServer(),lease)");
        assertTrue(button>=0&&button<firstReturn);
        assertTrue(gate>=0&&gate<firstReturn);
        assertTrue(authority>=0&&authority<firstReturn);
        assertTrue(use.contains("if(tv!=null)"));
        int physicalAuthority=use.indexOf("!physicalControllerAllowed(p,lease.console)");
        assertTrue(physicalAuthority>authority&&physicalAuthority<firstReturn);
        String connected=use.substring(use.indexOf("var connection=found.get()"));
        int connectedReturn=connected.indexOf("returnController(p.getServer(),lease)");
        int protectedEvent=connected.indexOf("NeoForge.EVENT_BUS.post");
        int connectedAuthority=connected.indexOf("!authorizedStart(p,lease,connection)");
        int currentConnection=connected.indexOf("!HomeSystems.isCurrent(connection)");
        assertTrue(protectedEvent>=0&&protectedEvent<connectedReturn);
        assertTrue(connectedAuthority>protectedEvent&&connectedAuthority<connectedReturn);
        assertTrue(currentConnection>connectedAuthority&&currentConnection<connectedReturn);
        String helper=s.substring(s.indexOf("private static void returnController("),s.indexOf("private static void tossedController("));
        int currentLease=helper.indexOf("st.leases.get(lease.id)!=lease");
        int release=helper.indexOf("release(server,lease,returnMessage(lease))");
        assertTrue(currentLease>=0&&currentLease<release);
        assertFalse(helper.contains("stop("));
        assertFalse(helper.contains("reset("));
    }
    @Test void powerOnCreatesOnlyHostAndNeverGrantsOrAutomaticallyRetries()throws Exception{String s=source("server/SfcHomeServer");String power=s.substring(s.indexOf("private static boolean powerOn("),s.indexOf("private static void sendRuntime"));assertTrue(power.contains("preflight(p,c,true)"));assertTrue(power.contains("new Host(p),new Lease[2]"));assertFalse(power.contains("grant(p,"));assertTrue(power.contains("relay.grant(p.connection.getConnection(),true)"));assertFalse(power.contains("SfcControllerData.create"));assertFalse(s.contains("start(p,l,false)"));assertFalse(s.contains("feedback(p,\"卡带已插入\");claim"));}
    @Test void readyBarrierRequiresHostAndOnlyExistingRemotePorts()throws Exception{String s=source("server/SfcHomeServer");assertTrue(s.contains("s.clock!=null||s.hostReady==null"));assertTrue(s.contains("s.ports[i]!=null&&!s.ports[i].player.equals(s.host.player)"));assertTrue(s.contains("s.hostReady.initialStateHash().equals(s.ready[i].initialStateHash())"));assertTrue(s.contains("s.health.start(state(server).tick)"));assertTrue(s.contains("运行端加载超时"));}
    @Test void eitherPortDepartureDoesNotStopHostOrResetFrame()throws Exception{String s=source("server/SfcHomeServer");String det=s.substring(s.indexOf("private static void detach("),s.indexOf("private static void release(MinecraftServer"));assertTrue(det.contains("s.ports[lease.port]=null"));assertTrue(det.contains("s.inputs[lease.port]=new SfcInputTimeline()"));assertFalse(det.contains("stop("));assertFalse(det.contains("s.frame="));assertFalse(det.contains("s.clock="));assertTrue(det.contains("!s.host.player.equals(lease.player)"));}
    @Test void resetChangesEpochAndClearsAllOldInputAndJoinState()throws Exception{String s=source("server/SfcHomeServer");String reset=s.substring(s.indexOf("private static void reset("),s.indexOf("private static boolean hostValid"));assertTrue(reset.contains("abortJoin(st,old"));assertTrue(reset.contains("old.epoch+1"));assertTrue(reset.contains("new Host(host)"));assertTrue(reset.contains("old.ports.clone()"));assertTrue(reset.indexOf("Stopped(old.id,old.epoch")<reset.indexOf("sendRuntime(host,next,null)"));}
    @Test void loadingAndInitializationStayOnActualWorker()throws Exception{String s=source("client/SfcPlayback");for(String stage:new String[]{"CORE_CREATE","ROM_LOAD","INITIAL_STATE","AUDIO","READY","RUNNING"})assertTrue(s.contains("SfcStartupProgress.Stage."+stage));assertTrue(s.contains(".start(this::run)"));assertTrue(s.contains("try (SfcExecutionCore core=new SfcExecutionCore())"));assertTrue(s.contains("core.initialize()"));assertTrue(source("client/SfcExecutionCore").contains("core.reset(true)"));assertTrue(s.contains("SfcClientFiles.hash(core.saveState())"));}
    @Test void backgroundHostDoesNotAcquireInputButNewControlDoes()throws Exception{String s=source("client/SfcHomeClient");assertTrue(s.contains("!message.executionHost()&&(ClientArcadeEvents.isControlling()||!CabinetClientOwner.acquire(INPUT_OWNER))"));assertTrue(s.contains("@Override public void control("));assertTrue(s.contains("CONTROL_GATE.mayGrant"));String release=s.substring(s.indexOf("private static void releaseControl()"),s.indexOf("@Override public void editor("));assertTrue(release.contains("CabinetClientOwner.release(INPUT_OWNER)"));assertFalse(release.contains("playback.close()"));assertFalse(release.contains("playback=null"));}
    @Test void legacyFcControlBlocksOnlyControlNotBackgroundExecution()throws Exception{String s=source("client/SfcHomeClient");String tick=s.substring(s.indexOf("public static void tick("),s.indexOf("@SubscribeEvent public static void key("));assertTrue(tick.contains("controlLease!=null&&ClientArcadeEvents.isControlling()"));assertTrue(tick.contains("if(s.executionHost()){releaseControl()"));String authority=s.substring(s.indexOf("private static boolean presentController()"),s.indexOf("static boolean acceptsInput()"));assertTrue(authority.contains("!ClientArcadeEvents.isControlling()"));assertTrue(authority.contains("controlPort>=0&&controlLease!=null"));assertTrue(authority.contains("ownsController(){return presentController()&&playback.started()"));assertTrue(s.contains("return ownsController()&&mc.screen==null"));}
}
