package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketArcadeGeometryTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void visibleScreenRetainsTheSourceModelsFourByThreeSurface() {
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            assertEquals(10.52D / 16.0D, screen.width(), EPSILON);
            assertEquals(7.89D / 16.0D, screen.height(), EPSILON);
            assertEquals(4.0D / 3.0D, screen.aspectRatio(), EPSILON);
        }
    }

    @Test
    void northScreenUsesTheRotatedFrontFaceAndTinyOutwardOffset() {
        double angle = Math.toRadians(22.5D);
        var lower = RocketArcadeGeometry.screen(0).lowerMinX();
        var upper = RocketArcadeGeometry.screen(0).upperMaxX();
        assertEquals(2.74D / 16.0D, lower.x(), EPSILON);
        assertEquals(13.26D / 16.0D, upper.x(), EPSILON);
        assertEquals((17.8D + 0.94D * Math.cos(angle) + 0.2D * Math.sin(angle)) / 16.0D
                + Math.sin(angle) * RocketArcadeGeometry.SCREEN_SURFACE_OFFSET,
                lower.y(), EPSILON);
        assertEquals((4.8D + 0.94D * Math.sin(angle) - 0.2D * Math.cos(angle)) / 16.0D
                - Math.cos(angle) * RocketArcadeGeometry.SCREEN_SURFACE_OFFSET,
                lower.z(), EPSILON);
        assertTrue(upper.z() > lower.z(), "The upper edge slopes into the cabinet");
        assertTrue(upper.y() > lower.y());
    }

    @Test
    void screenCornersAreCoplanarAndWithinTheTwoBlockCabinet() {
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            var normal = screen.normal();
            assertEquals(1.0D, normal.distanceTo(point(0, 0, 0)), EPSILON);
            for (var corner : List.of(screen.lowerMinX(), screen.lowerMaxX(),
                    screen.upperMaxX(), screen.upperMinX())) {
                assertTrue(corner.x() >= 0 && corner.x() <= 1);
                assertTrue(corner.z() >= 0 && corner.z() <= 1);
                assertTrue(corner.y() > 1 && corner.y() < RocketArcadeGeometry.MODEL_TOP);
                double dot = (corner.x() - screen.lowerMinX().x()) * normal.x()
                        + (corner.y() - screen.lowerMinX().y()) * normal.y()
                        + (corner.z() - screen.lowerMinX().z()) * normal.z();
                assertEquals(0.0D, dot, EPSILON);
            }
        }
    }

    @Test
    void screenRotationsMatchBlockstateNorthEastSouthWest() {
        var north = RocketArcadeGeometry.screen(0);
        var source = north.lowerMinX();
        assertPoint(point(1 - source.z(), source.y(), source.x()),
                RocketArcadeGeometry.screen(1).lowerMinX());
        assertPoint(point(1 - source.x(), source.y(), 1 - source.z()),
                RocketArcadeGeometry.screen(2).lowerMinX());
        assertPoint(point(source.z(), source.y(), 1 - source.x()),
                RocketArcadeGeometry.screen(3).lowerMinX());
        assertTrue(north.normal().z() < 0);
        assertTrue(RocketArcadeGeometry.screen(1).normal().x() > 0);
        assertTrue(RocketArcadeGeometry.screen(2).normal().z() > 0);
        assertTrue(RocketArcadeGeometry.screen(3).normal().x() < 0);
        assertEquals(north, RocketArcadeGeometry.screen(4));
        assertEquals(RocketArcadeGeometry.screen(3), RocketArcadeGeometry.screen(-1));
    }

    @Test
    void facingAdapterRejectsNonHorizontalAndDiagonalDirections() {
        assertEquals(0, RocketArcadeGeometry.quarterTurns(0, -1));
        assertEquals(1, RocketArcadeGeometry.quarterTurns(1, 0));
        assertEquals(2, RocketArcadeGeometry.quarterTurns(0, 1));
        assertEquals(3, RocketArcadeGeometry.quarterTurns(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> RocketArcadeGeometry.quarterTurns(0, 0));
        assertThrows(IllegalArgumentException.class, () -> RocketArcadeGeometry.quarterTurns(1, 1));
    }

    @Test
    void steppedCollisionMatchesCabinetExtentsWithoutDecorativeCubeExplosion() {
        var boxes = RocketArcadeGeometry.collisionBoxes(0);
        assertEquals(8, boxes.size());
        assertEquals(0.68D / 16.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::minX).min().orElseThrow(), EPSILON);
        assertEquals(15.32D / 16.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::maxX).max().orElseThrow(), EPSILON);
        assertEquals(0.382D / 16.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::minZ).min().orElseThrow(), EPSILON);
        assertEquals(14.65D / 16.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::maxZ).max().orElseThrow(), EPSILON);
        assertEquals(0.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::minY).min().orElseThrow(), EPSILON);
        assertEquals(2.0D, boxes.stream().mapToDouble(RocketArcadeGeometry.Box::maxY).max().orElseThrow(), EPSILON);
        assertTrue(boxes.stream().anyMatch(box -> box.contains(point(0.5D, 15.0D / 16.0D, 1.0D / 16.0D))));
        assertFalse(boxes.stream().anyMatch(box -> box.contains(point(0.5D, 23.0D / 16.0D, 1.0D / 16.0D))),
                "The inset monitor must not become a full two-block collision column");
        assertThrows(UnsupportedOperationException.class, boxes::clear);
    }

    @Test
    void collisionRotationsPreserveEveryStepAndStayInsideSingleColumn() {
        var north = RocketArcadeGeometry.collisionBoxes(0);
        for (int facing = 0; facing < 4; facing++) {
            var boxes = RocketArcadeGeometry.collisionBoxes(facing);
            assertEquals(north.size(), boxes.size());
            for (int index = 0; index < boxes.size(); index++) {
                var box = boxes.get(index);
                var original = north.get(index);
                assertTrue(box.minX() >= 0 && box.maxX() <= 1);
                assertTrue(box.minZ() >= 0 && box.maxZ() <= 1);
                assertTrue(box.minY() >= 0 && box.maxY() <= 2);
                assertTrue(box.maxX() > box.minX());
                assertTrue(box.maxY() > box.minY());
                assertTrue(box.maxZ() > box.minZ());
                assertEquals(original.volume(), box.volume(), EPSILON);
                var rotatedCenter = RocketArcadeGeometry.rotate(point(
                        (original.minX() + original.maxX()) / 2,
                        (original.minY() + original.maxY()) / 2,
                        (original.minZ() + original.maxZ()) / 2), facing);
                assertTrue(box.contains(rotatedCenter));
            }
        }
    }

    @Test
    void labelAndScreenProjectionUseTheSharedRocketGeometry() {
        var screen = RocketArcadeGeometry.screen(0);
        var bounds = ArcadeScreenBounds.resolve(1, 1, ArcadeDisplayStyle.LEGACY_GENERIC);
        assertEquals(screen.lowerMinX().x(), bounds.min(), 1.0E-7D);
        assertEquals(screen.lowerMaxX().x(), bounds.max(), 1.0E-7D);
        assertEquals(screen.lowerMinX().y(), bounds.bottom(), 1.0E-7D);
        assertEquals(screen.upperMinX().y(), bounds.top(), 1.0E-7D);
        assertEquals(screen.center().z(), bounds.frontInset(), 1.0E-7D);
        assertEquals(2.38F, ArcadeOccupancyLabelLayout.labelY(ArcadeDisplayStyle.LEGACY_GENERIC, 1), 1.0E-6F);
    }

    @Test
    void leaderboardIsOutwardFromTheSameScreenPlaneAndFitsItsWidth() {
        double offset = RocketArcadeGeometry.LEADERBOARD_SURFACE_OFFSET
                - RocketArcadeGeometry.SCREEN_SURFACE_OFFSET;
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            var center = screen.center();
            var normal = screen.normal();
            assertPoint(point(center.x() + normal.x() * offset,
                    center.y() + normal.y() * offset, center.z() + normal.z() * offset),
                    RocketArcadeGeometry.leaderboardCenter(facing));
            // Vanilla text-display pixels are 1/40 of a block before scale.
            assertTrue(RocketArcadeGeometry.LEADERBOARD_LINE_WIDTH * 0.025D
                    * RocketArcadeGeometry.LEADERBOARD_SCALE < screen.width());
        }
    }

    @Test
    void bottomAnchoredLeaderboardLeavesRoomForSevenWrappedLines() {
        double baseline = RocketArcadeGeometry.LEADERBOARD_BASELINE_BELOW_CENTER;
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            var center = RocketArcadeGeometry.leaderboardCenter(facing);
            var origin = RocketArcadeGeometry.leaderboardTextOrigin(facing);
            var normal = screen.normal();
            assertEquals(baseline, center.distanceTo(origin), EPSILON);
            assertTrue(origin.y() < center.y());
            assertEquals(0.0D, (origin.x() - center.x()) * normal.x()
                    + (origin.y() - center.y()) * normal.y()
                    + (origin.z() - center.z()) * normal.z(), EPSILON);
            double textHeight = 71.0D * 0.025D * RocketArcadeGeometry.LEADERBOARD_SCALE;
            assertTrue(textHeight < screen.height() / 2.0D + baseline);
            assertTrue(baseline < screen.height() / 2.0D);
        }
    }

    @Test
    void northSelectionIsOneOverallBoxUsingTheActualCabinetBounds() {
        assertEquals(new RocketArcadeGeometry.Box(
                        0.68D / 16.0D, 0.0D, 0.3820101013D / 16.0D,
                        15.32D / 16.0D, 2.0D, 14.65D / 16.0D),
                RocketArcadeGeometry.selectionBox(0));
        assertEquals(RocketArcadeGeometry.selectionBox(0), RocketArcadeGeometry.selectionBox(4));
        assertEquals(RocketArcadeGeometry.selectionBox(3), RocketArcadeGeometry.selectionBox(-1));
    }

    @Test
    void overallSelectionRotatesToFourDistinctFacingBounds() {
        double left = 0.68D / 16.0D;
        double right = 15.32D / 16.0D;
        double front = 0.3820101013D / 16.0D;
        double back = 14.65D / 16.0D;
        double[][] expected = {
                {left, front, right, back},
                {1 - back, left, 1 - front, right},
                {1 - right, 1 - back, 1 - left, 1 - front},
                {front, 1 - right, back, 1 - left}
        };
        for (int facing = 0; facing < 4; facing++) {
            var box = RocketArcadeGeometry.selectionBox(facing);
            assertEquals(expected[facing][0], box.minX(), EPSILON);
            assertEquals(expected[facing][1], box.minZ(), EPSILON);
            assertEquals(expected[facing][2], box.maxX(), EPSILON);
            assertEquals(expected[facing][3], box.maxZ(), EPSILON);
            assertEquals(0.0D, box.minY(), EPSILON);
            assertEquals(2.0D, box.maxY(), EPSILON);
        }
    }

    @Test
    void overallOutlineDoesNotFillTheSteppedPhysicalCollision() {
        var insetAir = point(0.5D, 23.0D / 16.0D, 1.0D / 16.0D);
        for (int facing = 0; facing < 4; facing++) {
            var rotatedAir = RocketArcadeGeometry.rotate(insetAir, facing);
            assertTrue(RocketArcadeGeometry.selectionBox(facing).contains(rotatedAir));
            assertEquals(8, RocketArcadeGeometry.collisionBoxes(facing).size());
            assertFalse(RocketArcadeGeometry.collisionBoxes(facing).stream()
                    .anyMatch(box -> box.contains(rotatedAir)));
        }
    }

    private static RocketArcadeGeometry.Point point(double x, double y, double z) {
        return new RocketArcadeGeometry.Point(x, y, z);
    }

    private static void assertPoint(RocketArcadeGeometry.Point expected, RocketArcadeGeometry.Point actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }
}
