package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HomeControllerInventoryTest {
    private record Stack(UUID id, int count) {}
    private static HomeControllerInventory.Selection<Stack> locate(UUID id, Stack... slots) {
        return HomeControllerInventory.locate(id, List.of(slots), Stack::id, Stack::count);
    }
    @Test void shiftClickCopyAndCursorSplitAreRelocatedWithoutLosingAuthority() {
        UUID id = UUID.randomUUID(); var original = new Stack(id, 1);
        assertSame(original, locate(id, original).value());
        // Vanilla quickMove creates a different stack object, empties source.
        var moved = new Stack(id, 1); assertNotSame(original, moved);
        assertSame(moved, locate(id, new Stack(id, 0), moved).value());
        // Ordinary cursor pickup and putting it into another slot copy again.
        var cursor = new Stack(id, 1);
        assertSame(cursor, locate(id, new Stack(id, 0), new Stack(null, 0), cursor).value());
        var placed = new Stack(id, 1);
        assertSame(placed, locate(id, placed, new Stack(id, 0)).value());
    }
    @Test void twoCopiesOrStackedTokensCannotProvideInputOrTransferReceipt() {
        UUID id = UUID.randomUUID();
        assertEquals(HomeControllerInventory.Status.AMBIGUOUS, locate(id, new Stack(id, 1), new Stack(id, 1)).status());
        assertEquals(HomeControllerInventory.Status.AMBIGUOUS, locate(id, new Stack(id, 2)).status());
        assertEquals(HomeControllerInventory.Status.UNIQUE, locate(id, new Stack(id, 1), new Stack(UUID.randomUUID(), 1)).status());
    }
    @Test void onlyActualRemovalFromBothInventoryAndCursorIsMissing() {
        UUID id = UUID.randomUUID();
        assertEquals(HomeControllerInventory.Status.UNIQUE, locate(id, new Stack(id, 0), new Stack(id, 1)).status());
        assertEquals(HomeControllerInventory.Status.MISSING, locate(id, new Stack(id, 0), new Stack(null, 0)).status());
    }
    @Test void outsideGuiDropAcceptsOnlyItsExactSoleCursorDuringVanillaTransaction() {
        UUID id = UUID.randomUUID(); var cursor = new Stack(id, 1); var copy = new Stack(id, 1);
        assertTrue(HomeControllerInventory.removedForToss(locate(id), null, cursor)); // Q already removed source.
        assertTrue(HomeControllerInventory.removedForToss(locate(id, cursor), cursor, cursor)); // GUI clears afterward.
        assertFalse(HomeControllerInventory.removedForToss(locate(id, cursor), cursor, copy));
        assertFalse(HomeControllerInventory.removedForToss(locate(id, cursor, copy), cursor, cursor));
    }
}
