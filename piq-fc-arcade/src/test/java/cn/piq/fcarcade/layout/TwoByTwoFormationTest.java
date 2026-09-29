package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TwoByTwoFormationTest {
    @Test
    void everyMemberResolvesToSameAnchor() {
        Set<TwoByTwoFormation.Offset> absoluteCells = twoByTwo(8, 15);
        for (TwoByTwoFormation.Offset member : absoluteCells) {
            var found = TwoByTwoFormation.findAnchor(relative -> absoluteCells.contains(
                    new TwoByTwoFormation.Offset(
                            member.horizontal() + relative.horizontal(),
                            member.vertical() + relative.vertical())));

            assertTrue(found.isPresent());
            assertEquals(
                    new TwoByTwoFormation.Offset(
                            8 - member.horizontal(),
                            15 - member.vertical()),
                    found.get());
        }
    }

    @Test
    void incompleteFormationFallsBackToSingleScreen() {
        Set<TwoByTwoFormation.Offset> cells = twoByTwo(0, 0);
        cells.remove(new TwoByTwoFormation.Offset(0, 1));

        assertTrue(TwoByTwoFormation.findAnchor(cells::contains).isEmpty());
    }

    @Test
    void largerArrayDoesNotCreateOverlappingScreens() {
        Set<TwoByTwoFormation.Offset> cells = twoByTwo(0, 0);
        cells.add(new TwoByTwoFormation.Offset(2, 0));
        cells.add(new TwoByTwoFormation.Offset(2, 1));

        assertTrue(TwoByTwoFormation.findAnchor(cells::contains).isEmpty());
    }

    private static Set<TwoByTwoFormation.Offset> twoByTwo(int left, int bottom) {
        Set<TwoByTwoFormation.Offset> cells = new HashSet<>();
        cells.add(new TwoByTwoFormation.Offset(left, bottom));
        cells.add(new TwoByTwoFormation.Offset(left + 1, bottom));
        cells.add(new TwoByTwoFormation.Offset(left, bottom + 1));
        cells.add(new TwoByTwoFormation.Offset(left + 1, bottom + 1));
        return cells;
    }
}
