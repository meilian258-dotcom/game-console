package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DeviceUsabilitySourceTest {
    private String source(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+file+".java"));}
    @Test void privatePresentationUsesNoBlurWithoutChangingSessionCallbacks()throws Exception{
        String s=source("PrivateHomeScreen");
        assertTrue(s.contains("extends cn.piq.fcarcade.client.ui.DeviceScreen"));
        assertTrue(s.contains("家用机 · 仅自己玩"));
        assertTrue(s.contains("存档留在本机"));
        assertTrue(s.contains("PrivateHomeClient.start(target,path.getValue(),backend)"));
        assertTrue(s.contains("backend=LibretroRuntimes.Backend.PROCESS"));
        assertTrue(source("PrivateHomeClient").contains("return start(target,filename,defaultBackend(target));"));
        assertTrue(s.contains("PrivateHomeClient.stop(\"已结束私人游戏\")"));
        assertTrue(s.contains("void onClose(){minecraft.setScreen(null);}"));
        assertFalse(s.contains("PacketDistributor"));
    }
    @Test void arcadeLibrarySeparatesFileSourceFromRuntime()throws Exception{
        String s=source("RomLibraryScreen");
        assertFalse(s.contains("\"已同步\""));
        assertTrue(s.contains("\"本地 / 已共享\""));
        assertTrue(s.contains("\"本地文件 · 服务器已有副本\""));
        assertTrue(s.contains("\"运行：本地输入同步\""));
        assertTrue(s.contains("ClientRomTransfers.select(blockPos, entry.sha256(), entry.server() != null)"));
    }
    @Test void collapsedTooltipOnlyFiltersOwnedHintLinesAndKeepsNameAndLore()throws Exception{
        String s=source("ui/DeviceItemTooltips");
        assertTrue(s.contains("value=Dist.CLIENT"));
        assertTrue(s.contains("for(int i=1;i<lines.size();)"));
        assertTrue(s.contains("line.getSiblings().isEmpty()"));
        assertTrue(s.contains("DeviceTooltipPolicy.detailKey(kind,translated.getKey())"));
        assertTrue(s.contains("if(Screen.hasShiftDown())lines.addAll(insertion,details)"));
        assertFalse(s.contains("lines.clear()"));
    }
}
