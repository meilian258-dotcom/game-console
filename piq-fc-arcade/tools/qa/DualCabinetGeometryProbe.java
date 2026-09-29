import cn.piq.fcarcade.layout.DualCabinetGeometry;
import cn.piq.fcarcade.layout.RocketArcadeGeometry.Point;

/** Executes the actual pure production geometry, without any Minecraft classes. */
public final class DualCabinetGeometryProbe {
    private static void point(Point p) { System.out.print(" " + p.x() + " " + p.y() + " " + p.z()); }
    public static void main(String[] args) {
        System.out.println("META " + DualCabinetGeometry.MODEL_SCALE + " " + DualCabinetGeometry.SCREEN_OFFSET + " " + DualCabinetGeometry.MODEL_Y_OFFSET);
        for (int turn = 0; turn < 4; turn++) {
            var q = DualCabinetGeometry.screen(turn);
            var b = DualCabinetGeometry.bounds(turn);
            System.out.print("TURN " + turn);
            point(q.lowerMinX()); point(q.lowerMaxX()); point(q.upperMaxX()); point(q.upperMinX()); point(q.normal());
            System.out.println(" " + b.minX() + " " + b.minY() + " " + b.minZ() + " " + b.maxX() + " " + b.maxY() + " " + b.maxZ());
        }
    }
}
