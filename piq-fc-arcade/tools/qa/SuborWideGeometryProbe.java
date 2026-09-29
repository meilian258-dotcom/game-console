import cn.piq.fcarcade.home.HomeConsoleLayout;

public final class SuborWideGeometryProbe {
    public static void main(String[] args) {
        System.out.println("WIDE " + HomeConsoleLayout.WIDE_CARD_X + " " + HomeConsoleLayout.WIDE_CARD_Y + " " + HomeConsoleLayout.WIDE_CARD_Z + " " + HomeConsoleLayout.WIDE_CARD_SCALE);
        System.out.println("OLD " + HomeConsoleLayout.CARD_X + " " + HomeConsoleLayout.CARD_Y + " " + HomeConsoleLayout.CARD_Z + " " + HomeConsoleLayout.CARD_SCALE);
        for (int turn=0;turn<4;turn++) {
            var b=HomeConsoleLayout.suborBounds(turn,true);
            System.out.println("BOUNDS " + turn + " " + b.minX() + " " + b.minY() + " " + b.minZ() + " " + b.maxX() + " " + b.maxY() + " " + b.maxZ());
        }
    }
}
