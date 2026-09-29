package cn.piq.fcarcade.client.ui;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeviceSecondaryUiTest {
    @Test void formsKeepFieldsStatusAndNavigationSeparateAtMinimumSize(){
        for(int width:new int[]{320,360,512,640,1024})for(int height:new int[]{240,256,278,360,556})for(int rows=1;rows<=6;rows++){
            var f=DeviceFormLayout.of(width,height,rows);var p=f.panel();
            assertTrue(new DeviceLayout.Rect(0,0,width,height).contains(p));assertTrue(f.fieldWidth()>=100);
            for(int row=0;row<rows;row++){
                var field=new DeviceLayout.Rect(f.fieldX(),f.rowY(row),f.fieldWidth(),20);
                assertTrue(p.contains(field));assertTrue(field.bottom()<=f.statusY());
                if(row>0)assertTrue(f.rowY(row-1)+20<=field.y());
            }
            assertTrue(f.statusY()+18<=f.footerY());assertTrue(f.footerY()+20<=p.bottom());
        }
    }
    private String source(String n)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/"+n+".java"));}
    @Test void allSecondaryScreensUseFlatDeviceChromeAndKeepTheirNetworkActions()throws Exception{
        for(String n:List.of("ArcadeSettingsScreen","LeaderboardPanelScreen","ArcadeSaveSlotsScreen","ArcadeSaveCatalogScreen","SkinLibraryScreen")){
            var s=source(n);assertTrue(s.contains("DeviceUi.panel("),n);assertFalse(s.contains("renderBackground("),n);assertTrue(s.contains("DeviceUi.button("),n);
        }
        assertTrue(source("ArcadeSettingsScreen").contains("FcNetwork.updateSettings(payload.blockPos(), settings)"));
        assertTrue(source("LeaderboardPanelScreen").contains("FcNetwork.updateLeaderboardPanel(update)"));
        for(String n:List.of("ArcadeSettingsScreen","LeaderboardPanelScreen"))assertTrue(source(n).contains("if(previous!=null)replacement.setValue(previous.getValue())"));
    }
    @Test void saveSlotsKeepPerSlotDraftAndNeverTreatEscapeAsRestart()throws Exception{
        var s=source("ArcadeSaveSlotsScreen");assertTrue(s.contains("selectedSlot=index;rebuildWidgets();"));
        assertTrue(s.contains("name.setResponder(value->names[index]=value)"));assertTrue(s.contains("name.setEditable(editable)"));
        assertTrue(s.contains("send(index,ArcadeSaveSlotActionPayload.PLAY,true)"));assertFalse(s.contains("PLAY, resume"));
        String restart=s.substring(s.indexOf("private void confirmRestart"),s.indexOf("private void confirmDelete"));
        assertTrue(restart.contains("if(confirmed)send(index,ArcadeSaveSlotActionPayload.PLAY,false);else minecraft.setScreen(this)"));
        assertTrue(s.contains("index + 1"));assertTrue(s.contains("players[index]"));
        assertTrue(s.contains("if (!isCurrentRom(slot))"));assertTrue(s.contains("if (replace)"));assertTrue(s.contains("if (confirmed)"));
    }
    @Test void skinSelectionOnlyChangesFocusAndExplicitActionPreservesCompatibility()throws Exception{
        var s=source("SkinLibraryScreen");assertTrue(s.contains("focused=index;rebuildWidgets();"));
        assertTrue(s.contains("()->choose(entry),!dedicatedAppearance()&&entry.compatible(),DeviceUi.Tone.PRIMARY"));
        assertTrue(s.contains("if (dedicatedAppearance() || !entry.compatible()) return;"));
        assertTrue(s.contains("ClientSkinManager.upload(blockPos, entry.local)"));
        assertTrue(s.contains("FcNetwork.selectSkin(blockPos, \"\")"));
        assertTrue(s.contains("FcNetwork.selectSkin(blockPos, entry.server.sha256())"));
        var saves=source("ArcadeSaveCatalogScreen");assertTrue(saves.contains("if (confirmed)"));assertTrue(saves.contains("entry.storageId()"));
    }
    @Test void dedicatedDualStatusNeverInvitesAnUnavailableApplyAction()throws Exception{
        var s=source("SkinLibraryScreen");
        assertTrue(s.contains("dedicated?\"当前：配套默认贴图\":entry.name"));
        assertTrue(s.contains("dedicated?\"此机型不支持自定义皮肤\":entry.defaultSkin?"));
        assertTrue(s.contains("dedicated?\"旧皮肤数据已保留\":\"选好后点击应用\""));
        assertTrue(s.contains("dedicated?\"仅支持配套外观\":entry.compatible()?\"确认后点击应用\""));
        int action=s.indexOf("private void choose(Entry entry)");
        int guard=s.indexOf("if (dedicatedAppearance() || !entry.compatible()) return;",action);
        assertTrue(guard>action&&guard<s.indexOf("FcNetwork.selectSkin",action));
        assertTrue(guard<s.indexOf("ClientSkinManager.upload",action));
    }
}
