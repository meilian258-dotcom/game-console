package cn.piq.fcarcade.layout;

import java.util.Optional;
import java.util.Set;

public final class RectangularFormation {
    private RectangularFormation() {
    }

    public static Optional<Bounds> resolve(
            Set<Cell> cells,
            int maximumDimension
    ) {
        if (cells.isEmpty() || maximumDimension < 1) return Optional.empty();
        int minHorizontal = cells.stream()
                .mapToInt(Cell::horizontal)
                .min()
                .orElse(0);
        int maxHorizontal = cells.stream()
                .mapToInt(Cell::horizontal)
                .max()
                .orElse(0);
        int minVertical = cells.stream()
                .mapToInt(Cell::vertical)
                .min()
                .orElse(0);
        int maxVertical = cells.stream()
                .mapToInt(Cell::vertical)
                .max()
                .orElse(0);
        int width = maxHorizontal - minHorizontal + 1;
        int height = maxVertical - minVertical + 1;
        if (width > maximumDimension
                || height > maximumDimension
                || cells.size() != width * height) {
            return Optional.empty();
        }
        for (int vertical = minVertical;
             vertical <= maxVertical;
             vertical++) {
            for (int horizontal = minHorizontal;
                 horizontal <= maxHorizontal;
                 horizontal++) {
                if (!cells.contains(new Cell(horizontal, vertical))) {
                    return Optional.empty();
                }
            }
        }
        return Optional.of(new Bounds(
                minHorizontal,
                minVertical,
                width,
                height));
    }

    public record Cell(int horizontal, int vertical) {
    }

    public record Bounds(
            int minimumHorizontal,
            int minimumVertical,
            int width,
            int height
    ) {
    }
}
