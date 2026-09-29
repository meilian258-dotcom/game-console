package cn.piq.fcarcade.layout;

import java.util.Optional;
import java.util.function.Predicate;

public final class TwoByTwoFormation {
    private TwoByTwoFormation() {
    }

    public static Optional<Offset> findAnchor(Predicate<Offset> isCell) {
        for (int verticalOffset = 0; verticalOffset <= 1; verticalOffset++) {
            for (int horizontalOffset = 0; horizontalOffset <= 1; horizontalOffset++) {
                Offset candidate = new Offset(-horizontalOffset, -verticalOffset);
                if (isExactTwoByTwo(candidate, isCell)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isExactTwoByTwo(
            Offset bottomLeft,
            Predicate<Offset> isCell
    ) {
        Offset bottomRight = bottomLeft.add(1, 0);
        if (!isCell.test(bottomLeft)
                || !isCell.test(bottomRight)
                || !isCell.test(bottomLeft.add(0, 1))
                || !isCell.test(bottomRight.add(0, 1))) {
            return false;
        }

        return !isCell.test(bottomLeft.add(-1, 0))
                && !isCell.test(bottomLeft.add(-1, 1))
                && !isCell.test(bottomLeft.add(2, 0))
                && !isCell.test(bottomLeft.add(2, 1))
                && !isCell.test(bottomLeft.add(0, -1))
                && !isCell.test(bottomRight.add(0, -1))
                && !isCell.test(bottomLeft.add(0, 2))
                && !isCell.test(bottomRight.add(0, 2));
    }

    public record Offset(int horizontal, int vertical) {
        Offset add(int horizontalDelta, int verticalDelta) {
            return new Offset(
                    horizontal + horizontalDelta,
                    vertical + verticalDelta);
        }
    }
}
