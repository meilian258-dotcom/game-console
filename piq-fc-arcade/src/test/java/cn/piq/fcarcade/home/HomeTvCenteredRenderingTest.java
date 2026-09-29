package cn.piq.fcarcade.home;

import cn.piq.fcarcade.client.HomeHardwareRenderLayout;
import cn.piq.fcarcade.client.HomeHardwareRenderLayout.Point;
import cn.piq.fcarcade.layout.ArcadeDisplayStyle;
import cn.piq.fcarcade.layout.ArcadeOccupancyLabelLayout;
import cn.piq.fcarcade.layout.ArcadeScreenBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-consumer world-coordinate contracts; no game/renderer bootstrap is needed. */
class HomeTvCenteredRenderingTest {
    @Test void oldOverloadsKeepTheOriginalBoundsCableAndOccupancyCenter() {
        for (int turn = 0; turn < 4; turn++) {
            assertEquals(HomeHardwareRenderLayout.tvBounds(turn), HomeHardwareRenderLayout.tvBounds(turn, false));
            assertEquals(new Point(0, 0, 0), HomeHardwareRenderLayout.tvOffset(turn, false));
            assertEquals(HomeHardwareRenderLayout.rotate(HomeHardwareRenderLayout.TV_CABLE, turn),
                    HomeHardwareRenderLayout.tvCable(turn, false));
            assertEquals(ArcadeOccupancyLabelLayout.homeTvCenter(turn), ArcadeOccupancyLabelLayout.homeTvCenter(turn, false));
        }
    }

    @Test void renderingBoundsEncloseEveryReservedCellAndTheWholePhysicalBody() {
        for (boolean centered : new boolean[]{false, true}) for (var facing : HomeTvFootprint.Facing.values()) {
            var render = HomeHardwareRenderLayout.tvBounds(facing.ordinal(), centered);
            for (var cell : HomeTvFootprint.cells(facing, centered)) {
                assertContained(render, new Point(cell.x(), cell.y(), cell.z()));
                assertContained(render, new Point(cell.x() + 1, cell.y() + 1, cell.z() + 1));
            }
            var body = HomeTvFootprint.bounds(facing, centered);
            assertContained(render, new Point(body.minX() / 16, body.minY() / 16, body.minZ() / 16));
            assertContained(render, new Point(body.maxX() / 16, body.maxY() / 16, body.maxZ() / 16));
            double volume = (render.max().x() - render.min().x()) * (render.max().y() - render.min().y())
                    * (render.max().z() - render.min().z());
            assertEquals(HomeTvFootprint.cellCount(centered), volume, 1e-9);
        }
    }

    @Test void alreadyBakedShellOffsetMatchesCollisionRatherThanRotatingItAgain() {
        for (var facing : HomeTvFootprint.Facing.values()) {
            var old = HomeTvFootprint.bounds(facing);
            var centered = HomeTvFootprint.bounds(facing, true);
            var offset = HomeHardwareRenderLayout.tvOffset(facing.ordinal(), true);
            assertEquals(old.minX() / 16 + offset.x(), centered.minX() / 16, 1e-9);
            assertEquals(old.maxX() / 16 + offset.x(), centered.maxX() / 16, 1e-9);
            assertEquals(old.minZ() / 16 + offset.z(), centered.minZ() / 16, 1e-9);
            assertEquals(old.maxZ() / 16 + offset.z(), centered.maxZ() / 16, 1e-9);
            assertEquals(0, offset.y());
            assertEquals(.5, Math.abs(offset.x()) + Math.abs(offset.z()));
        }
    }

    @Test void avEndFollowsTheSameLocalTranslationThenVanillaRotation() {
        var original = HomeHardwareRenderLayout.TV_CABLE;
        for (int turn = 0; turn < 4; turn++) {
            var expected = HomeHardwareRenderLayout.rotate(new Point(original.x() - .5, original.y(), original.z()), turn);
            assertPoint(expected, HomeHardwareRenderLayout.tvCable(turn, true));
            assertContained(HomeHardwareRenderLayout.tvBounds(turn, true), expected);
        }
        assertPoint(new Point(.25, 8.29 / 16, 28.92 / 16), HomeHardwareRenderLayout.tvCable(0, true));
    }

