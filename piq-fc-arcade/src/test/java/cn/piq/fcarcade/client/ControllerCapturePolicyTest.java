package cn.piq.fcarcade.client;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControllerCapturePolicyTest {
    private final UUID player=UUID.randomUUID(),lease=UUID.randomUUID();
    private record Stack(UUID lease,int count){}
    private boolean valid(double distance){return ControllerCapturePolicy.receipt(player,lease,0,player,lease,false,distance);}
    @Test void exactServerVisualReceiptAtSixBlocksCapturesButDoesNotDescribeRuntime(){assertTrue(valid(36));assertTrue(valid(0));assertFalse(valid(Math.nextUp(36.0)));}
    @Test void unknownAndInvalidDistancesFailClosed(){for(double distance:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY})assertFalse(valid(distance));}
    @Test void forgedLeaseOrWrongBorrowerCannotCapture(){assertFalse(ControllerCapturePolicy.receipt(player,UUID.randomUUID(),0,player,lease,false,0));assertFalse(ControllerCapturePolicy.receipt(UUID.randomUUID(),lease,0,player,lease,false,0));}
    @Test void dockedAndInvalidPortCannotCapture(){assertFalse(ControllerCapturePolicy.receipt(player,lease,0,player,lease,true,0));for(int port:new int[]{-1,2})assertFalse(ControllerCapturePolicy.receipt(player,lease,port,player,lease,false,0));}
    @Test void missingVisualPairIsNeverPresence(){assertFalse(ControllerCapturePolicy.receipt(player,lease,1,null,lease,false,0));assertFalse(ControllerCapturePolicy.receipt(player,lease,1,player,null,false,0));}
    @Test void inventoryAndMenuAliasesAreOnePhysicalStack(){var held=new Stack(lease,1);assertTrue(ControllerCapturePolicy.uniqueHeld(held,lease,List.of(held,held,held),Stack::lease,Stack::count));}
    @Test void secondCopyInBackpackRejectsEvenWhenOnlyOneHeld(){var held=new Stack(lease,1);assertFalse(ControllerCapturePolicy.uniqueHeld(held,lease,List.of(held,new Stack(lease,1)),Stack::lease,Stack::count));}
    @Test void cursorCopyReplacingOriginalRequiresCurrentlyHeldObject(){var held=new Stack(lease,1);var cursor=new Stack(lease,1);assertFalse(ControllerCapturePolicy.uniqueHeld(held,lease,List.of(cursor),Stack::lease,Stack::count));assertTrue(ControllerCapturePolicy.uniqueHeld(cursor,lease,List.of(cursor),Stack::lease,Stack::count));}
    @Test void emptyAliasesOtherLoansAndCountsAreHandled(){var held=new Stack(lease,1);assertTrue(ControllerCapturePolicy.uniqueHeld(held,lease,List.of(held,new Stack(lease,0),new Stack(UUID.randomUUID(),1)),Stack::lease,Stack::count));var two=new Stack(lease,2);assertFalse(ControllerCapturePolicy.uniqueHeld(two,lease,List.of(two),Stack::lease,Stack::count));}
    @Test void oldLeaseCannotProveReplacement(){var held=new Stack(UUID.randomUUID(),1);assertFalse(ControllerCapturePolicy.uniqueHeld(held,lease,List.of(held),Stack::lease,Stack::count));}
    @Test void swappingHandsRequiresNewNeutralIdentityButCopyingStackDoesNot(){var main=new ControllerCapturePolicy.Held(lease,0,0,false);assertEquals(main,new ControllerCapturePolicy.Held(lease,0,0,false));assertNotEquals(main,new ControllerCapturePolicy.Held(lease,0,1,false));assertNotEquals(main,new ControllerCapturePolicy.Held(UUID.randomUUID(),0,0,false));assertNotEquals(main,new ControllerCapturePolicy.Held(lease,1,0,true));}
}
