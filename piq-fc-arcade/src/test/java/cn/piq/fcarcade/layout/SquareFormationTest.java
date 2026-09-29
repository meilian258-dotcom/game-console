package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SquareFormationTest {
    @Test
    void everyThreeByThreeMemberResolvesToSameAnchor() {
        Set<SquareFormation.Offset> cells = square(3, 8, 15);
        for (SquareFormation.Offset member : cells) {
            var found = SquareFormation.findAnchor(3, relative -> cells.contains(
                    new SquareFormation.Offset(
                            member.horizontal() + relative.horizontal(),
                            member.vertical() + relative.vertical())));

            assertTrue(found.isPresent());
            assertEquals(
                    new SquareFormation.Offset(
                            8 - member.horizontal(),
                            15 - member.vertical()),
                    found.get());
        }
    }

    @Test
    void incompleteThreeByThreeIsRejected() {
        Set<SquareFormation.Offset> cells = square(3, 0, 0);
        cells.remove(new SquareFormation.Offset(2, 2));

        assertTrue(SquareFormation.findAnchor(3, cells::contains).isEmpty());
    }

    @Test
    void largerArrayDoesNotCreateOverlappingThreeByThreeScreens() {
        Set<SquareFormation.Offset> cells = square(3, 0, 0);
        cells.add(new SquareFormation.Offset(3, 0));

        assertTrue(SquareFormation.findAnchor(3, cells::contains).isEmpty());
    }

    private static Set<SquareFormation.Offset> square(
            int size,
            int left,
            int bottom
    ) {
        Set<SquareFormation.Offset> cells = new HashSet<>();
        for (int vertical = 0; vertical < size; vertical++) {
            for (int horizontal = 0; horizontal < size; horizontal++) {
                cells.add(new SquareFormation.Offset(
                        left + horizontal,
                        bottom + vertical));
            }
        }
        return cells;
    }
}