    @Test void occupancyAndScreenTextFollowTheShellWithoutMovingTheOldTv() {
        for (int turn = 0; turn < 4; turn++) {
            var offset = HomeHardwareRenderLayout.tvOffset(turn, true);
            var oldLabel = ArcadeOccupancyLabelLayout.homeTvCenter(turn, false);
            var label = ArcadeOccupancyLabelLayout.homeTvCenter(turn, true);
            assertEquals(oldLabel.x() + offset.x(), label.x(), 1e-9);
            assertEquals(oldLabel.z() + offset.z(), label.z(), 1e-9);
            var expectedLabel = HomeHardwareRenderLayout.rotate(new Point(.5, 0, 1), turn);
            assertEquals(expectedLabel.x(), label.x(), 1e-9);
            assertEquals(expectedLabel.z(), label.z(), 1e-9);
            var oldScreen = ArcadeOccupancyLabelLayout.homeTvScreenCenter(turn, false);
            var screen = ArcadeOccupancyLabelLayout.homeTvScreenCenter(turn, true);
            assertEquals(oldScreen.x() + offset.x(), screen.x(), 1e-9);
            assertEquals(oldScreen.z() + offset.z(), screen.z(), 1e-9);
        }
        assertEquals(25.4 / 16 + .38,
                ArcadeOccupancyLabelLayout.labelY(ArcadeDisplayStyle.HOME_RETRO_TV, 1), 1e-6);
    }

    @Test void translatedFourByThreeScreenAndIdleTextShareTheSameHorizontalCenter() {
        var bounds = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.HOME_RETRO_TV);
        double left = bounds.negativeMin(), right = bounds.max();
        assertEquals(4.0 / 3, (right - left) / (bounds.top() - bounds.bottom()), 1e-6);
        for (boolean centered : new boolean[]{false, true}) for (int turn = 0; turn < 4; turn++) {
            var offset = HomeHardwareRenderLayout.tvOffset(turn, centered);
            var rotatedCenter = HomeHardwareRenderLayout.rotate(new Point((left + right) / 2,
                    (bounds.bottom() + bounds.top()) / 2, bounds.frontInset() - .001), turn);
            var text = ArcadeOccupancyLabelLayout.homeTvScreenCenter(turn, centered);
            assertEquals(rotatedCenter.x() + offset.x(), text.x(), 1e-6);
            assertEquals(rotatedCenter.z() + offset.z(), text.z(), 1e-6);
            // Translation changes position only: width/height and depth separation
            // from the static black glass are identical for new and legacy TVs.
            var a = HomeHardwareRenderLayout.rotate(new Point(left, bounds.bottom(), bounds.frontInset()), turn);
            var b = HomeHardwareRenderLayout.rotate(new Point(right, bounds.bottom(), bounds.frontInset()), turn);
            double width = Math.hypot((b.x() + offset.x()) - (a.x() + offset.x()),
                    (b.z() + offset.z()) - (a.z() + offset.z()));
            assertEquals(4.0 / 3, width / (bounds.top() - bounds.bottom()), 1e-6);
        }
    }

    private static void assertPoint(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
        assertEquals(expected.z(), actual.z(), 1e-9);
    }

    private static void assertContained(HomeHardwareRenderLayout.Bounds bounds, Point point) {
        assertTrue(point.x() >= bounds.min().x() - 1e-9 && point.x() <= bounds.max().x() + 1e-9);
        assertTrue(point.y() >= bounds.min().y() - 1e-9 && point.y() <= bounds.max().y() + 1e-9);
        assertTrue(point.z() >= bounds.min().z() - 1e-9 && point.z() <= bounds.max().z() + 1e-9);
    }
}
