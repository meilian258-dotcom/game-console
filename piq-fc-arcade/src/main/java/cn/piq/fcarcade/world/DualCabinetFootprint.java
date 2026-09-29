package cn.piq.fcarcade.world;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/** Legacy twelve-cell and compact 2 wide / 2 high / 1 deep layouts. */
public final class DualCabinetFootprint {
    public static final int CELL_COUNT = 12;
    public static final int COMPACT_CELL_COUNT = 4;
    public static final double BODY_HEIGHT = 32;
    public static final double BODY_BACK = 32;
    public static final double BODY_FRONT = BODY_BACK - 17.6;
    public enum Facing { NORTH, EAST, SOUTH, WEST }
    public record Cell(int part, int x, int y, int z) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    private DualCabinetFootprint() {}
    public static Cell cell(Facing facing, int part) {
        if (part < 0 || part >= CELL_COUNT) throw new IllegalArgumentException("Cabinet part outside twelve-cell layout");
        int x = part % 2, y = (part / 2) % 3, z = part / 6;
        return switch (facing) {
            case NORTH -> new Cell(part, x, y, z);
            case EAST -> new Cell(part, -z, y, x);
            case SOUTH -> new Cell(part, -x, y, -z);
            case WEST -> new Cell(part, z, y, -x);
        };
    }
    public static List<Cell> cells(Facing facing) { return IntStream.range(0, CELL_COUNT).mapToObj(i -> cell(facing, i)).toList(); }
    public static int count(boolean compact) { return compact ? COMPACT_CELL_COUNT : CELL_COUNT; }
    public static Cell cell(Facing facing, int part, boolean compact) {
        if (part < 0 || part >= count(compact)) throw new IllegalArgumentException("Part outside cabinet layout");
        return cell(facing, part);
    }
    public static List<Cell> cells(Facing facing, boolean compact) {
        return IntStream.range(0, count(compact)).mapToObj(i -> cell(facing, i, compact)).toList();
    }
    public static boolean canPlace(Facing facing, boolean compact, Predicate<Cell> available) {
        return cells(facing, compact).stream().allMatch(available);
    }
    public static boolean canPlace(Facing facing, Predicate<Cell> available) { return cells(facing).stream().allMatch(available); }
    public static Bounds bounds(Facing facing) {
        return bounds(facing, false);
    }
    public static Bounds bounds(Facing facing, boolean compact) {
        double back = compact ? 16 : BODY_BACK;
        double front = back - 17.6;
        return switch (facing) {
            case NORTH -> new Bounds(4, 0, front, 28, BODY_HEIGHT, back);
            case EAST -> new Bounds(16-back, 0, 4, 16-front, BODY_HEIGHT, 28);
            case SOUTH -> new Bounds(-12, 0, 16-back, 12, BODY_HEIGHT, 16-front);
            case WEST -> new Bounds(front, 0, -12, back, BODY_HEIGHT, 12);
        };
    }
    public static Bounds selection(Facing facing, int part) {
        return selection(facing, part, false);
    }
    public static Bounds selection(Facing facing, int part, boolean compact) {
        var cell = cell(facing, part, compact); var box = bounds(facing, compact);
        return new Bounds(box.minX() - cell.x()*16, box.minY() - cell.y()*16, box.minZ() - cell.z()*16,
                box.maxX() - cell.x()*16, box.maxY() - cell.y()*16, box.maxZ() - cell.z()*16);
    }
    public static Bounds clipped(Facing facing, int part) {
        return clipped(facing, part, false);
    }
    public static Bounds clipped(Facing facing, int part, boolean compact) {
        var box = selection(facing, part, compact);
        // Clip at occupied cells: the compact front overhang is visual only.
        if (box.maxX()<=0 || box.maxY()<=0 || box.maxZ()<=0
                || box.minX()>=16 || box.minY()>=16 || box.minZ()>=16)
            return new Bounds(0,0,0,0,0,0);
        return new Bounds(Math.max(0, box.minX()), Math.max(0, box.minY()), Math.max(0, box.minZ()),
                Math.min(16, box.maxX()), Math.min(16, box.maxY()), Math.min(16, box.maxZ()));
    }
}
