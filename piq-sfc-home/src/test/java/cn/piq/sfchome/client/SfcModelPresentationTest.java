package cn.piq.sfchome.client;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class SfcModelPresentationTest {
    @Test void allEightSyncedStatesHaveExactlyOneCardOrCover() {
        for (int mask = 0; mask < 8; mask++) {
            boolean p1 = (mask & 1) != 0, p2 = (mask & 2) != 0, card = (mask & 4) != 0;
            assertTrue(SfcModelPresentation.visible(0,p1,p2,card));
            assertEquals(p1,SfcModelPresentation.visible(1,p1,p2,card));
            assertEquals(p2,SfcModelPresentation.visible(2,p1,p2,card));
            assertEquals(!card,SfcModelPresentation.visible(3,p1,p2,card));
            assertEquals(card,SfcModelPresentation.visible(4,p1,p2,card));
            assertNotEquals(SfcModelPresentation.visible(3,p1,p2,card),SfcModelPresentation.visible(4,p1,p2,card));
        }
    }
    @Test void fiveDistinctStandaloneModelsAndNoComposite() {
        var paths = new HashSet<String>();
        for (int i=0;i<SfcModelPresentation.MODEL_COUNT;i++) {
            String path=SfcModelPresentation.path(i);assertTrue(paths.add(path));
            assertFalse(path.contains("preview"));assertFalse(path.contains("inventory"));
        }
        assertEquals(5,paths.size());
    }
    @Test void fourFacingsUseSameNativeBlockCenter() {
        assertEquals(0,SfcModelPresentation.yawDegrees(0));
        assertEquals(-90,SfcModelPresentation.yawDegrees(1));
        assertEquals(-180,SfcModelPresentation.yawDegrees(2));
        assertEquals(-270,SfcModelPresentation.yawDegrees(3));
        assertEquals(-270,SfcModelPresentation.yawDegrees(-1));
        assertEquals(0,SfcModelPresentation.yawDegrees(4));
    }
    @Test void invalidLayersFailClosed() {
        assertThrows(IllegalArgumentException.class,()->SfcModelPresentation.path(5));
        assertThrows(IllegalArgumentException.class,()->SfcModelPresentation.visible(-1,true,true,true));
    }
}
