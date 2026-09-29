package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CartridgeAssemblyInventoryTest {
    private record Stack(String kind) {}
    private final Stack empty=new Stack("empty"),card=new Stack("card"),board=new Stack("board"),shell=new Stack("shell");
    private boolean empty(Stack item) { return item==empty; }
    private List<Stack> inventory() { var slots=new ArrayList<Stack>(Collections.nCopies(36,empty));slots.set(3,card);return slots; }
    @Test void splitReplacesExactlyOneCardWithOneBoardAndOneShell() {
        var slots=inventory();
        assertTrue(CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));
        assertSame(board,slots.get(3)); assertSame(shell,slots.get(0)); assertEquals(2,slots.stream().filter(s->!empty(s)).count());
    }
    @Test void fullInventoryDoesNotConsumeAnythingOrCreateAnOutput() {
        var slots=new ArrayList<Stack>(Collections.nCopies(36,card));var before=new ArrayList<>(slots);
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));assertEquals(before,slots);
    }
    @Test void staleObjectAndReplayCannotAffectAnotherStack() {
        var slots=inventory();var stale=new Stack("card");
        assertFalse(CartridgeAssemblyInventory.split(slots,3,stale,board,shell,this::empty));
        assertTrue(CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));
        assertEquals(2,slots.stream().filter(s->!empty(s)).count());
    }
    @Test void aliasedOrEmptyOutputsAreNeverCommitted() {
        var slots=inventory();
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,board,board,this::empty));
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,card,shell,this::empty));
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,board,empty,this::empty));
        assertSame(card,slots.get(3));
        slots.set(3,board);var off=new ArrayList<>(List.of(shell));
        assertFalse(CartridgeAssemblyInventory.combine(slots,off,3,board,shell,board,empty,this::empty));
        assertFalse(CartridgeAssemblyInventory.combine(slots,off,3,board,shell,shell,empty,this::empty));
        assertSame(board,slots.get(3));assertSame(shell,off.get(0));
    }
    @Test void splitDoesNotTreatArmorOrOffhandAsAnEmptyBackpackSlot() {
        var slots=new ArrayList<Stack>(Collections.nCopies(41,card));slots.set(40,empty);
        assertFalse(CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));assertSame(card,slots.get(3));
    }
    @Test void combineWorksInFullInventoryAndConsumesOffhandForAllGameModes() {
        for(boolean creative:new boolean[]{false,true}) { // There is intentionally no creative bypass in the transaction.
            var slots=new ArrayList<Stack>(Collections.nCopies(36,card));slots.set(3,board);var off=new ArrayList<>(List.of(shell));
            assertTrue(CartridgeAssemblyInventory.combine(slots,off,3,board,shell,card,empty,this::empty));
            assertSame(card,slots.get(3));assertSame(empty,off.get(0));
            assertFalse(CartridgeAssemblyInventory.combine(slots,off,3,board,shell,card,empty,this::empty));
        }
    }
    @Test void wrongOrAliasedSecondHandCannotCombine() {
        var slots=inventory();slots.set(3,board);var off=new ArrayList<>(List.of(empty));
        assertFalse(CartridgeAssemblyInventory.combine(slots,off,3,board,shell,card,empty,this::empty));
        off.set(0,board);assertFalse(CartridgeAssemblyInventory.combine(slots,off,3,board,board,card,empty,this::empty));
        assertSame(board,slots.get(3));
    }
    @Test void throwingSecondSlotWriteRollsBackTheOriginalCard() {
        var slots=new ArrayList<Stack>(inventory()) {
            boolean throwOnce=true;
            @Override public Stack set(int i,Stack item) {
                if(i==0&&item==shell&&throwOnce){throwOnce=false;throw new IllegalStateException("injected");}
                return super.set(i,item);
            }
        };
        assertThrows(IllegalStateException.class,()->CartridgeAssemblyInventory.split(slots,3,card,board,shell,this::empty));
        assertSame(card,slots.get(3));assertSame(empty,slots.get(0));
    }
    @Test void throwingWholeWriteRestoresOffhandShell() {
        var slots=new ArrayList<Stack>(inventory()) {
            boolean throwOnce=true;
            @Override public Stack set(int i,Stack item) {
                if(i==3&&item==card&&throwOnce){throwOnce=false;throw new IllegalStateException("injected");}
                return super.set(i,item);
            }
        };
        slots.set(3,board);var off=new ArrayList<>(List.of(shell));
        assertThrows(IllegalStateException.class,()->CartridgeAssemblyInventory.combine(slots,off,3,board,shell,card,empty,this::empty));
        assertSame(board,slots.get(3));assertSame(shell,off.get(0));
    }
}
