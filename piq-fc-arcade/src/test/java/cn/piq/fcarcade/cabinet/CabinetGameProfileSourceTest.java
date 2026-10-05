package cn.piq.fcarcade.cabinet;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameProfileSourceTest {
    String read(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    @Test void editRequiresCurrentLeaseBrowseAndOpBeforeDataMutation()throws Exception{
        String s=read("cabinet/CabinetGameLibraryService");
        for(String check:new String[]{"!current(p,connection)","!CabinetRooms.canConfigureGame(p,r.lease())","!PlayerContentAccess.canBrowse(p)","!p.hasPermissions(2)","!PlayerContentAccess.canUseServerRom(p)","!CabinetCoinPolicy.supported(r.backend().toString())"})assertTrue(s.indexOf(check)<s.indexOf(".update(game,r.edit())"),check);
        assertTrue(s.contains("m.contentId().equals(r.selectedContentId())"));
        String edit=s.substring(s.indexOf("if(r.edit()!=null)"),s.indexOf("if(r.selectedContentId().isEmpty())"));
        assertFalse(edit.contains(".put(target"));assertFalse(edit.contains("store.contains"));assertFalse(edit.contains("IO.execute"));
    }
    @Test void profileDoesNotEnterManifestOrRomCacheIdentity()throws Exception{
        String m=read("cabinet/CabinetGameManifest");assertFalse(m.contains("CabinetGameProfile"));
        String db=read("cabinet/CabinetGameProfiles");assertTrue(db.contains("game.backend()+\"/\"+game.gameHash()"));assertTrue(db.contains("find(game).revision()!=proposed.revision()"));
    }
    @Test void allVideoModesUseTheSameDisplayOverride()throws Exception{
        assertTrue(read("client/cabinet/CabinetVideoDisplay").contains("cabinet.displayProfile().aspect().rawAspect(rawAspect,rotation"));
        assertTrue(read("client/watch/CabinetWatchDisplay").contains("CabinetVideoDisplay.render"));
        String be=read("world/LegacyFcArcadeBlockEntity");assertTrue(be.contains("CabinetGameProfiles.forCabinet(this)"));
        String rooms=read("cabinet/CabinetRooms");assertTrue(rooms.contains("refreshGameProfile(server,state.secondary.get(room.id))"));assertTrue(rooms.contains("refreshGameProfiles(player.getServer())"));
    }
    @Test void libraryKeepsLocalAndServerViewsAndStructuredEditor()throws Exception{
        String s=read("client/cabinet/CabinetSetupScreen");
        for(String label:new String[]{"本地游戏","服务器游戏","支持人数：","屏幕标注：","显示比例：","保存到服务器","上传并使用"})assertTrue(s.contains(label),label);
        assertTrue(s.contains("profiles.getOrDefault(e.contentId(),CabinetGameProfile.EMPTY)"));
        assertTrue(s.contains("editProfile.revision()"));assertTrue(s.contains("不自动修改 PGM、DIP 或旋转"));
        assertTrue(read("cabinet/CabinetGameNetwork").contains("cabinet-game-7"));
    }
}
