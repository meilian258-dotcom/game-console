// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from PIQ FC DualCabinet lifecycle; isolated native cabinet IDs and six-cell ledger.
package cn.piq.nativearcade.world;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/** Six occupied cells: 2 wide, 3 high, 1 deep, rotated around the anchor block centre. */
public final class NativeCabinetFootprint {
    public static final int CELL_COUNT = 6;
    public static final double BODY_HEIGHT = 37.6;
    public enum Facing { NORTH, EAST, SOUTH, WEST }
    public record Cell(int part, int x, int y, int z) {}
    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {}
    private NativeCabinetFootprint() {}
    public static Cell cell(Facing facing, int part) {
        if (part < 0 || part >= CELL_COUNT) throw new IllegalArgumentException("Cabinet part outside six-cell layout");
        int x = part % 2, y = part / 2, z = 0;
        return switch (facing) {
            case NORTH -> new Cell(part, x, y, z);
            case EAST -> new Cell(part, -z, y, x);
            case SOUTH -> new Cell(part, -x, y, -z);
            case WEST -> new Cell(part, z, y, -x);
        };
    }
    public static List<Cell> cells(Facing facing) { return IntStream.range(0, CELL_COUNT).mapToObj(i -> cell(facing, i)).toList(); }
    public static boolean canPlace(Facing facing, Predicate<Cell> available) { return cells(facing).stream().allMatch(available); }
    public static Bounds bounds(Facing facing) {
        return switch (facing) {
            case NORTH -> new Bounds(0, 0, 0, 32, BODY_HEIGHT, 16);
            case EAST -> new Bounds(0, 0, 0, 16, BODY_HEIGHT, 32);
            case SOUTH -> new Bounds(-16, 0, 0, 16, BODY_HEIGHT, 16);
            case WEST -> new Bounds(0, 0, -16, 16, BODY_HEIGHT, 16);
        };
    }
    public static Bounds selection(Facing facing, int part) {
        var cell = cell(facing, part); var box = bounds(facing);
        return new Bounds(box.minX() - cell.x()*16, box.minY() - cell.y()*16, box.minZ() - cell.z()*16,
                box.maxX() - cell.x()*16, box.maxY() - cell.y()*16, box.maxZ() - cell.z()*16);
    }
    public static Bounds clipped(Facing facing, int part) {
        var box = selection(facing, part);
        // Only the occupied 5.6 units of the third row collide.
        if (box.maxX()<=0 || box.maxY()<=0 || box.maxZ()<=0
                || box.minX()>=16 || box.minY()>=16 || box.minZ()>=16)
            return new Bounds(0,0,0,0,0,0);
        return new Bounds(Math.max(0, box.minX()), Math.max(0, box.minY()), Math.max(0, box.minZ()),
                Math.min(16, box.maxX()), Math.min(16, box.maxY()), Math.min(16, box.maxZ()));
    }
}
