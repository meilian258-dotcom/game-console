package cn.piq.sfchome.client;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class SfcServerCoverUiTest {
    @Test void existingCoverUseIsNotAnUploadAndDoesNotChangeRomOrTitle()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java"));
        assertTrue(s.contains("covers.server(data.covers().stream()"));
        assertTrue(s.contains("SfcEditorPermissions.selection(data.capabilities(),row.local(),coverTab)"));
        assertTrue(s.contains("if(selected.local()){importFile(selected.path());return;}"));
        assertTrue(s.contains("send(coverTab?SfcHomeNetwork.USE_COVER:SfcHomeNetwork.WRITE,selected.hash(),coverTab?\"\":library.title().strip()"));
        assertFalse(s.contains("if(coverTab){scanLocal(false);return;}"));
    }
    @Test void saveSettingsAndReadOnlyLibraryReuseSharedPagesWithSfcAuthority()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java"));
        assertFalse(s.contains("CartridgeSaveScreen.open(\"sfc\""));
        assertTrue(s.contains("SfcHomeNetwork.SET_SAVE_MODE,SfcSaveSettings.mode(choice)"));
        assertTrue(s.contains("saveManagerButton.active=!busy()&&!scanning&&canBrowse()"));
        assertTrue(s.contains("new CartridgeSaveSettingsLayout(layout.panel())"));
        assertTrue(s.contains("CartridgeSaveScreen.expectOpen(\"piq_sfc_home:sfc\",token(),data.currentRom())"));
        assertTrue(s.contains("send(SfcHomeNetwork.SAVE_LIBRARY,data.currentRom(),\"\",0,0,new byte[0])"));
        assertTrue(s.contains("只读，不支持重命名或删除"));
    }
}
