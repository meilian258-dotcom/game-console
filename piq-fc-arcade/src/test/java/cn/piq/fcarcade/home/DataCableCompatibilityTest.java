package cn.piq.fcarcade.home;

import cn.piq.fcarcade.registry.CreativeTabCatalog;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Narrow wiring contracts complement the real registered-item probe, not a substitute for world interaction. */
class DataCableCompatibilityTest {
    private static String java(String name)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+name+".java"));}
    @Test void oldSavedIdRemainsRegisteredAndSharesOneCurrentCreativeItemAndModel()throws Exception{
        var items=java("registry/ModItems");assertTrue(items.contains("ITEMS.register(\"cabinet_link_cable\""));
        assertTrue(items.contains("ITEMS.register(\"zapper_stand_cable\""));
        for(boolean optional:new boolean[]{false,true}){
            var list=CreativeTabCatalog.itemPaths(optional);assertFalse(list.contains("cabinet_link_cable"));
            assertEquals(1,list.stream().filter("zapper_stand_cable"::equals).count());
        }
        var model=JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/piq_fc_arcade/models/item/cabinet_link_cable.json"))).getAsJsonObject();
        assertEquals(1,model.size());assertEquals("piq_fc_arcade:item/zapper_stand_cable",model.get("parent").getAsString());
    }
    @Test void sharedCableUsesOldProtectedServicesAndCancelsOnlyOtherPendingFamily()throws Exception{
        var src=java("home/ZapperStandCableItem");
        assertTrue(src.contains("CabinetLinks.use(p,c.getClickedPos(),hit,p.isShiftKeyDown())"));
        assertTrue(src.contains("ZapperStandService.cable(p,c)"));
        assertTrue(src.contains("ZapperStandService.cancelCableSelection(p)"));
        assertTrue(src.contains("CabinetLinks.cancelSelection(p)"));
        assertTrue(src.contains("!p.connection.getConnection().isConnected()"));
        assertTrue(java("cabinet/CabinetLinkCableItem").contains("extends cn.piq.fcarcade.home.ZapperStandCableItem"));
        var authority=java("cabinet/CabinetLinks");assertTrue(authority.contains("if(!player.hasPermissions(2))"));
        assertTrue(authority.contains("authorizedBoth(player,clickedBinding,otherBinding)"));
        assertTrue(authority.contains("if(busy(server,resolved,other))"));
    }
    @Test void consoleEndDisconnectRetainsBothFinalBindingsAfterProtectionCallbacks()throws Exception{
        var src=java("home/ZapperStandService");int at=src.indexOf("if(p.isShiftKeyDown()&&HomeHardware.loadedEndpoint");
        assertTrue(at>0);var branch=src.substring(at,src.indexOf("var first=pending",at));
        for(var guard:new String[]{"!permission(p,target,hit)","!permission(p,b,hit)","!current(p,target)","!current(p,b)",
                "p.getMainHandItem()!=held","b.link()!=original","d.links.at(end(target))!=saved","console(p.getServer(),saved)!=target"})assertTrue(branch.contains(guard),guard);
        assertTrue(branch.lastIndexOf("disconnect(b)")>branch.lastIndexOf("d.links.at(end(target))!=saved"));
    }
    @Test void avKeepsItsOwnItemAndOriginalShiftDisconnectTransaction()throws Exception{
        var item=java("home/AvCableItem");assertTrue(item.contains("HomeHardware.useCable(player"));
        assertTrue(item.contains("tooltip.piq_fc_arcade.av_cable.disconnect"));
        var hardware=java("home/HomeHardware");assertTrue(hardware.contains("wire.is(ModItems.AV_CABLE.get())"));
        assertTrue(hardware.contains("disconnect(endpoint, player)"));
    }
    @Test void allCabinetAnchorAndProxyPathsYieldToBothDataCableIdsBeforeSessionInteraction()throws Exception{
        for(String type:new String[]{"FcArcadeBlock","DualCabinetBlock","DualCabinetPartBlock"}){
            var src=java("world/"+type);int guard=src.indexOf("instanceof cn.piq.fcarcade.home.ZapperStandCableItem");
            assertTrue(guard>0,type);assertTrue(src.substring(guard,guard+120).contains("return InteractionResult.PASS"),type);
            int interaction=src.indexOf(type.equals("FcArcadeBlock")?"ServerCabinets.interact":"DualCabinetStructure.interact",guard);
            assertTrue(interaction>guard,type);
        }
    }
    @Test void gunCordUsesTheSameRealPhysicalSecondSocketAndNeverP1()throws Exception{
        var src=java("client/zapper/ZapperStandRenderer");
        int start=src.indexOf("Point socket=cn.piq.fcarcade.layout.ControllerCableGeometry.socket(");assertTrue(start>0);
        var assignment=src.substring(start,src.indexOf(';',start));assertTrue(assignment.endsWith(",1,ct)"));
        assertTrue(assignment.contains("Style.FAMICOM")&&assignment.contains("Style.SUBOR_WIDE")&&assignment.contains("Style.SUBOR"));
    }
}
