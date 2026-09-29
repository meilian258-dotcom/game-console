package cn.piq.sfchome.server;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcControllerInventoryTest {
    record Item(UUID lease,int count){}
    private Item locate(UUID lease,Item...items){return SfcControllerInventory.unique(lease,Arrays.asList(items),Item::lease,Item::count);}
    @Test void ordinaryAndAliasedInventoryViewsFindTheSameOneItem(){var id=UUID.randomUUID();var item=new Item(id,1);assertSame(item,locate(id,item));assertSame(item,locate(id,item,item,item));}
    @Test void DistinctEqualCopiesAreNeverCoalesced(){var id=UUID.randomUUID();var a=new Item(id,1);var b=new Item(id,1);assertEquals(a,b);assertNull(locate(id,a,b));assertNull(locate(id,a,a,b,b));}
    @Test void allSingleCopyMovesRemainOwnedWithoutDependingOnObjectIdentity(){var id=UUID.randomUUID();for(int slot=0;slot<52;slot++){Item[] items=new Item[52];var item=new Item(id,1);items[slot]=item;assertSame(item,locate(id,items));}}
    @Test void anotherLeaseAndNullSlotsCannotGrantThisLease(){var id=UUID.randomUUID();assertNull(locate(id,null,new Item(UUID.randomUUID(),1)));assertNull(locate(null,new Item(id,1)));}
    @Test void malformedCountsFailClosedEvenWithOtherwiseValidCopy(){var id=UUID.randomUUID();for(int n:new int[]{-1,0,2,64}){assertNull(locate(id,new Item(id,n)));assertNull(locate(id,new Item(id,n),new Item(id,1)));}}
    @Test void aMissingControllerDoesNotBecomeOwnedWhenOtherItemsExist(){assertNull(locate(UUID.randomUUID(),new Item(UUID.randomUUID(),1),new Item(UUID.randomUUID(),1)));}
    @Test void qDropAcceptsTheSoleRemovedReceiptWithAnEmptySource(){
        var id=UUID.randomUUID();var drop=new Item(id,1);
        assertTrue(SfcControllerInventory.removedForToss(id,List.of(new Item(id,0)),null,drop,Item::lease,Item::count));
    }
    @Test void outsideGuiDropAcceptsOnlyItsExactCursorAlias(){
        var id=UUID.randomUUID();var cursor=new Item(id,1);var copy=new Item(id,1);
        assertTrue(SfcControllerInventory.removedForToss(id,List.of(cursor,cursor),cursor,cursor,Item::lease,Item::count));
        assertFalse(SfcControllerInventory.removedForToss(id,List.of(cursor),cursor,copy,Item::lease,Item::count));
        assertFalse(SfcControllerInventory.removedForToss(id,List.of(cursor,copy),cursor,cursor,Item::lease,Item::count));
    }
    @Test void copiedTokensOrWrongLeaseCannotRevokeTheRealReceipt(){
        var id=UUID.randomUUID();var real=new Item(id,1);var copy=new Item(id,1);
        assertFalse(SfcControllerInventory.removedForToss(id,List.of(real),null,copy,Item::lease,Item::count));
        assertFalse(SfcControllerInventory.removedForToss(id,List.of(),null,new Item(UUID.randomUUID(),1),Item::lease,Item::count));
        for(int count:new int[]{-1,0,2,64})assertFalse(SfcControllerInventory.removedForToss(id,List.of(),null,new Item(id,count),Item::lease,Item::count));
    }
    @Test void droppingEitherPortCannotMatchTheOtherPlayersAuthority(){
        var p1=UUID.randomUUID();var p2=UUID.randomUUID();var l1=UUID.randomUUID();var l2=UUID.randomUUID();
        var first=new SfcControllerAuthority(l1,p1,0);var second=new SfcControllerAuthority(l2,p2,1);
        assertTrue(first.accepts(p1,l1));assertTrue(second.accepts(p2,l2));
        assertFalse(first.accepts(p2,l1));assertFalse(second.accepts(p1,l2));
        assertFalse(first.itemMatches(true,l2,1,1));assertFalse(second.itemMatches(true,l1,0,1));
        var drop=new Item(l1,1);var other=new Item(l2,1);
        assertTrue(SfcControllerInventory.removedForToss(l1,List.of(other),null,drop,Item::lease,Item::count));
        assertSame(other,locate(l2,other));
    }
    @Test void unheldPacketsConsumeSequenceAndClearOnlyTheirPort(){
        var p1=new SfcInputTimeline();var p2=new SfcInputTimeline();assertTrue(p1.offer(0,1,false));assertTrue(p2.offer(0,512,false));
        var neutral=SfcControllerAuthority.input(false,4095,false);assertNotNull(neutral);assertEquals(0,neutral.mask());assertTrue(neutral.release());
        assertTrue(p1.offer(1,neutral.mask(),neutral.release()));assertEquals(0,p1.pending());assertEquals(0,p1.next());assertEquals(512,p2.next());
        assertFalse(p1.offer(1,4095,false));assertTrue(p1.offer(2,1,false));assertEquals(1,p1.next());
    }
    @Test void everyUnheldTwelveBitInputIsNeutralWhileEveryHeldBitIsPreserved(){
        for(int mask=0;mask<4096;mask++){
            var held=SfcControllerAuthority.input(true,mask,false);assertEquals(mask,held.mask());assertFalse(held.release());
            var stored=SfcControllerAuthority.input(false,mask,false);assertEquals(0,stored.mask());assertTrue(stored.release());
        }
    }
    @Test void invalidProtocolValuesAreNotSanitizedIntoValidHeartbeats(){
        for(boolean held:new boolean[]{false,true}){
            assertNull(SfcControllerAuthority.input(held,-1,false));assertNull(SfcControllerAuthority.input(held,4096,false));
            assertNull(SfcControllerAuthority.input(held,1,true));var zero=SfcControllerAuthority.input(held,0,true);assertEquals(0,zero.mask());assertTrue(zero.release());
        }
    }
    @Test void neutralStoredHeartbeatsKeepWatchdogAliveWithoutWakingTheCoreInput(){
        var timeline=new SfcInputTimeline();var health=new SfcInputHealth();health.start(0);
        for(int tick=0;tick<1000;tick+=10){var input=SfcControllerAuthority.input(false,128,false);assertTrue(health.packet(0,tick));assertTrue(timeline.offer(tick,input.mask(),input.release()));assertFalse(health.expiredPort(0,tick));assertEquals(0,timeline.next());}
        assertTrue(health.expiredPort(0,1101));
    }
}
