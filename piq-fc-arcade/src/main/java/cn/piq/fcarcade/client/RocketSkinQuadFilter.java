package cn.piq.fcarcade.client;

import cn.piq.fcarcade.layout.RocketArcadeGeometry;

/** Recognizes only the model's static screen face, independent of vertex winding and facing. */
final class RocketSkinQuadFilter {
    private static final double TOLERANCE = 0.0001D;
    private static final double[][][] RAW_SCREEN_CORNERS = rawScreenCorners();

    private RocketSkinQuadFilter() {}

    static boolean isStaticScreenFace(int[] vertices, int stride, int positionOffset) {
        if (vertices == null || stride < 3 || positionOffset < 0
                || positionOffset + 2 >= stride || vertices.length != 4 * stride) return false;
        for (double[][] corners : RAW_SCREEN_CORNERS) {
            int matchedCorners = 0;
            for (int vertex = 0; vertex < 4; vertex++) {
                int offset = vertex * stride + positionOffset;
                double x = Float.intBitsToFloat(vertices[offset]);
                double y = Float.intBitsToFloat(vertices[offset + 1]);
                double z = Float.intBitsToFloat(vertices[offset + 2]);
                for (int corner = 0; corner < 4; corner++) {
                    if ((matchedCorners & (1 << corner)) == 0
                            && Math.abs(x - corners[corner][0]) <= TOLERANCE
                            && Math.abs(y - corners[corner][1]) <= TOLERANCE
                            && Math.abs(z - corners[corner][2]) <= TOLERANCE) {
                        matchedCorners |= 1 << corner;
                        break;
                    }
                }
            }
            if (matchedCorners == 15) return true;
        }
        return false;
    }

    private static double[][][] rawScreenCorners() {
        double[][][] result = new double[4][4][3];
        for (int facing = 0; facing < 4; facing++) {
            var screen = RocketArcadeGeometry.screen(facing);
            var corners = new RocketArcadeGeometry.Point[]{screen.lowerMinX(), screen.lowerMaxX(),
                    screen.upperMaxX(), screen.upperMinX()};
            var normal = screen.normal();
            double offset = RocketArcadeGeometry.SCREEN_SURFACE_OFFSET;
            for (int corner = 0; corner < 4; corner++) {
                // Geometry.screen is the dynamic plane. Subtract its offset to match the baked model only.
                result[facing][corner][0] = corners[corner].x() - normal.x() * offset;
                result[facing][corner][1] = corners[corner].y() - normal.y() * offset;
                result[facing][corner][2] = corners[corner].z() - normal.z() * offset;
            }
        }
        return result;
    }
}
