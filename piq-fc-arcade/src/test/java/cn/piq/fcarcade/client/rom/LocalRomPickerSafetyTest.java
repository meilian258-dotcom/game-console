package cn.piq.fcarcade.client.rom;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Source contracts complement the real layout and filesystem tests; no fake Minecraft world. */
class LocalRomPickerSafetyTest {
    private String source()throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/rom/LocalRomPickerScreen.java"));}
    @Test void tinyWindowCannotQueueUnboundFocusRefresh()throws Exception{
        String tick=source().split("@Override public void tick\\(\\)")[1].split("@Override public void removed")[0];
        assertTrue(tick.indexOf("if(!initialized||closed)return;")<tick.indexOf("minecraft.isWindowActive()"));
        assertTrue(source().contains("connection=minecraft.getConnection();refresh();"));
    }
    @Test void asynchronousSelectionRequiresSameScreenConnectionAndRevision()throws Exception{
        String s=source();assertTrue(s.contains("token==revision"));assertTrue(s.contains("minecraft.screen==this"));
        assertTrue(s.contains("minecraft.getConnection()==connection"));assertTrue(s.contains("if(!current(token))return;"));
        assertTrue(s.contains("public void removed(){closed=true;revision++;}"));assertTrue(s.contains("LocalRomLibrary.validateFile(file)"));
    }
    @Test void renderingDoesNotBlurOrReadRomFiles()throws Exception{
        String s=source();String render=s.split("@Override public void render")[1];
        assertFalse(s.contains("renderBackground("));assertFalse(render.contains("LocalRomLibrary.scan"));assertFalse(render.contains("Files."));
        assertTrue(s.contains("LocalRomLibrary.submit"));assertTrue(s.contains("高级：指定文件"));assertTrue(s.contains("打开 ROM 文件夹"));
    }
}
