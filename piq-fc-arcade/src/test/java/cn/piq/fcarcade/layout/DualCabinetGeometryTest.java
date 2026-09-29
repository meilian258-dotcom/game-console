package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetGeometryTest {
    @Test void physicalGlassRetainsUserFourByThreeAtAllFacings() {
        for (int turns=0;turns<4;turns++) {
            var q = DualCabinetGeometry.screen(turns);
            assertEquals(4D/3,q.aspectRatio(),1e-10);
            assertEquals(18D/16,q.width(),1e-10);
            assertEquals(13.5/16,q.height(),1e-10);
            var n=q.normal();
            assertEquals(1,n.x()*n.x()+n.y()*n.y()+n.z()*n.z(),1e-12);
            var b=DualCabinetGeometry.bounds(turns);
            assertTrue(b.contains(q.lowerMinX())); assertTrue(b.contains(q.upperMaxX()));
        }
    }
    @Test void footprintRotationUsesAnchorNotCabinetCenter() {
        var north=DualCabinetGeometry.bounds(0); var east=DualCabinetGeometry.bounds(1);
        assertEquals(.25,north.minX(),1e-12); assertEquals(1.75,north.maxX(),1e-12);
        assertEquals(1-north.maxZ(),east.minX(),1e-12);
        assertEquals(north.maxX(),east.maxZ(),1e-12);
        assertEquals(2,east.maxY(),1e-12);
        assertTrue(east.minX()<0); // Deeper rear occupies the already reserved second row.
        assertEquals(.5-DualCabinetGeometry.MODEL_Z_OFFSET,DualCabinetGeometry.occupancy(1).x(),1e-12);
        assertEquals(1,DualCabinetGeometry.occupancy(1).z(),1e-12);
    }
    @Test void standingPlayersLookIntoGlassWithoutRaisingTheCamera() {
        var q=DualCabinetGeometry.screen(0);
        assertTrue(q.lowerMinX().y()<1.62);
        assertTrue(q.upperMinX().y()>1.62);
        assertEquals(2.25,DualCabinetGeometry.occupancy(0).y(),1e-12);
        assertEquals((14+.55*Math.cos(Math.PI/8)+.2*Math.sin(Math.PI/8))/16
                +Math.sin(Math.PI/8)*DualCabinetGeometry.SCREEN_OFFSET,q.lowerMinX().y(),1e-12);
    }
    @Test void scoreboardSitsAheadOfGlassAndBelowItsCenter() {
        for(int turns=0;turns<4;turns++) {
            var q=DualCabinetGeometry.screen(turns); var text=DualCabinetGeometry.leaderboardTextOrigin(turns);
            var c=q.center(); var n=q.normal();
            double distance=(text.x()-c.x())*n.x()+(text.y()-c.y())*n.y()+(text.z()-c.z())*n.z();
            assertEquals(.012-DualCabinetGeometry.SCREEN_OFFSET,distance,1e-10);
            assertTrue(text.y()<c.y()); assertTrue(text.y()>q.lowerMinX().y());
        }
    }
    @Test void lcdFlatScreenIsFourByThreeAndFitsSingleBlock() {
        var q=ArcadeScreenBounds.resolve(1,1,ArcadeDisplayStyle.HOME_LCD_TV);
        assertEquals(4F/3,q.aspectRatio(),1e-6);
        assertEquals(6F/16,q.frontInset(),1e-8);
        assertTrue(q.min()>0 && q.max()<1 && q.top()<1);
    }
    @Test void sessionSkinAndBodyUseTheSameRegisteredCabinet() throws Exception {
        String base="src/main/java/cn/piq/fcarcade/";
        String be=Files.readString(Path.of(base+"world/DualCabinetBlockEntity.java"));
        assertTrue(be.contains("extends LegacyFcArcadeBlockEntity"));
        assertTrue(be.contains("super.loadAdditional(tag, registries)"));
        String sessions=Files.readString(Path.of(base+"server/ServerArcadeSessions.java"));
        assertTrue(sessions.contains("DualCabinetStructure.complete(level, clickedPos)"));
        assertTrue(sessions.contains("DualCabinetStructure.complete(machineLevel, session.key.anchor())"));
        String skin=Files.readString(Path.of(base+"server/ServerSkinService.java"));
        assertTrue(skin.contains("DualCabinetStructure.complete(player.serverLevel(), pos)"));
        String renderer=Files.readString(Path.of(base+"client/DualCabinetRenderer.java"));
        assertTrue(renderer.contains("ModBlockEntities.DUAL_CABINET.get()"));
        assertTrue(renderer.contains("cached.model() != model"));
        assertTrue(renderer.contains("!face.screen()"));
        assertTrue(renderer.contains("ResourceLocation skin = null;"));
        assertFalse(renderer.contains("ClientSkinManager.textureFor"));
        assertFalse(renderer.contains("setSkin"));
        assertTrue(renderer.contains("DualCabinetGeometry.modelZOffset(machine.compactFootprint())"));
        assertFalse(renderer.contains("ClientSkinManager.zOffset"));
    }
}
