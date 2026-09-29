package cn.piq.fcarcade.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HomeHardwareRenderLayoutTest {
    @Test
    void heldControllerFrontAndButtonOrderUseOppositeRotations() {
        assertEquals(-90f, HomeHardwareRenderLayout.heldControllerYaw(0));
        assertEquals(90f, HomeHardwareRenderLayout.heldControllerYaw(1));
        assertThrows(IllegalArgumentException.class, () -> HomeHardwareRenderLayout.heldControllerYaw(-1));
        assertThrows(IllegalArgumentException.class, () -> HomeHardwareRenderLayout.heldControllerYaw(2));
    }
    @Test
    void scaledCableAnchorsMatchBothTransformedModelPorts() {
        assertEquals(0.5, HomeHardwareRenderLayout.CONSOLE_CABLE.x(), 1e-12);
        assertEquals(2.28 / 16, HomeHardwareRenderLayout.CONSOLE_CABLE.y(), 1e-12);
        assertEquals(14.97 / 16, HomeHardwareRenderLayout.CONSOLE_CABLE.z(), 1e-12);
        assertEquals(new HomeHardwareRenderLayout.Point(12.0 / 16, 8.29 / 16, 28.92 / 16),
                HomeHardwareRenderLayout.TV_CABLE);
    }

    @Test
    void insertedCardUsesTheSameConsoleTransformAfterItsOriginalSlotTranslation() {
        var bottom = HomeHardwareRenderLayout.insertedCardPoint(new HomeHardwareRenderLayout.Point(0.5, 0, 0.5));
        assertEquals(0.5, bottom.x(), 1e-12);
        assertEquals(2.46 / 16, bottom.y(), 1e-12);
        assertEquals(10.232 / 16, bottom.z(), 1e-12);
        var top = HomeHardwareRenderLayout.insertedCardPoint(new HomeHardwareRenderLayout.Point(0.5, 7.8 / 16, 0.5));
        assertEquals(7.14 / 16, top.y(), 1e-12);
    }

    @Test
    void tvFrustumCoversRotatedEightBlockVolumeWithoutChangingItsRotationPivot() {
        double[][] expected = {{0, 0, 2, 2}, {-1, 0, 1, 2}, {-1, -1, 1, 1}, {0, -1, 2, 1}};
        for (int facing = 0; facing < 4; facing++) {
            var bounds = HomeHardwareRenderLayout.tvBounds(facing);
            assertEquals(expected[facing][0], bounds.min().x());
            assertEquals(expected[facing][1], bounds.min().z());
            assertEquals(expected[facing][2], bounds.max().x());
            assertEquals(expected[facing][3], bounds.max().z());
            assertEquals(0, bounds.min().y());
            assertEquals(2, bounds.max().y());
            for (var point : new HomeHardwareRenderLayout.Point[]{new HomeHardwareRenderLayout.Point(0, 0, 0),
                    new HomeHardwareRenderLayout.Point(2, 25.4 / 16, 28.91 / 16)}) {
                var rotated = HomeHardwareRenderLayout.rotate(point, facing);
                assertTrue(rotated.x() >= bounds.min().x() && rotated.x() <= bounds.max().x());
                assertTrue(rotated.z() >= bounds.min().z() && rotated.z() <= bounds.max().z());
            }
        }
    }

    @Test
    void atlasEdgesMapToZeroAndOneWithoutLegacyDivisionBySixteen() {
        assertEquals(0, HomeHardwareRenderLayout.textureCoordinate(0.25f, 0.25f, 0.375f));
        assertEquals(1, HomeHardwareRenderLayout.textureCoordinate(0.375f, 0.25f, 0.375f));
        assertEquals(0.5f, HomeHardwareRenderLayout.textureCoordinate(0.3125f, 0.25f, 0.375f));
    }

    @Test
    void cardLabelMapsToItsExactPixelsOnTheWhole1024Skin() {
        // A 1024 sprite located anywhere in an 8192 atlas. Model UV .5..8.5
        // is already normalized to .03125..53125 before being baked into that atlas.
        for (int pixel : new int[]{32, 544, 288}) {
            float expected = pixel / 1024f;
            float atlas = 0.625f + (0.75f - 0.625f) * expected;
            assertEquals(expected, HomeHardwareRenderLayout.textureCoordinate(atlas, 0.625f, 0.75f), 1e-6f);
        }
    }

    @Test
    void textureCoordinatePreservesBakedUvShrinkRatherThanSnappingEdges() {
        float atlas = 0.25f + 0.125f * 0.002f;
        float actual = HomeHardwareRenderLayout.textureCoordinate(atlas, 0.25f, 0.375f);
        assertEquals(0.002f, actual, 1e-6f);
        assertTrue(actual > 0 && actual < 0.01f);
    }

    @Test
    void endpointsRotateWithTheFourBlockstateFacings() {
        var north = new HomeHardwareRenderLayout.Point(0.25, 0.5, 0.9);
        assertEquals(new HomeHardwareRenderLayout.Point(0.1, 0.5, 0.25).x(),
                HomeHardwareRenderLayout.rotate(north, 1).x(), 1e-12);
        assertEquals(0.75, HomeHardwareRenderLayout.rotate(north, 2).x());
        assertEquals(0.9, HomeHardwareRenderLayout.rotate(north, 3).x());
        assertEquals(north, HomeHardwareRenderLayout.rotate(north, 4));
        for (int i = 0; i < 4; i++) assertEquals(0.5, HomeHardwareRenderLayout.rotate(north, i).y());
    }

    @Test
    void fullScaleOriginalCardFitsInsideOriginalSlot() {
        double shift = HomeHardwareRenderLayout.CARTRIDGE_Z * 16;
        assertTrue(2.29999382 > 1.81 && 13.70000618 < 14.19);
        assertTrue(7.25 + shift > 10.87);
        assertTrue(8.75 + shift < 12.57);
        assertTrue(HomeHardwareRenderLayout.CARTRIDGE_Y * 16 > 3.94);
        assertTrue(HomeHardwareRenderLayout.CARTRIDGE_Y * 16 < 5.52);
        assertTrue(7.80000618 / 16 + HomeHardwareRenderLayout.CARTRIDGE_Y < 1);
    }

    @Test
    void sagNeverMovesEndpointsAndRemainsBounded() {
        var a = new HomeHardwareRenderLayout.Point(0.5, 0.4, 1.2);
        var b = new HomeHardwareRenderLayout.Point(8.5, 3.4, 0.9);
        assertEquals(a, HomeHardwareRenderLayout.cablePoint(a, b, 0));
        var last = HomeHardwareRenderLayout.cablePoint(a, b, 1);
        assertEquals(b.x(), last.x(), 1e-12);
        assertEquals(b.y(), last.y(), 1e-12);
        assertEquals(b.z(), last.z(), 1e-12);
        double linearMidpoint = (a.y() + b.y()) / 2;
        double actual = HomeHardwareRenderLayout.cablePoint(a, b, 0.5).y();
        assertTrue(actual < linearMidpoint && actual >= linearMidpoint - 0.65);
    }
}
