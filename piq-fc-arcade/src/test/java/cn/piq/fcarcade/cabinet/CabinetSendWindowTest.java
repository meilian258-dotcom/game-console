package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetSendWindowTest {
    @Test void wholeFrameReservationIsAtomic(){var w=new CabinetSendWindow();assertNotNull(w.reserve(new int[]{100000}));assertNull(w.reserve(new int[]{60000,60000}));assertEquals(100000,w.inFlight());}
    @Test void actualIndividualCompletionReturnsOnlyItsBytes(){var w=new CabinetSendWindow();var t=w.reserve(new int[]{20000,30000,40000});t.complete(1);assertEquals(60000,w.inFlight());t.complete(1);assertEquals(60000,w.inFlight());t.complete(2);t.complete(0);assertEquals(0,w.inFlight());}
    @Test void failedAndDuplicateOldCallbacksCannotReleaseNewTickets(){var w=new CabinetSendWindow();var old=w.reserve(new int[]{100000});old.complete(0);var next=w.reserve(new int[]{100000});old.complete(0);assertEquals(100000,w.inFlight());assertNull(w.reserve(new int[]{100000}));next.complete(0);assertEquals(0,w.inFlight());}
    @Test void maximumInFlightStaysBoundedAcrossRepeatedQueueAttempts(){var w=new CabinetSendWindow();var held=w.reserve(new int[]{24576,24576,24576,24576,24576,8192});assertNotNull(held);int accepted=0;for(int i=0;i<10000;i++){if(w.reserve(new int[]{19200})!=null)accepted++;assertTrue(w.inFlight()<=196608);}assertEquals(3,accepted);}
    @Test void definitelyUnsentTailCanBeReleasedWithoutForgivingUncertainSend(){var w=new CabinetSendWindow();var t=w.reserve(new int[]{100,200,300});t.cancelUnsent(2);assertEquals(300,w.inFlight());t.complete(0);assertEquals(200,w.inFlight());t.complete(1);assertEquals(0,w.inFlight());}
    @Test void closeIsTerminalAndLateCallbacksCannotCreateNegativeBalance(){var w=new CabinetSendWindow();var t=w.reserve(new int[]{1000});w.close();assertEquals(0,w.inFlight());t.complete(0);assertEquals(0,w.inFlight());assertNull(w.reserve(new int[]{1}));}
    @Test void invalidWeightsCannotOverflowOrAllocateUnboundedTickets(){var w=new CabinetSendWindow();assertNull(w.reserve(null));assertNull(w.reserve(new int[]{}));assertNull(w.reserve(new int[]{-1}));assertNull(w.reserve(new int[]{Integer.MAX_VALUE}));assertNull(w.reserve(new int[]{1,1,1,1,1,1,1}));assertEquals(0,w.inFlight());}
    @Test void concurrentDuplicateCompletionsAreIdempotent()throws Exception{var w=new CabinetSendWindow();var t=w.reserve(new int[]{60000,60000,60000});var threads=new Thread[12];for(int i=0;i<threads.length;i++){int slot=i%3;threads[i]=new Thread(()->{for(int n=0;n<1000;n++)t.complete(slot);});threads[i].start();}for(var thread:threads)thread.join();assertEquals(0,w.inFlight());}
}
