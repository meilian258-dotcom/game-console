package cn.piq.fcarcade.client.cabinet;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetClientSafetyTest {
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/cabinet/"+name+".java"));}
    @Test void asyncLinkFailureAndRejectedStarterCannotLeavePermanentLoading()throws Exception{
        var s=source("CabinetClientBackends");
        assertTrue(s.contains("catch(Exception|LinkageError failure)"));
        assertTrue(s.contains("catch(RejectedExecutionException failure){OPENING.set(false);stop("));
        assertTrue(s.contains("PENDING.compareAndSet(opened,null);CabinetCleanup.closeRetro(opened)"));
        assertTrue(s.contains("if(token==generation)stop(\"模拟器启动失败：\""));
        assertTrue(s.contains("finally{OPENING.set(false);}"));
        assertTrue(s.contains("AtomicReference<RetroEmulator> PENDING"));
        assertTrue(source("CabinetCleanup").contains("static void closeRetro(RetroEmulator emulator)"));
    }
    @Test void cancelledStartupStillClosesExactCoreAtBothHandoffs()throws Exception{
        var s=source("CabinetClientBackends");
        assertTrue(s.contains("if(shuttingDown||token!=generation){CabinetCleanup.closeRetro(ready);return;}"));
        assertTrue(s.contains("if(shuttingDown||token!=generation){PENDING.compareAndSet(ready,null);CabinetCleanup.closeRetro(ready);return;}"));
        assertTrue(s.contains("if(token!=generation||!current()||!playing){CabinetCleanup.closeRetro(ready);return;}"));
        assertTrue(s.contains("var pending=PENDING.getAndSet(null)"));
        assertTrue(s.contains("generation++;var previous=launch;var previousRoom=room;var previousConnection=sessionConnection;launch=null;backend=null;room=null;netplayGrant=null;peers=null"));
        assertTrue(s.contains("mc.getConnection()==previousConnection"));
        assertTrue(s.contains("if(media!=null){media.close();media=null;}"));
        assertTrue(s.contains("var old=emulator;emulator=null;CabinetCleanup.closeRetro(old)"));
        assertTrue(s.contains("if(pending!=old)CabinetCleanup.closeRetro(pending)"));
    }
    @Test void focusClearChecksKeyboardCallbacksAsWellAsTickAndRender()throws Exception{var s=source("CabinetPlayScreen");assertTrue(s.contains("if(!active&&focused){keys.clear();CabinetClientBackends.clearInput();}"));assertTrue(s.contains("keyReleased(int key,int scan,int modifiers){checkFocus();"));assertTrue(s.contains("keyPressed(int key,int scan,int modifiers){\n        checkFocus();")||s.contains("keyPressed(int key,int scan,int modifiers){\r\n        checkFocus();"));assertTrue(s.contains("removed(){keys.clear();CabinetClientBackends.clearInput();}"));assertFalse(s.contains("setKey("));assertFalse(s.contains("mouseHandler.grabMouse"));}
    @Test void longLabelsUsePixelFitAndDescriptionsWrap()throws Exception{assertTrue(source("CabinetMenuScreen").contains("DeviceUi.row(font,"));assertTrue(source("CabinetMenuScreen").contains("page=layout.page()"));assertTrue(source("CabinetSetupScreen").contains("extends cn.piq.fcarcade.client.ui.DeviceScreen"));assertTrue(source("CabinetSetupScreen").contains("Tooltip.create"));assertTrue(source("CabinetSetupScreen").contains("DeviceUi.row(font,"));String picker=source("../rom/LocalRomPickerScreen");assertTrue(picker.contains("DeviceUi.button(font,"));assertTrue(source("../ui/DeviceUi").contains("fit(font,getMessage().getString(),w-14)"));assertTrue(picker.contains("font.plainSubstrByWidth"));assertTrue(picker.contains("font.split(Component.literal(clean(text)),w)"));assertTrue(picker.contains("Tooltip.create"));assertTrue(source("CabinetPlayScreen").contains("font.split(Component.literal(line),boxWidth)"));}
}
