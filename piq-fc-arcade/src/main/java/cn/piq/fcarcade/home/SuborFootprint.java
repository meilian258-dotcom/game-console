package cn.piq.fcarcade.home;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/** Versioned horizontal cells; old worlds retain four cells, compact placements use two. */
public final class SuborFootprint {
    public enum Facing { NORTH, EAST, SOUTH, WEST }
    public record Cell(int part, int x, int y, int z) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    private SuborFootprint() {}
    public static int partCount(boolean compact) { return compact ? 2 : 4; }
    public static Cell cell(Facing facing, int part) {
        return cell(facing, part, false);
    }
    public static Cell cell(Facing facing, int part, boolean compact) {
        if (part < 0 || part >= partCount(compact)) throw new IllegalArgumentException("Subor part outside layout");
        int x = part & 1, z = part >> 1;
        return switch (facing) {
            case NORTH -> new Cell(part, x, 0, z);
            case EAST -> new Cell(part, -z, 0, x);
            case SOUTH -> new Cell(part, -x, 0, -z);
            case WEST -> new Cell(part, z, 0, -x);
        };
    }
    public static List<Cell> cells(Facing facing) { return cells(facing, false); }
    public static List<Cell> cells(Facing facing, boolean compact) {
        return IntStream.range(0, partCount(compact)).mapToObj(i -> cell(facing, i, compact)).toList();
    }
    public static boolean canPlace(Facing facing, Predicate<Cell> available) { return cells(facing).stream().allMatch(available); }
    public static boolean canPlace(Facing facing, boolean compact, Predicate<Cell> available) {
        return cells(facing, compact).stream().allMatch(available);
    }
    public static Bounds selection(Facing facing, int part) {
        return selection(facing, part, false);
    }
    public static Bounds selection(Facing facing, int part, boolean compact) {
        var cell = cell(facing, part, compact); var box = HomeConsoleLayout.suborBounds(facing.ordinal(), true, compact);
        return new Bounds(box.minX() - cell.x() * 16, box.minY(), box.minZ() - cell.z() * 16,
                box.maxX() - cell.x() * 16, box.maxY(), box.maxZ() - cell.z() * 16);
    }
    public static Bounds clipped(Facing facing, int part) {
        return clipped(facing, part, false);
    }
    public static Bounds clipped(Facing facing, int part, boolean compact) {
        var box = selection(facing, part, compact);
        return new Bounds(Math.max(0, box.minX()), Math.max(0, box.minY()), Math.max(0, box.minZ()),
                Math.min(16, box.maxX()), Math.min(16, box.maxY()), Math.min(16, box.maxZ()));
    }
}
