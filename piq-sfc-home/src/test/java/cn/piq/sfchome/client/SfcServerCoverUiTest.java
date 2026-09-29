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
    @Test void netplayOwnershipSelectorDoesNotPretendToBeTheOldUnimplementedSaveCatalog()throws Exception{
        String s=Files.readString(Path.of("src/main/java/cn/piq/sfchome/client/SfcCardEditorScreen.java"));
        assertFalse(s.contains("CartridgeSaveScreen.open(\"sfc\""));
        assertTrue(s.contains("SfcHomeNetwork.SET_SAVE_MODE,(data.saveMode()+1)%3"));
        assertTrue(s.contains("saveManagerButton.active=!busy()&&canBrowse()"));
        assertTrue(s.contains("与旧本机备份隔离"));
    }
}
