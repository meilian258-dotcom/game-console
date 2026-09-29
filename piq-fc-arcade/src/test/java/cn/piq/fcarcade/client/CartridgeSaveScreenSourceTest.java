package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeSaveScreenSourceTest {
    private String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/CartridgeSaveScreen.java"));}
    @Test void openReplyCannotReplaceUnrelatedScreenAndDenialMatchesOriginalEditorToken()throws Exception{
        String s=source();
        for(String guard:new String[]{"mc.screen==expected.parent","source==expected.connection","reply.editorToken().equals(expected.editorToken)","reply.system().equals(expected.system)","reply.token().equals(expected.editorToken)","reply.rom().equals(expected.rom)","15_000_000_000L"})assertTrue(s.contains(guard),guard);
        assertTrue(s.contains("screen.data.token().equals(reply.token())&&screen.connection==source"));
        assertTrue(s.contains("CartridgeSaveNetwork.CLOSE"));
    }
    @Test void deleteRequiresServerPreparationOpaqueVersionAndSecondExplicitButton()throws Exception{
        String s=source();
        assertTrue(s.contains("CartridgeSaveNetwork.PREPARE_DELETE,e.id(),e.version()"));
        assertTrue(s.contains("reply.confirmation()!=null&&!reply.pendingId().isEmpty()"));
        assertTrue(s.contains("button(\"确认删除\""));
        assertTrue(s.contains("CartridgeSaveNetwork.CONFIRM_DELETE,data.pendingId(),data.pendingVersion(),\"\",data.confirmation()"));
        assertTrue(s.contains("row.canEdit()&&!row.active()"));
        assertTrue(s.contains("nextActionAt=sentAt+1_050_000_000L"));
        assertTrue(s.contains("if(!current()||!ready())return"));
        assertTrue(s.contains("name.setMaxLength(32)"));
        assertTrue(s.contains("移入服务器回收目录，不自动恢复"));
        assertTrue(s.contains("本次列表未显示存档，详见状态"));
        assertFalse(s.contains("不可撤销"));
        assertTrue(s.contains("CartridgeSaveNetwork.RENAME,e.id(),e.version(),name.getValue().strip()"));
        for(String forbidden:new String[]{"Files.delete","Files.write","download","requestJoin","startGame","loadState"})assertFalse(s.contains(forbidden),forbidden);
    }
    @Test void closeReleasesOnlyItsOwnConnectionAndBackendSession()throws Exception{
        String s=source();
        assertTrue(s.contains("void removed(){closeSession();}"));
        assertTrue(s.contains("minecraft.getConnection().getConnection()==connection&&connection.isConnected()"));
        assertTrue(s.contains("new CartridgeSaveNetwork.Request(data.token(),CartridgeSaveNetwork.CLOSE"));
        assertTrue(s.contains("本机私人存档与恢复备份不在此列表"));
    }
}
