package cn.piq.fcarcade.layout;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RectangularFormationTest {
    @Test
    void completeFourByThreePanelResolvesToOneBounds() {
        Set<RectangularFormation.Cell> cells = rectangle(4, 3, -2, -1);

        var result = RectangularFormation.resolve(cells, 8);

        assertTrue(result.isPresent());
        assertEquals(
                new RectangularFormation.Bounds(-2, -1, 4, 3),
                result.get());
    }

    @Test
    void missingPanelRejectsTheCombinedDisplay() {
        Set<RectangularFormation.Cell> cells = rectangle(3, 2, 0, 0);
        cells.remove(new RectangularFormation.Cell(1, 1));

        assertTrue(RectangularFormation.resolve(cells, 8).isEmpty());
    }

    @Test
    void dimensionAboveEightIsRejected() {
        assertTrue(RectangularFormation.resolve(
                rectangle(9, 1, 0, 0),
                8).isEmpty());
    }

    @Test
    void everyPanelMemberCalculatesTheSameAbsoluteAnchor() {
        Set<RectangularFormation.Cell> absolute = rectangle(5, 3, 7, 12);
        for (RectangularFormation.Cell member : absolute) {
            Set<RectangularFormation.Cell> relative = new HashSet<>();
            for (RectangularFormation.Cell cell : absolute) {
                relative.add(new RectangularFormation.Cell(
                        cell.horizontal() - member.horizontal(),
                        cell.vertical() - member.vertical()));
            }
            var bounds = RectangularFormation.resolve(relative, 8);

            assertTrue(bounds.isPresent());
            assertEquals(
                    7,
                    member.horizontal()
                            + bounds.get().minimumHorizontal());
            assertEquals(
                    12,
                    member.vertical()
                            + bounds.get().minimumVertical());
        }
    }

    private static Set<RectangularFormation.Cell> rectangle(
            int width,
            int height,
            int left,
            int bottom
    ) {
        Set<RectangularFormation.Cell> cells = new HashSet<>();
        for (int vertical = 0; vertical < height; vertical++) {
            for (int horizontal = 0; horizontal < width; horizontal++) {
                cells.add(new RectangularFormation.Cell(
                        left + horizontal,
                        bottom + vertical));
            }
        }
        return cells;
    }
}
