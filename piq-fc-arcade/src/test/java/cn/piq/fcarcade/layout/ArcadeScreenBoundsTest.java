package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcadeScreenBoundsTest {
    @Test
    void enlargedDeluxeScreenRemainsFourByThree() {
        ArcadeScreenBounds bounds = ArcadeScreenBounds.resolve(
                1,
                1,
                ArcadeDisplayStyle.DELUXE);

        assertEquals(4.0F / 3.0F, bounds.aspectRatio(), 0.0001F);
        assertEquals(0.75F, bounds.max() - bounds.min(), 0.0001F);
        assertEquals(0.5625F, bounds.top() - bounds.bottom(), 0.0001F);
    }

    @Test
    void waterFramesDisplayIsLargeFourByThreeSurface() {
        ArcadeScreenBounds bounds = ArcadeScreenBounds.resolve(
                1,
                1,
                ArcadeDisplayStyle.WATERFRAMES_BIG_TV);

        assertEquals(4.0F / 3.0F, bounds.aspectRatio(), 0.0001F);
        assertTrue(bounds.max() - bounds.min() > 2.0F);
        assertTrue(bounds.top() > 1.0F);
        assertEquals(0.125F, bounds.frontInset(), 0.0001F);
    }

    @Test
    void multiBlockDisplayRemainsFourByThree() {
        assertEquals(
                4.0F / 3.0F,
                ArcadeScreenBounds.resolve(
                        3,
                        3,
                        ArcadeDisplayStyle.DELUXE).aspectRatio(),
                0.0001F);
    }

    @Test
    void rectangularPanelFitsFourByThreeInsideAnyBuiltSize() {
        ArcadeScreenBounds wide = ArcadeScreenBounds.resolve(
                4,
                2,
                ArcadeDisplayStyle.WATERFRAMES_PANEL);
        ArcadeScreenBounds tall = ArcadeScreenBounds.resolve(
                2,
                4,
                ArcadeDisplayStyle.WATERFRAMES_PANEL);

        assertEquals(4.0F / 3.0F, wide.aspectRatio(), 0.0001F);
        assertEquals(4.0F / 3.0F, tall.aspectRatio(), 0.0001F);
        assertEquals(0.96875F, wide.frontInset(), 0.0001F);
        assertTrue(wide.bottom() > 0.0F);
        assertTrue(tall.min() > 0.0F);
    }
}
