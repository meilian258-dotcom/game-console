package cn.piq.fcarcade.layout;

import cn.piq.fcarcade.world.DualCabinetFootprint;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DualCabinetWallFitTest {
    @Test void rearMeetsNextSolidRowAndSidesKeepTheirOriginalInsetInEveryDirection() {
        for (int turn=0; turn<4; turn++) {
            var b=DualCabinetGeometry.bounds(turn);
            var physical=DualCabinetFootprint.bounds(DualCabinetFootprint.Facing.values()[turn]);
            assertEquals(b.minX()*16,physical.minX(),1e-10);
            assertEquals(b.maxX()*16,physical.maxX(),1e-10);
            assertEquals(b.minZ()*16,physical.minZ(),1e-10);
            assertEquals(b.maxZ()*16,physical.maxZ(),1e-10);
            switch(turn) {
                case 0 -> { assertEquals(2,b.maxZ()); assertEquals(.25,b.minX()); assertEquals(1.75,b.maxX()); }
                case 1 -> { assertEquals(-1,b.minX()); assertEquals(.25,b.minZ()); assertEquals(1.75,b.maxZ()); }
                case 2 -> { assertEquals(-1,b.minZ()); assertEquals(-.75,b.minX()); assertEquals(.75,b.maxX()); }
                case 3 -> { assertEquals(2,b.maxX()); assertEquals(-.75,b.minZ()); assertEquals(.75,b.maxZ()); }
            }
            assertEquals(12,DualCabinetFootprint.cells(DualCabinetFootprint.Facing.values()[turn]).size());
        }
    }
    @Test void sourceBackTranslationMatchesGlassAndWholeWorldDrawButNotItemPresentation() throws Exception {
        assertEquals(2,(DualCabinetGeometry.SOURCE_FRONT+DualCabinetGeometry.BODY_DEPTH)/16
                +DualCabinetGeometry.MODEL_Z_OFFSET,1e-12);
        assertEquals(1,(DualCabinetGeometry.SOURCE_FRONT+DualCabinetGeometry.BODY_DEPTH)/16
                +DualCabinetGeometry.modelZOffset(true),1e-12);
        String renderer=Files.readString(Path.of("src/main/java/cn/piq/fcarcade/client/DualCabinetRenderer.java"));
        assertTrue(renderer.substring(0,renderer.indexOf("private static void draw(")).contains("DualCabinetGeometry.modelZOffset(machine.compactFootprint())"));
        assertTrue(renderer.contains("Float.intBitsToFloat(data[p+2]) + DualCabinetGeometry.MODEL_Z_OFFSET"));
        assertFalse(renderer.substring(renderer.indexOf("private static final class ItemRenderer")).contains("MODEL_Z_OFFSET"));
        assertFalse(renderer.substring(renderer.indexOf("private static final class ItemRenderer")).contains("modelZOffset"));
    }
}
