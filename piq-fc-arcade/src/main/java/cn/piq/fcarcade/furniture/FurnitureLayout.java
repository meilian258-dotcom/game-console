package cn.piq.fcarcade.furniture;

/** Coordinates are block units, rotating the original model about (8, 8), never scaling it. */
public final class FurnitureLayout {
    public record Point(double x, double y, double z) {}
    public record Cell(int x, int z) {}
    private FurnitureLayout() {}
    public static Cell second(int turns) {
        return switch (Math.floorMod(turns,4)) {
            case 1 -> new Cell(0,1); case 2 -> new Cell(-1,0); case 3 -> new Cell(0,-1);
            default -> new Cell(1,0);
        };
    }
    public static Point rotate(Point point, int turns) {
        double x=point.x-.5, z=point.z-.5;
        return switch (Math.floorMod(turns,4)) {
            case 1 -> new Point(.5-z, point.y, .5+x);
            case 2 -> new Point(.5-x, point.y, .5-z);
            case 3 -> new Point(.5+z, point.y, .5-x);
            default -> point;
        };
    }
    public static Point seat(boolean bench, int seat, int turns) {
        if (seat<0 || seat>=(bench?2:1)) throw new IllegalArgumentException("seat");
        return rotate(new Point(bench?.5+seat:.5, bench?.5:6.1513896/16, .5),turns);
    }
}
