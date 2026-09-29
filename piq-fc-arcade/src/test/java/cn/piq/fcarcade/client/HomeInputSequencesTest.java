package cn.piq.fcarcade.client;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class HomeInputSequencesTest {
    @Test void gunToPadAndBackResumesSameLeaseWithoutReset(){var state=new HomeInputSequences();var gun=UUID.randomUUID();var pad=UUID.randomUUID();assertEquals(0,state.switchLease(null,0,gun));assertEquals(0,state.switchLease(gun,81,pad));assertEquals(81,state.switchLease(pad,23,gun));assertEquals(23,state.switchLease(gun,83,pad));}
    @Test void stowingAndReturningDoesNotForgetSequence(){var state=new HomeInputSequences();var gun=UUID.randomUUID();assertEquals(0,state.switchLease(gun,17,null));assertEquals(17,state.switchLease(null,0,gun));}
    @Test void newEpochDropsOldSequenceWindows(){var state=new HomeInputSequences();var gun=UUID.randomUUID();state.switchLease(gun,17,null);state.clear();assertEquals(0,state.switchLease(null,0,gun));}
    @Test void boundedStorageDoesNotGrowForBorrowReturnCycles(){var state=new HomeInputSequences();var current=UUID.randomUUID();for(int i=0;i<1000;i++){var next=UUID.randomUUID();assertEquals(0,state.switchLease(current,i,next));assertTrue(state.size()<=2);current=next;}}
}
