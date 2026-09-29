package cn.piq.fcarcade.client.cabinet;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetLibraryUiSourceTest {
    private String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/"+name+".java"));}
    @Test void repliesAreBoundToScreenConnectionRequestLeaseAndBackend()throws Exception{
        String s=source("CabinetSetupScreen");
        for(String guard:new String[]{"source!=connection","pending==null","!pending.equals(message.request())","!launch.lease().equals(message.lease())","!launch.backend().equals(message.backend())","connection.isConnected()"})assertTrue(s.contains(guard),guard);
        assertTrue(s.contains("15_000_000_000L"));assertTrue(s.contains("服务器目录请求超时"));
        assertTrue(s.contains("boolean selected=selecting;selecting=false"));
        assertTrue(s.contains("if(message.success()&&selected){CabinetClientBackends.startConfiguredServer(launch);return;}"));
    }
    @Test void serverSelectionNeverCallsLocalUploadAndHostRevalidatesLaunch()throws Exception{
        String ui=source("CabinetSetupScreen"),host=source("CabinetClientBackends");
        assertTrue(ui.contains("has(localTab?PlayerContentPolicy.ROM_UPLOAD:PlayerContentPolicy.SERVER_ROM_USE)"));
        assertTrue(ui.contains("if(!localTab){if(has(PlayerContentPolicy.SERVER_ROM_USE))request(0,chosen.id);return;}"));
        assertTrue(ui.contains("LocalRomLibrary.validateFile(chosen.path)"));
        assertTrue(host.contains("if(launch!=expected||!configureSelection||!(Minecraft.getInstance().screen instanceof CabinetSetupScreen)||!current())return;"));
        assertTrue(host.contains("startGame(null,false);"));
        assertTrue(host.contains("CabinetGameNetwork.setLibrarySink(CabinetSetupScreen::receive)"));
    }
    @Test void libraryPagesHaveBothBatchNavigationAndNoSourceModeConflation()throws Exception{
        String s=source("CabinetSetupScreen");
        assertTrue(s.contains("request(Math.max(0,offset-CabinetLibraryPage.SIZE),\"\")"));assertTrue(s.contains("request(offset+CabinetLibraryPage.SIZE,\"\")"));
        assertTrue(s.contains("来源："));assertTrue(s.contains("运行："));assertFalse(s.contains("已同步"));
    }
    @Test void initialFailureIsNotReportedAsAnEmptyCatalogAndFastClicksDoNotTimeOut()throws Exception{
        String s=source("CabinetSetupScreen");
        assertTrue(s.contains("serverLoaded=true;server=message.games()"));
        assertTrue(s.contains("!serverLoaded?\"服务器目录未读取\""));
        assertTrue(s.contains("尚未取得服务器目录"));
        assertTrue(s.contains("System.nanoTime()-requestAt<300_000_000L"));
        assertFalse(s.contains("offset/64"));
    }
}
