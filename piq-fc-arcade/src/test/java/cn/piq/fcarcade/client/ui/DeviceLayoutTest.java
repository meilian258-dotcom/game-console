package cn.piq.fcarcade.client.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production layout, not copied fixture geometry or an in-game screenshot. */
class DeviceLayoutTest {
    @Test void everyInteractiveRegionAndRowFitsWithoutCollision(){
        for(int width:new int[]{320,321,360,427,465,466,480,512,640,854,1024,1920})
            for(int height:new int[]{240,241,256,278,360,480,556,1080})for(int toolbar=1;toolbar<=2;toolbar++){
                var b=DeviceLayout.browser(width,height,toolbar);assertTrue(b.supported());
                assertTrue(new DeviceLayout.Rect(0,0,width,height).contains(b.panel()));
                var areas=List.of(b.toolbar(),b.list(),b.details(),b.navigation(),b.status());
                for(int i=0;i<areas.size();i++){
                    assertTrue(b.panel().contains(areas.get(i)),width+"x"+height+" "+areas.get(i));
                    for(int j=0;j<i;j++)assertFalse(areas.get(i).overlaps(areas.get(j)));
                }
                for(int row=0;row<b.rows();row++){
                    assertTrue(b.list().contains(b.row(row)));assertEquals(20,b.row(row).height());
                    if(row>0)assertFalse(b.row(row).overlaps(b.row(row-1)));
                }
                assertTrue(b.details().contains(b.primary()));assertTrue(b.primary().y()>=b.details().y()+20);
                assertEquals(18,b.status().height());assertTrue(b.navigation().bottom()<b.status().y());
            }
    }
    @Test void narrowAndWideKeepTheSameCompactWorkstation(){
        assertTrue(DeviceLayout.browser(320,240,2).split());assertTrue(DeviceLayout.browser(512,278,2).split());
        assertFalse(DeviceLayout.browser(319,240,2).supported());assertFalse(DeviceLayout.browser(320,239,2).supported());
        assertEquals(420,DeviceLayout.browser(1920,1080,2).panel().width());
        assertEquals(260,DeviceLayout.browser(1920,1080,2).panel().height());
        var normal=DeviceLayout.browser(512,278,2).panel();
        assertTrue(normal.width()<=512*.81);assertTrue(normal.height()<=278*.82);
        assertTrue(normal.x()>=50);assertTrue(normal.y()>=26);
        assertThrows(IllegalArgumentException.class,()->DeviceLayout.browser(320,240,3));
    }
    private String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+name+".java"));}
    @Test void listClicksOnlySelectAndExplicitPrimaryActionsKeepOriginalWorkflows()throws Exception{
        var card=source("ClientCartridgeEditor");assertTrue(card.contains("()->preview(entry)"));assertTrue(card.contains("DeviceUi.Tone.PRIMARY"));
        assertTrue(card.contains("if(chosen!=null&&canSelectCover(chosen))"));
        assertTrue(card.contains("if(chosen.server())writeCover(chosen.hash);else uploadCover(chosen);"));
        assertTrue(card.contains("if(chosen!=null)select(chosen)"));
        var picker=source("rom/LocalRomPickerScreen");assertTrue(picker.contains("focusedPath=entry.path();rebuildWidgets();"));
        assertTrue(picker.contains("if(entry!=null)choose(entry.path())"));assertTrue(picker.contains("if(!current(token))return;"));
        var menu=source("cabinet/CabinetMenuScreen");assertTrue(menu.contains("focused=entry.id();rebuildWidgets();"));
        assertTrue(menu.contains("if(sent||age>=600||minecraft.screen!=this||minecraft.getConnection()!=connection||!menu.target().matches(minecraft.level)||entry==null||unavailable(entry)!=null)return;"));
        assertTrue(menu.contains("if(!CabinetClientBackends.configure(menu,entry.id()))return;"));
        assertTrue(menu.contains("sent=true;CabinetNetwork.send(new CabinetNetwork.Choose(menu.token(),entry.id()))"));
        assertTrue(menu.contains("++age>=600"));assertTrue(menu.contains("!menu.target().matches(minecraft.level)"));
    }
    @Test void chromeHasDynamicLabelsBoundedTextAndNoRuntimeOwnership()throws Exception{
        String s=source("ui/DeviceUi");assertTrue(s.contains("getMessage().getString()"));assertTrue(s.contains("setMessage(Component message)"));
        assertTrue(s.contains("plainSubstrByWidth"));assertTrue(s.contains("Tooltip.create"));
        assertTrue(s.contains("renderString(GuiGraphics g,Font nativeFont,int color)"));
        assertFalse(s.contains("void renderWidget("),"Vanilla Button must own background, hover/focus and disabled sprites");
        assertFalse(s.contains("0xFF79C9BF"),"No teal custom chrome");
        assertTrue(source("ArcadeSaveSlotsScreen").contains("(i==selectedSlot?\"> \":\"\")+\"槽位 \""),"Selected save slot remains visible without colored buttons");
        for(String forbidden:List.of("Files.","new Thread(","setScreen(","grabMouse(","CabinetClientBackends"))assertFalse(s.contains(forbidden));
        for(String name:List.of("RomLibraryScreen","ClientCartridgeEditor","RomRenameScreen","rom/LocalRomPickerScreen","cabinet/CabinetMenuScreen")){
            String screen=source(name);assertFalse(screen.contains("renderBackground("));assertTrue(screen.contains("DeviceUi.panel("));
        }
    }
    @Test void coverActionsNeverSubmitTheUnwrittenRomTitleDraft()throws Exception{
        String s=source("ClientCartridgeEditor");
        assertTrue(s.contains("()->writeCover(\"\")"));assertTrue(s.contains("()->writeCover(originalCover)"));
        String cover=s.substring(s.indexOf("private void writeCover("),s.indexOf("private void select("));
        assertTrue(cover.contains("target, romSha, cover, currentCardTitle"));
        assertFalse(cover.contains("draftTitle"));assertFalse(cover.contains("titleBox"));
        String upload=s.substring(s.indexOf("private void uploadCover("),s.indexOf("private void beginUpload("));
        assertTrue(upload.contains("beginUpload(true, local.fileName, hash, \"\", png)"));
    }
    @Test void renamePreservesDraftAndDeleteKeepsExactWhitelistedWrapper()throws Exception{
        assertTrue(source("RomRenameScreen").contains("if(name!=null)draft=name.getValue();"));
        assertTrue(source("RomRenameScreen").contains("if(value.isBlank())return;"));
        assertTrue(source("FcRomDeleteScreen").contains("extends DeviceConfirmScreen"));
        assertTrue(source("CartridgeScreenCompat").contains("entries.add(FcRomDeleteScreen.class.getName())"));
        var confirm=source("ui/DeviceConfirmScreen");assertTrue(confirm.contains("if(dismiss==null)answer(false)"));assertTrue(confirm.contains("if(!answered)"));
        assertTrue(confirm.contains("dismiss.run()"));
        assertTrue(confirm.contains("mouseScrolled("));assertTrue(confirm.contains("key==266||key==267"));
        String events=source("ClientArcadeEvents");
        assertEquals(2,events.split("\\Q() -> minecraft.setScreen(null)\\E",-1).length-1,"Escape must only dismiss the exit/resume action dialogs");
    }
}
