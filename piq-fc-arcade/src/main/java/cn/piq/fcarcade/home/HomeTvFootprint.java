package cn.piq.fcarcade.home;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/** Persisted legacy/centred occupied cells, rotated around the vanilla anchor block's centre. */
public final class HomeTvFootprint {
    public enum Facing { NORTH, EAST, SOUTH, WEST }
    public record Cell(int part, int x, int y, int z) {}
    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {}

    private HomeTvFootprint() {}

    public static int cellCount(boolean centered) {
        return centered ? 12 : 8;
    }

    public static Cell cell(Facing facing, int part) {
        return cell(facing, part, false);
    }

    public static Cell cell(Facing facing, int part, boolean centered) {
        if (part < 0 || part >= cellCount(centered)) throw new IllegalArgumentException("TV part outside layout");
        // The anchor is always first. Centred TVs reserve the two half-covered
        // neighbouring columns without moving the authoritative block position.
        int x = centered ? switch (part % 3) { case 1 -> -1; case 2 -> 1; default -> 0; } : part & 1;
        int y = centered ? (part / 3) % 2 : (part >> 1) & 1;
        int z = centered ? part / 6 : (part >> 2) & 1;
        return switch (facing) {
            case NORTH -> new Cell(part, x, y, z);
            case EAST -> new Cell(part, -z, y, x);
            case SOUTH -> new Cell(part, -x, y, -z);
            case WEST -> new Cell(part, z, y, -x);
        };
    }

    public static List<Cell> cells(Facing facing) {
        return cells(facing, false);
    }

    public static List<Cell> cells(Facing facing, boolean centered) {
        return IntStream.range(0, cellCount(centered)).mapToObj(part -> cell(facing, part, centered)).toList();
    }

    /** One failing cell rejects the entire placement; the predicate must not load chunks. */
    public static boolean canPlace(Facing facing, Predicate<Cell> available) {
        return canPlace(facing, false, available);
    }

    public static boolean canPlace(Facing facing, boolean centered, Predicate<Cell> available) {
        return cells(facing, centered).stream().allMatch(available);
    }

    public static Bounds bounds(Facing facing) {
        return bounds(facing, false);
    }

    public static Bounds bounds(Facing facing, boolean centered) {
        var min = HomeHardwareScale.tvPoint(0, 0, 0.399);
        var max = HomeHardwareScale.tvPoint(16, 12.7, 14.455);
        // Width only: translate the already-scaled north model by half a block,
        // before applying the unchanged vanilla blockstate rotation.
        double minX = min.x() - (centered ? 8 : 0);
        double maxX = max.x() - (centered ? 8 : 0);
        return switch (facing) {
            case NORTH -> new Bounds(minX, min.y(), min.z(), maxX, max.y(), max.z());
            case EAST -> new Bounds(16 - max.z(), min.y(), minX, 16 - min.z(), max.y(), maxX);
            case SOUTH -> new Bounds(16 - maxX, min.y(), 16 - max.z(), 16 - minX, max.y(), 16 - min.z());
            case WEST -> new Bounds(min.z(), min.y(), 16 - maxX, max.z(), max.y(), 16 - minX);
        };
    }

    /** Cell-local collision/selection box, always contained by this occupied block. */
    public static Bounds clipped(Facing facing, int part) {
        return clipped(facing, part, false);
    }

    public static Bounds clipped(Facing facing, int part, boolean centered) {
        var cell = cell(facing, part, centered);
        var full = bounds(facing, centered);
        double x = cell.x() * 16.0, y = cell.y() * 16.0, z = cell.z() * 16.0;
        return new Bounds(Math.max(0, full.minX() - x), Math.max(0, full.minY() - y),
                Math.max(0, full.minZ() - z), Math.min(16, full.maxX() - x),
                Math.min(16, full.maxY() - y), Math.min(16, full.maxZ() - z));
    }

    /** Whole-machine selection outline expressed relative to whichever part was hit. */
    public static Bounds selection(Facing facing, int part) {
        return selection(facing, part, false);
    }

    public static Bounds selection(Facing facing, int part, boolean centered) {
        var cell = cell(facing, part, centered);
        var box = bounds(facing, centered);
        double x = cell.x() * 16.0, y = cell.y() * 16.0, z = cell.z() * 16.0;
        return new Bounds(box.minX() - x, box.minY() - y, box.minZ() - z,
                box.maxX() - x, box.maxY() - y, box.maxZ() - z);
    }
}
