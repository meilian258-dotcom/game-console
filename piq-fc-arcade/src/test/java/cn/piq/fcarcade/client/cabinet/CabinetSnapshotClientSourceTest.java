package cn.piq.fcarcade.client.cabinet;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring checks supplement real worker/mailbox behavior tests, not a Minecraft session test. */
class CabinetSnapshotClientSourceTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade",name+".java"));}
    @Test void oldProviderEntryPointsRetainCompatibleDefaults()throws Exception{
        String backend=source("client/cabinet/CabinetBackend");
        assertTrue(backend.contains("default String syncUnavailableReason(){return unavailableReason();}"));
        assertTrue(backend.contains("Connection connection) throws Exception {return registered;}"));
        assertTrue(backend.contains("Connection connection) throws Exception {return this::openSync;}"));
        assertTrue(backend.contains("default void requestClose() {}"));
        assertTrue(source("cabinet/CabinetSyncCore").contains("default int snapshotIntervalFrames() { return 300; }"));
    }
    @Test void factoryPreparationRunsBeforeWorkerAndRevalidatesEveryCapturedIdentity()throws Exception{
        String s=source("client/cabinet/CabinetClientBackends");s=s.substring(s.indexOf("private static void startSynchronous"));
        int prepare=s.indexOf("provider.prepareSyncFactory"),worker=s.indexOf("new CabinetSyncWorker");assertTrue(prepare>=0&&worker>prepare);
        String guard=s.substring(prepare,worker);
        for(String check:new String[]{"launch!=request","room!=assignment","sessionConnection!=preparedConnection","generation!=preparedGeneration",
                "backend!=provider","selectionKey!=key","mc.getConnection().getConnection()!=connection","!current()"})assertTrue(guard.contains(check),check);
        assertTrue(s.indexOf("CabinetSharedGames.resolve")>worker);assertTrue(s.indexOf("factory.open(path)")>s.indexOf("CabinetSharedGames.resolve"));
        assertTrue(s.contains("expectedCompatibility.equals(core.compatibilityId())"));
        assertTrue(s.contains("public void requestClose(){factory.requestClose();}"));
        assertTrue(s.contains("token!=generation||!connection.isConnected()"));
    }
    @Test void authoritativeModeSelectsAvailabilityAndSeatCapacity()throws Exception{
        String s=source("client/cabinet/CabinetClientBackends");
        assertTrue(s.contains("synchronous()?selected.syncUnavailableReason():selected.unavailableReason()"));
        assertTrue(s.contains("!CabinetClientAdmission.accepts(request,netplayGrant)"));
        String admission=source("client/cabinet/CabinetClientAdmission");
        assertTrue(admission.contains("grant.assignment() == assignment"));
        assertTrue(admission.contains("netplay ? CabinetNetplay.maxPlayers(assignment.backend())"));
        assertTrue(admission.contains(": CabinetBackends.syncMaxPlayers(assignment.backend())"));
        assertTrue(s.contains("CabinetBackends.syncMaxPlayers(request.backend())<assignment.capacity()"));
        assertTrue(s.contains("hosted()?null:netplay()?selected.netplayUnavailableReason():synchronous()?selected.syncUnavailableReason():selected.unavailableReason()"));
        String menu=source("client/cabinet/CabinetMenuScreen");
        assertTrue(menu.contains("provider==null?\"客户端未安装\":null"));
        assertFalse(menu.contains("provider.unavailableReason()"),"Menu selection must not require a client DLL before server mode is known");
    }
    @Test void restoredStateStillRequiresFullHashAndWorkerOwnsClose()throws Exception{
        String s=source("client/cabinet/CabinetSyncWorker");
        assertTrue(s.contains("core.loadState(command.state,command.frame);if(!CabinetSyncState.hash(state(core)).equals(command.hash))"));
        assertTrue(s.contains("if(frame%300==0)checkpoint(core,before)"));
        assertTrue(s.contains("host&&frame%snapshotInterval==0"));
        String close=s.substring(s.indexOf("@Override public void close()"));
        assertFalse(close.contains("core.close()"));assertTrue(close.contains("factory.requestClose()"));assertTrue(close.contains("core.requestClose()"));
        assertTrue(s.contains("finally{ready=false;activeCore=null;if(core!=null)try{core.close();}"));
    }
    @Test void liveScopeAndSharedWindowStillGuardSnapshotRetries()throws Exception{
        String s=source("client/cabinet/CabinetSyncClient");
        assertTrue(s.contains("connection.isConnected()&&id.equals(room.room())&&member.equals(room.member())&&epoch==1"));
        assertTrue(s.contains("if(scope(p.room(),p.member(),p.epoch()))outgoing.grant(p.token(),tick)"));
        assertTrue(s.contains("if(room.port()==0)outgoing.offer(e)"));
        assertTrue(s.contains("upload.needsOffer(tick)"));assertTrue(s.contains("CabinetSyncSender.send(connection,new CabinetSyncNetwork.Upload(p),n+512)"));
        assertTrue(s.contains("outgoing.close()"));
    }
    @Test void releasedSnapshotCopyDoesNotReplaceOrdinarySfcDescription()throws Exception{
        String s=source("client/cabinet/CabinetSyncSettingsScreen");
        assertTrue(s.contains("if(CabinetBackends.hostSnapshotSync(backend)&&setting!=null&&setting.mode()==1)"));
        assertTrue(s.contains("本地输入同步：kof97 / mslug2，最多两席。"));
        assertFalse(s.contains("实验："));
        assertTrue(s.contains("同步完成后即可操作；旁观接收音画。"));
        assertTrue(s.contains("本地同步：玩家各自在本机运行；旁观接收音画。"));
        assertTrue(s.contains("editable&&setting.supported()&&current!=1"));
    }
}
