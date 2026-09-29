package cn.piq.fcarcade.cabinet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetGameTransferTest {
    private CabinetGameTransfer open(int...sizes){var t=new CabinetGameTransfer(sizes);t.delivered(0);return t;}
    @Test void openingMustBeActuallyAdmittedBeforeCommands() {
        var t=new CabinetGameTransfer(new int[]{100});assertEquals(1,t.pending());assertFalse(t.reserve(1,false));
        t.delivered(0);assertEquals(0,t.pending());assertTrue(t.reserve(1,false));
    }
    @Test void exactlyFourPendingWithPermitsUntilReplyAdmission() {
        var t=open(100);for(int i=1;i<=4;i++)assertTrue(t.reserve(i,false));
        assertEquals(4,t.pending());assertFalse(t.reserve(5,false));assertEquals(4,t.pending());
        t.delivered(1);assertEquals(3,t.pending());assertTrue(t.reserve(5,false));
        assertFalse(t.reserve(6,false));
    }
    @Test void badSequenceCannotConsumeOrCreateNegativePermits() {
        var t=open(100);assertFalse(t.reserve(2,false));assertFalse(t.reserve(-1,false));assertEquals(0,t.pending());
        assertTrue(t.reserve(1,false));assertFalse(t.reserve(1,false));assertEquals(1,t.pending());t.delivered(1);assertEquals(0,t.pending());
    }
    @Test void OutOfOrderOrDuplicateReplyCannotFreeDifferentRequest() {
        var t=open(100);assertTrue(t.reserve(1,false));assertTrue(t.reserve(2,false));
        assertThrows(IllegalStateException.class,()->t.delivered(2));assertEquals(2,t.pending());
        t.delivered(1);assertThrows(IllegalStateException.class,()->t.delivered(1));assertEquals(1,t.pending());t.delivered(2);
    }
    @Test void endingRejectsLaterCommandsAndCloseCannotResurrect() {
        var t=open(100);assertTrue(t.reserve(1,true));assertFalse(t.reserve(2,false));t.delivered(1);t.close();
        assertFalse(t.reserve(2,false));assertEquals(0,t.pending());assertThrows(IllegalStateException.class,()->t.delivered(1));
        assertThrows(IllegalArgumentException.class,()->t.download(0,0));
    }
    @Test void exactFileChunksRejectReplayAndOutOfRange() {
        var t=open(2*CabinetGameManifest.CHUNK+9);int c=CabinetGameManifest.CHUNK;
        assertEquals(c,t.download(0,0));assertThrows(IllegalArgumentException.class,()->t.download(0,0));
        assertThrows(IllegalArgumentException.class,()->t.download(0,c+1));assertEquals(c,t.download(0,c));assertEquals(9,t.download(0,2*c));
        assertThrows(IllegalArgumentException.class,()->t.download(0,2*c+9));
    }
    @Test void wholeCachedFilesMayBeSkippedButNeverBackwards() {
        var t=open(123,234,345);assertEquals(234,t.download(1,0));assertEquals(345,t.download(2,0));
        assertThrows(IllegalArgumentException.class,()->t.download(0,0));
        var skip=open(123,234,345);assertEquals(345,skip.download(2,0));
    }
    @Test void cannotAbandonPartialFileForNextOneOrStartAtOffset() {
        var t=open(30000,100);assertThrows(IllegalArgumentException.class,()->t.download(0,1));
        t.download(0,0);assertThrows(IllegalArgumentException.class,()->t.download(1,0));
        t.download(0,CabinetGameManifest.CHUNK);assertEquals(100,t.download(1,0));
    }
    @Test void alreadyCachedWholeManifestCanEndWithoutDownloads() {
        var t=open(100,100,100);assertTrue(t.reserve(1,true));t.delivered(1);assertEquals(0,t.pending());
    }
    @Test void sizesAreBoundedAndCopied() {
        assertThrows(IllegalArgumentException.class,()->new CabinetGameTransfer(new int[0]));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameTransfer(new int[4]));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameTransfer(new int[]{0}));
        assertThrows(IllegalArgumentException.class,()->new CabinetGameTransfer(new int[]{CabinetGameManifest.MAX_FILE+1}));
        int[] sizes={10};var t=new CabinetGameTransfer(sizes);sizes[0]=999;assertEquals(10,t.download(0,0));
    }
}
