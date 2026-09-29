package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.ArcadeOccupancyLabelLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcadeBlockScreenRendererTest {
    @Test
    void legacyOccupancyLabelClearsThePhysicalCabinetTop() {
        float labelY = ArcadeOccupancyLabelLayout.labelY(
                ArcadeDisplayStyle.LEGACY_GENERIC,
                1);

        assertTrue(labelY >= 2.35F);
    }

    @Test
    void multiblockOccupancyLabelClearsTheStructure() {
        float labelY = ArcadeOccupancyLabelLayout.labelY(
                ArcadeDisplayStyle.DELUXE,
                3);

        assertTrue(labelY >= 3.35F);
    }

    @Test
    void waterFramesBigTvLabelClearsItsOversizedModel() {
        float labelY = ArcadeOccupancyLabelLayout.labelY(
                ArcadeDisplayStyle.WATERFRAMES_BIG_TV,
                1);

        assertEquals(2.28F, labelY, 0.0001F);
    }

    @Test
    void waterFramesTvLabelClearsItsRaisedModel() {
        float labelY = ArcadeOccupancyLabelLayout.labelY(
                ArcadeDisplayStyle.WATERFRAMES_TV,
                1);

        assertEquals(1.83F, labelY, 0.0001F);
    }
}
