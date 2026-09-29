package cn.piq.fcarcade.cabinet;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring checks only; geometry tests are executable, MC interaction remains manual QA. */
class CabinetPowerSourceTest {
    @Test void physicalSwitchSoundsOnceOnlyAfterAnAcceptedPowerTransition()throws Exception{
        String source=read("cabinet/ServerCabinets");
        String interact=source.substring(source.indexOf("public static boolean interact("),source.indexOf("private static boolean allowClick("));
        int press=interact.indexOf("if(power){");
        assertTrue(interact.indexOf("if(!valid(player,binding,true))return true;")<press);
        assertTrue(interact.indexOf("if(!allowClick(state,player,now(server)))return true;")<press);
        String button=interact.substring(press,interact.indexOf("if(!CabinetRooms.hasTarget(server,target))",press));
        assertTrue(button.indexOf("boolean wasPowered=cabinet.visualPowered()")<button.indexOf("CabinetRooms.powerOff(player,target)"));
        assertTrue(button.indexOf("launch(player,state,binding,selected)")<button.indexOf("boolean powered=cabinet.visualPowered()"));
        assertTrue(button.contains("if(wasPowered!=powered)cn.piq.fcarcade.home.HomeInteractionSounds.play(player.serverLevel(),clicked,"));
        assertTrue(button.contains("powered?cn.piq.fcarcade.home.HomeInteractionSounds.Action.POWER_ON:cn.piq.fcarcade.home.HomeInteractionSounds.Action.POWER_OFF"));
        assertEquals(1,source.split("HomeInteractionSounds.play",-1).length-1);
        for(String file:new String[]{"world/LegacyFcArcadeBlockEntity","cabinet/CabinetRooms","client/CabinetPowerRenderer"})assertFalse(read(file).contains("HomeInteractionSounds.play"),file);
        String sounds=read("home/HomeInteractionSounds");
        assertTrue(sounds.contains("SoundEvents.LEVER_CLICK"));assertTrue(sounds.contains("SoundSource.BLOCKS"));
        assertTrue(sounds.contains("level.playSound(null, pos,")); // Include clicker and nearby observers once.
        assertTrue(sounds.contains("case POWER_ON -> 1.15f"));assertTrue(sounds.contains("case POWER_OFF -> .9f"));
        assertTrue(sounds.contains("catch (RuntimeException failure)"));
    }
    @Test void compiledDualCabinetInheritsNetworkTagAndPacketNotDiskOnlyOverride()throws Exception{
        // Real class method resolution catches the subclass regression missed by base-source checks.
        var base=cn.piq.fcarcade.world.LegacyFcArcadeBlockEntity.class;
        var dual=cn.piq.fcarcade.world.DualCabinetBlockEntity.class;
        assertSame(base,dual.getMethod("getUpdateTag",net.minecraft.core.HolderLookup.Provider.class).getDeclaringClass());
        assertSame(base,dual.getMethod("getUpdatePacket").getDeclaringClass());
        String s=read("world/LegacyFcArcadeBlockEntity");
        String tag=s.substring(s.indexOf("public CompoundTag getUpdateTag("),s.indexOf("public Packet<ClientGamePacketListener> getUpdatePacket("));
        for(String key:new String[]{"VisualCabinetPowered","VisualCabinetLink","VisualGameProfile"})assertTrue(tag.contains(key),key);
        assertTrue(tag.contains("saveWithoutMetadata(registries)"));
        assertTrue(read("world/DualCabinetBlockEntity").contains("super.saveAdditional(tag, registries)"));
    }
    @Test void normalPowerOffIsNotReportedAsRuntimeFailure(){
        assertTrue(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("侧面开关键：整组街机已关闭"));
        assertTrue(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("正面开关键：整组街机已关闭"));
        assertTrue(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("所有操作席已空闲 60 秒，街机已自动关机"));
        assertFalse(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("所有操作席已空闲 60 秒，街机已自动关机；存档失败"));
        assertFalse(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("侧面开关键：整组街机已关闭；存档失败"));
        assertFalse(cn.piq.fcarcade.client.ui.DeviceNoticePolicy.routineText("只有当前主持玩家或管理员可以关闭整组街机"));
    }
    private String read(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+file+".java"));}
    @Test void serverSyncsBothCabinetsAndClearsBeforeDiscardingPeer()throws Exception{
        String s=read("cabinet/CabinetRooms");
        assertTrue(s.contains("visualPower(server,state,room,true)"));
        assertTrue(s.contains("visualPower(server,room.target,on);visualPower(server,state.secondary.get(room.id),on)"));
        String close=s.substring(s.indexOf("private static void close("),s.indexOf("private static void forgetMember("));
        assertTrue(close.indexOf("visualPower(server,state,room,false)")<close.indexOf("state.secondary.remove(room.id)"));
        assertTrue(s.contains("cabinet.cabinetId().equals(target.identity())"));
        assertTrue(s.contains("level.hasChunkAt(target.anchor())"));
        String control=s.substring(s.indexOf("if((own.port==0||room.mode==CabinetSyncMode.SERVER_MEDIA)&&room.ready"),s.indexOf("已退出街机；再次右键加入或启动"));
        assertFalse(control.contains("visualPower")); // Temporarily releasing P1 is not power-off.
    }
    @Test void powerIsNetworkVisualOnlyAndNotSavedAsRuntimeAuthority()throws Exception{
        String s=read("world/LegacyFcArcadeBlockEntity");
        String save=s.substring(s.indexOf("protected void saveAdditional("),s.indexOf("public CompoundTag getUpdateTag("));
        assertFalse(save.contains("VisualCabinetPowered"));
        assertTrue(s.contains("tag.putBoolean(\"VisualCabinetPowered\",visualPowered)"));
        assertTrue(s.contains("level!=null&&level.isClientSide&&tag.getBoolean(\"VisualCabinetPowered\")"));
        assertTrue(s.contains("visualPowered==powered")); // No per-tick broadcast.
    }
    @Test void rendererUsesServerStateAndOpaqueDepthTestedQuads()throws Exception{
        String s=read("client/CabinetPowerRenderer");
        assertTrue(s.contains("CabinetPowerMesh.faces(machine.visualPowered())"));
        assertFalse(s.contains("debugFilledBox"));assertFalse(s.contains("isControlling"));
        String material=read("client/CabinetPowerMaterial");
        for(String expected:new String[]{"VertexFormat.Mode.QUADS","NO_TRANSPARENCY","LEQUAL_DEPTH_TEST","COLOR_DEPTH_WRITE"})assertTrue(material.contains(expected),expected);
    }
}
