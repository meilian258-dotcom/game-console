package cn.piq.fcarcade.home;

import cn.piq.fcarcade.rom.RomSaveMode;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeSaveModeSettingTest {
    static final String ROM="a".repeat(64),OTHER="b".repeat(64);
    String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    String between(String s,String a,String b){return s.substring(s.indexOf(a),s.indexOf(b,s.indexOf(a)));}
    @Test void exactlyTheThreeRealModesAreAcceptedForAnIdleWrittenServerRom(){
        for(var mode:RomSaveMode.values())assertTrue(CartridgeComputerBinding.permitsSaveModeSetting(mode.id(),ROM,ROM,true,false));
        for(int mode:new int[]{Integer.MIN_VALUE,-1,3,4,Integer.MAX_VALUE})assertFalse(CartridgeComputerBinding.permitsSaveModeSetting(mode,ROM,ROM,true,false));
    }
    @Test void selectedButUnwrittenLocalUnknownOrBusyGamesCannotChangeAnotherRom(){
        for(int mode=0;mode<=2;mode++){
            assertFalse(CartridgeComputerBinding.permitsSaveModeSetting(mode,OTHER,ROM,true,false));
            assertFalse(CartridgeComputerBinding.permitsSaveModeSetting(mode,ROM,ROM,false,false));
            assertFalse(CartridgeComputerBinding.permitsSaveModeSetting(mode,ROM,ROM,true,true));
            for(String invalid:new String[]{null,"","a".repeat(63),"A".repeat(64),"z".repeat(64)})assertFalse(CartridgeComputerBinding.permitsSaveModeSetting(mode,invalid,invalid,true,false));
        }
    }
    @Test void networkOperationHasCanonicalFieldsAndMatchingDedicatedProtocol()throws Exception{
        String s=source("home/CartridgeNetwork");assertTrue(s.contains("SET_SAVE_MODE = 9"));assertTrue(s.contains("TrafficPayloadRegistrar.create(event,\"34\")"));
        assertTrue(s.contains("operation > SET_SAVE_MODE"));assertTrue(s.contains("operation==SET_SAVE_MODE&&(hash.isEmpty()||total>2||offset!=0||data.length!=0"));
        assertTrue(s.contains("||!cover.isEmpty()||!title.isEmpty()||!fileName.isEmpty()"));
        assertTrue(s.contains("buffer.writeVarInt(entry.saveMode().id())"));assertTrue(s.contains("RomSaveMode.fromId(buffer.readVarInt())"));
    }
    @Test void callbackCannotMutateModeUntilExactCardAndComputerAreRevalidated()throws Exception{
        String s=source("server/ServerCartridgeService"),set=between(s,"void setSaveMode(","void write(");
        assertTrue(s.contains("case CartridgeNetwork.SET_SAVE_MODE -> state.setSaveMode(player, session, request)"));
        assertTrue(set.indexOf("session.upload!=null||session.processing")<set.indexOf("valid(player,session,request.target())"));
        assertEquals(2,set.split("permitsSaveModeSetting",-1).length-1);
        int revalidate=set.indexOf("valid(player,session,request.target())"),second=set.indexOf("permitsSaveModeSetting",set.indexOf("permitsSaveModeSetting")+1);
        assertTrue(revalidate<second);assertTrue(second<set.indexOf("FcCartridgeData.setSaveMode("));assertFalse(set.contains("library.setSaveMode("));
        assertFalse(set.contains("FcCartridgeData.write("));assertFalse(set.contains("ServerArcadeSessions."));
        String valid=between(s,"boolean valid(","void send(");
        for(String token:new String[]{"computerPermitted","cardMatches(player, session, claimed)","session.target.permits(claimed","PlayerContentAccess.canBrowse(player)","ItemStack.isSameItemSameComponents"})assertTrue(valid.contains(token),token);
        assertEquals(2,set.split("PlayerContentAccess.canBrowse\\(player\\)",-1).length-1);
        assertTrue(set.lastIndexOf("PlayerContentAccess.canBrowse(player)")>revalidate);
        assertFalse(set.contains("player.hasPermissions(2)"));
    }
    @Test void uiHasNoModeSendOnSelectionRefreshResizeOrTabAndGatesTheVisibleWrittenRom()throws Exception{
        String s=source("client/ClientCartridgeEditor"),gate=between(s,"private boolean saveModeEditable(","private static String saveModeName(");
        for(String token:new String[]{"currentGame()","!busy&&!scanning","game.sha256(),romSha,true,false","has(PlayerContentPolicy.BROWSE)"})assertTrue(gate.contains(token),token);
        assertFalse(gate.contains("selectedRom()"),"Filtering or previewing another row must not disable the written cartridge's settings");
        String send=between(s,"private void setSaveMode(","private void togglePlayers(");
        assertTrue(send.contains("if(!current()||!saveSettings||!saveModeEditable())return"));assertTrue(send.contains("target,game.sha256()"));assertTrue(send.contains("mode.id()"));
        assertTrue(send.contains("if(cardSaveMode==mode)return"));assertFalse(s.contains("saveMode().next()"));
        assertEquals(1,s.split("CartridgeNetwork.SET_SAVE_MODE",-1).length-1);
        assertTrue(s.contains("button(\"存档设置\""));
        assertTrue(s.contains("int bottom=workbench.clearCover().y()-2"));
    }
    @Test void explicitSelectionKeepsTheEditorAndWaitsForServerConfirmation()throws Exception{
        String s=source("client/ClientCartridgeEditor");
        String view=between(s,"if (saveSettings) {","workbench = CartridgeWorkbenchLayout.of(menu)");
        for(String token:new String[]{"RomSaveMode.NONE,RomSaveMode.MACHINE,RomSaveMode.PLAYER","cardSaveMode==mode"," · 当前","()->setSaveMode(mode)","saveModeEditable()&&!selected"})assertTrue(view.contains(token),token);
        assertFalse(view.contains("MC.setScreen"));assertFalse(view.contains("cancel()"));
        String key=between(s,"@Override public boolean keyPressed(","@Override public void removed()");
        assertTrue(key.contains("GLFW_KEY_ESCAPE&&saveSettings"));assertFalse(key.contains("cancel()"));
        String send=between(s,"private void setSaveMode(","private void togglePlayers(");
        assertFalse(send.contains("serverRoms ="));assertFalse(send.contains("romSha ="));assertFalse(send.contains("FcCartridgeData.write"));
    }
    @Test void onlyPersonalModeOpensStartupPickerAndDefaultStillDisablesSaving()throws Exception{
        String sessions=source("server/ServerArcadeSessions");
        assertTrue(sessions.contains("if(mode==RomSaveMode.PLAYER){m.openHomeSaveSlots"));
        String library=source("server/ServerRomLibrary");
        String method=between(library,"RomSaveMode saveMode(","void setSaveMode(");
        assertTrue(method.contains("RomSaveMode.NONE"));
    }
}
