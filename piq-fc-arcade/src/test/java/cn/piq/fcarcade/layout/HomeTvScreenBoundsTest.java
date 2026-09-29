package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeTvScreenBoundsTest {
    @Test void screenMatchesImportedGlassRatherThanOneBlockArcade() {
        var bounds = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.HOME_RETRO_TV);
        assertEquals(1 - 28.24 / 16, bounds.min(), 1e-7);
        assertEquals(28.24 / 16, bounds.max(), 1e-7);
        assertEquals(3.76 / 16, bounds.negativeMin(), 1e-7);
        assertEquals(1 - 3.76 / 16, bounds.positiveMax(), 1e-7);
        assertEquals(4.8 / 16, bounds.bottom(), 1e-7);
        assertEquals(23.16 / 16, bounds.top(), 1e-7);
        assertEquals(0.814 / 16, bounds.frontInset(), 1e-7);
        assertEquals(4.0 / 3, bounds.aspectRatio(), 1e-6);
    }

    @Test void dynamicScreenFitsBetweenCornerMasksAndStaticGlass() {
        var bounds = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.HOME_RETRO_TV);
        double renderPlane = bounds.frontInset() - 0.0005;
        assertTrue(renderPlane > 0.798 / 16);
        assertTrue(renderPlane < 0.814 / 16);
        assertFalse(ArcadeDisplayStyle.HOME_RETRO_TV.usesWaterFramesAssets());
        assertEquals(25.4 / 16 + 0.38, ArcadeOccupancyLabelLayout.labelY(ArcadeDisplayStyle.HOME_RETRO_TV, 1), 1e-6);
    }

    @Test void allFourDirectionsUseTheOriginalAnchorRotationCenter() {
        var b = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.HOME_RETRO_TV);
        assertEquals(1 - b.max(), b.min(), 1e-7);
        assertEquals(1 - b.negativeMin(), b.positiveMax(), 1e-7);
        assertEquals(b.max() - b.negativeMin(), b.positiveMax() - b.min(), 1e-7);
        assertEquals(1.53, b.max() - b.negativeMin(), 1e-7);
        assertTrue(b.max() > 1 && b.min() < 0);
        assertEquals(b, ArcadeScreenBounds.resolve(2, 2, ArcadeDisplayStyle.HOME_RETRO_TV));
    }

    @Test void occupancyLabelFollowsLargeBodyCenterInEveryDirection() {
        var centers = new double[][]{{1, 1}, {0, 1}, {0, 0}, {1, 0}};
        for (int turn = 0; turn < 4; turn++) {
            var center = ArcadeOccupancyLabelLayout.homeTvCenter(turn);
            assertEquals(centers[turn][0], center.x());
            assertEquals(centers[turn][1], center.z());
        }
    }
}
