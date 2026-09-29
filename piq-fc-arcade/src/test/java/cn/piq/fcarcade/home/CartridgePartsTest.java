package cn.piq.fcarcade.home;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CartridgePartsTest {
    private static final String ROM = "a".repeat(64), COVER = "b".repeat(64);
    private CartridgeParts.Whole whole(int variant) { return new CartridgeParts.Whole(UUID.randomUUID(), ROM, "自定义游戏名", COVER, variant, 0); }
    @Test void splitSeparatesRomAndCoverWithoutLosingCustomTitle() {
        var source = whole(-1); var split = CartridgeParts.split(source, 2, UUID.randomUUID());
        assertEquals(ROM, split.board().rom()); assertEquals("自定义游戏名", split.board().internalTitle());
        assertEquals(COVER, split.shell().cover()); assertEquals(source.id(), split.board().id());
        assertEquals(1, split.board().revision()); assertEquals(2, split.board().variant());
    }
    @Test void shellStructurallyCannotStoreRomAndBoardCannotStoreCover() {
        assertArrayEquals(new String[]{"id", "cover"}, java.util.Arrays.stream(CartridgeParts.Shell.class.getRecordComponents()).map(c -> c.getName()).toArray(String[]::new));
        assertFalse(java.util.Arrays.stream(CartridgeParts.Board.class.getRecordComponents()).anyMatch(c -> c.getName().equals("cover")));
    }
    @Test void everyStableVariantSurvivesRepeatedAssemblyDespiteNewRandomCandidates() {
        for (int variant=0; variant<3; variant++) {
            var card=whole(-1); var parts=CartridgeParts.split(card,variant,UUID.randomUUID());
            for (int cycle=0; cycle<20; cycle++) {
                var restored=CartridgeParts.combine(parts.board(),parts.shell());
                assertEquals(variant,restored.variant()); assertEquals(card.title(),restored.title());
                parts=CartridgeParts.split(restored,(variant+cycle)%3,UUID.randomUUID());
                assertEquals(variant,parts.board().variant());
            }
        }
    }
    @Test void sameRomCanHaveDifferentRandomVariantsNotDerivedFromHash() {
        var card=whole(-1);
        assertNotEquals(CartridgeParts.split(card,0,UUID.randomUUID()).board().variant(),CartridgeParts.split(card,2,UUID.randomUUID()).board().variant());
    }
    @Test void mixedShellUsesBoardRomAndOriginalTitleButOtherShellCover() {
        var board=CartridgeParts.split(whole(1),0,UUID.randomUUID()).board();
        var result=CartridgeParts.combine(board,new CartridgeParts.Shell(UUID.randomUUID(),"c".repeat(64)));
        assertEquals(ROM,result.rom()); assertEquals("自定义游戏名",result.title()); assertEquals("c".repeat(64),result.cover());
        assertEquals(board.id(),result.id()); assertEquals(board.variant(),result.variant());
    }
    @Test void blankWholeAndBlankShellArePhysicalPartsWithoutInventedRom() {
        var card=new CartridgeParts.Whole(UUID.randomUUID(),"","","",-1,0);
        var parts=CartridgeParts.split(card,0,UUID.randomUUID());
        assertEquals("",CartridgeParts.combine(parts.board(),parts.shell()).rom());
    }
    @Test void badVariantsIdsHashesAndTitlesCannotEnterParts() {
        for (int variant:new int[]{-2,3,Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Whole(UUID.randomUUID(),ROM,"",COVER,variant,0));
        assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Board(UUID.randomUUID(),ROM,"",-1,0));
        assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Shell(CartridgeAssemblyBinding.ZERO,COVER));
        assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Board(UUID.randomUUID(),"bad","",1,0));
        assertThrows(IllegalArgumentException.class,()->new CartridgeParts.Board(UUID.randomUUID(),ROM,"bad\nname",1,0));
    }
    @Test void revisionOverflowRejectsWithoutWrappingIntoReplayableState() {
        var card=new CartridgeParts.Whole(UUID.randomUUID(),ROM,"",COVER,0,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->CartridgeParts.split(card,0,UUID.randomUUID()));
        var board=new CartridgeParts.Board(UUID.randomUUID(),ROM,"",0,Long.MAX_VALUE);
        assertThrows(ArithmeticException.class,()->CartridgeParts.combine(board,new CartridgeParts.Shell(UUID.randomUUID(),COVER)));
    }
}
