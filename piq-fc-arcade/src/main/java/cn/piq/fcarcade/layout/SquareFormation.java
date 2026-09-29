package cn.piq.fcarcade.layout;

import java.util.Optional;
import java.util.function.Predicate;

public final class SquareFormation {
    private SquareFormation() {
    }

    public static Optional<Offset> findAnchor(
            int size,
            Predicate<Offset> isCell
    ) {
        if (size < 2) {
            throw new IllegalArgumentException("Square size must be at least 2");
        }
        for (int verticalOffset = 0; verticalOffset < size; verticalOffset++) {
            for (int horizontalOffset = 0; horizontalOffset < size; horizontalOffset++) {
                Offset candidate = new Offset(-horizontalOffset, -verticalOffset);
                if (isExactSquare(candidate, size, isCell)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isExactSquare(
            Offset bottomLeft,
            int size,
            Predicate<Offset> isCell
    ) {
        for (int vertical = 0; vertical < size; vertical++) {
            for (int horizontal = 0; horizontal < size; horizontal++) {
                if (!isCell.test(bottomLeft.add(horizontal, vertical))) {
                    return false;
                }
            }
        }

        for (int index = 0; index < size; index++) {
            if (isCell.test(bottomLeft.add(-1, index))
                    || isCell.test(bottomLeft.add(size, index))
                    || isCell.test(bottomLeft.add(index, -1))
                    || isCell.test(bottomLeft.add(index, size))) {
                return false;
            }
        }
        return true;
    }

    public record Offset(int horizontal, int vertical) {
        Offset add(int horizontalDelta, int verticalDelta) {
            return new Offset(
                    horizontal + horizontalDelta,
                    vertical + verticalDelta);
        }
    }
}
