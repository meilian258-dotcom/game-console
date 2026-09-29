package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RocketSkinQuadFilterTest {
    @Test
    void sourceScreenFaceMatchesAllFourBlockstateRotationsAndEveryCornerOrder() {
        // These are the actual '4比3空白屏面' north-face bounds in the hash-locked JSON model.
        for (int facing = 0; facing < 4; facing++) {
            double[][] corners = sourceFace(2.74, 18.74, 13.26, 26.63, 4.6, facing);
            for (int first = 0; first < 4; first++) {
                for (int second = 0; second < 4; second++) {
                    if (second == first) continue;
                    for (int third = 0; third < 4; third++) {
                        if (third == first || third == second) continue;
                        int fourth = 6 - first - second - third;
                        assertTrue(RocketSkinQuadFilter.isStaticScreenFace(
                                packed(corners, first, second, third, fourth), 8, 0));
                    }
                }
            }
        }
    }

    @Test
    void adjacentFramesAndRearShellAreNotExcluded() {
        for (int facing = 0; facing < 4; facing++) {
            // Actual neighboring '屏幕左/右/下/上厚框' and '屏幕厚背壳' north faces.
            for (double[] face : new double[][]{
                    {13.42, 17.75, 14.58, 27.45, 4.27},
                    {1.42, 17.75, 2.58, 27.45, 4.27},
                    {2.54, 17.75, 13.46, 18.72, 4.27},
                    {2.54, 26.56, 13.46, 27.45, 4.27},
                    {1.46, 17.75, 14.54, 27.45, 4.83}}) {
                assertFalse(RocketSkinQuadFilter.isStaticScreenFace(packed(sourceFace(
                        face[0], face[1], face[2], face[3], face[4], facing), 0, 1, 2, 3), 8, 0));
            }
        }
    }

    @Test
    void dynamicOffsetPlaneIsNotMistakenForTheRawModelFace() {
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            var points = new RocketArcadeGeometry.Point[]{screen.lowerMinX(), screen.lowerMaxX(),
                    screen.upperMaxX(), screen.upperMinX()};
            double[][] corners = new double[4][3];
            for (int i = 0; i < 4; i++) corners[i] = new double[]{points[i].x(), points[i].y(), points[i].z()};
            assertFalse(RocketSkinQuadFilter.isStaticScreenFace(packed(corners, 0, 1, 2, 3), 8, 0));
        }
    }

    @Test
    void acceptsFloatBakeRoundingButRejectsDegenerateAndUnrelatedVertexData() {
        double[][] corners = sourceFace(2.74, 18.74, 13.26, 26.63, 4.6, 0);
        corners[0][0] += 0.000005;
        assertTrue(RocketSkinQuadFilter.isStaticScreenFace(packed(corners, 0, 1, 2, 3), 8, 0));
        assertFalse(RocketSkinQuadFilter.isStaticScreenFace(packed(corners, 0, 1, 2, 2), 8, 0));
        corners[0][0] += 0.001;
        assertFalse(RocketSkinQuadFilter.isStaticScreenFace(packed(corners, 0, 1, 2, 3), 8, 0));
        assertFalse(RocketSkinQuadFilter.isStaticScreenFace(new int[31], 8, 0));
        assertFalse(RocketSkinQuadFilter.isStaticScreenFace(new int[32], 8, 7));
    }

    private static int[] packed(double[][] points, int... order) {
        int[] vertices = new int[32];
        for (int vertex = 0; vertex < 4; vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                vertices[vertex * 8 + axis] = Float.floatToIntBits((float) points[order[vertex]][axis]);
            }
        }
        return vertices;
    }

    private static double[][] sourceFace(double minX, double minY, double maxX, double maxY,
                                         double z, int quarterTurns) {
        double[][] source = {{minX, minY, z}, {maxX, minY, z}, {maxX, maxY, z}, {minX, maxY, z}};
        double sin = Math.sin(Math.toRadians(22.5));
        double cos = Math.cos(Math.toRadians(22.5));
        for (double[] point : source) {
            double dy = point[1] - 17.8;
            double dz = point[2] - 4.8;
            point[0] /= 16;
            point[1] = (17.8 + dy * cos - dz * sin) / 16;
            point[2] = (4.8 + dy * sin + dz * cos) / 16;
            for (int turn = 0; turn < quarterTurns; turn++) {
                double previousX = point[0];
                point[0] = 1 - point[2];
                point[2] = previousX;
            }
        }
        return source;
    }
}
