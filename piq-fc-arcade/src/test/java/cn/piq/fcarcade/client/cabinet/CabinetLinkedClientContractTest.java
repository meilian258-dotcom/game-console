package cn.piq.fcarcade.client.cabinet;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CabinetLinkedClientContractTest {
    private String source(String file)throws Exception{return Files.readString(Path.of("src/main/java/cn/piq/fcarcade/"+file+".java"));}
    @Test void assignmentResetsClockAndCapacityIsNotConfusedWithBackendMaximum()throws Exception{var s=source("client/cabinet/CabinetClientBackends");assertTrue(s.contains("inputSequence=0;coinSequence=0;ticks=0;sessionConnection=mc.getConnection()"));assertTrue(s.contains("!CabinetClientAdmission.accepts(request,netplayGrant)"));assertTrue(s.indexOf("emulator=ready; // Own the core")<s.indexOf("ready.maxPlayers()<room.capacity()"));}
    @Test void onlyRecipientAndExactConnectionAcceptStream()throws Exception{var s=source("client/cabinet/CabinetClientBackends");assertTrue(s.contains("room.member().equals(stream.member())"));assertTrue(s.contains("connection.getConnection()==source"));assertTrue(s.contains("CabinetMediaSender.serverbound(connection.getConnection(),batch)"));}
    @Test void rendersBothAuthorizedCabinetsAndForceReleaseIsPerPort()throws Exception{var s=source("client/cabinet/CabinetClientBackends");assertTrue(s.contains("room==null?launch.target():room.primary()"));assertTrue(s.contains("CabinetVideoDisplay.render(event,room.secondary()"));assertTrue(s.contains("new CabinetRoomNetwork.Reset(room.room(),room.member(),inputSequence++)"));assertTrue(s.contains("emulator.releasePort(message.port())"));}
    @Test void cableGetsItemUseInsteadOfStartingGame()throws Exception{
        for(var path:new String[]{"world/FcArcadeBlock","world/DualCabinetBlock","world/DualCabinetPartBlock"}){
            var block=source(path);String guard="instanceof cn.piq.fcarcade.home.ZapperStandCableItem)return InteractionResult.PASS";
            assertTrue(block.contains(guard),path);
            int interaction=block.indexOf(path.endsWith("FcArcadeBlock")?"ServerCabinets.interact":"DualCabinetStructure.interact",block.indexOf(guard));
            assertTrue(interaction>block.indexOf(guard),path);
        }
        assertTrue(source("cabinet/CabinetLinkCableItem").contains("extends cn.piq.fcarcade.home.ZapperStandCableItem"));
        var shared=source("home/ZapperStandCableItem");
        assertTrue(shared.contains("c.getHand()!=InteractionHand.MAIN_HAND"));
        assertTrue(shared.contains("CabinetLinks.use(p,c.getClickedPos(),hit,p.isShiftKeyDown())"));
    }
    @Test void PhysicalRemovalNotChunkUnloadDeletesWire()throws Exception{for(var path:new String[]{"world/LegacyFcArcadeBlock","world/DualCabinetBlock"})assertTrue(source(path).contains("CabinetLinks.removed("));assertFalse(source("world/LegacyFcArcadeBlockEntity").contains("CabinetLinks.removed("));assertTrue(source("FcArcadeMod").contains("CabinetLinks.register()"));}
}
