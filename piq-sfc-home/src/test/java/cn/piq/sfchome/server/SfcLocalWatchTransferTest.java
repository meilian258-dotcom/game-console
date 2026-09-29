package cn.piq.sfchome.server;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SfcLocalWatchTransferTest {
    private String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void commitsOnlyCompleteOrderedDigestCheckedState()throws Exception{
        byte[] bytes={1,2,3,4};var t=new SfcLocalWatchTransfer(4,sha(bytes));
        assertFalse(t.complete());assertThrows(IllegalStateException.class,t::take);
        assertTrue(t.append(4,0,sha(bytes),new byte[]{1,2}));assertFalse(t.complete());
        assertTrue(t.append(4,2,sha(bytes),new byte[]{3,4}));assertTrue(t.complete());assertArrayEquals(bytes,t.take());
    }
    @Test void malformedTotalRejectedBeforeAllocation(){
        for(int size:new int[]{-1,0,SfcRepairLedger.MAX_STATE+1,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->new SfcLocalWatchTransfer(size,"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new SfcLocalWatchTransfer(1,"bad"));
    }
    @Test void missingFirstOrDuplicateSegmentPermanentlyFails()throws Exception{
        String sha=sha(new byte[]{1,2});var t=new SfcLocalWatchTransfer(2,sha);
        assertFalse(t.append(2,1,sha,new byte[]{2}));assertFalse(t.append(2,0,sha,new byte[]{1,2}));
        var duplicate=new SfcLocalWatchTransfer(2,sha);assertTrue(duplicate.append(2,0,sha,new byte[]{1}));
        assertFalse(duplicate.append(2,0,sha,new byte[]{1}));assertFalse(duplicate.complete());
    }
    @Test void sizeAndDigestChangesRejected()throws Exception{
        String sha=sha(new byte[]{1,2});
        assertFalse(new SfcLocalWatchTransfer(2,sha).append(3,0,sha,new byte[]{1}));
        assertFalse(new SfcLocalWatchTransfer(2,sha).append(2,0,"a".repeat(64),new byte[]{1}));
        assertFalse(new SfcLocalWatchTransfer(2,sha).append(2,0,sha,new byte[]{2,1}));
    }
    @Test void invalidPartsAndOvershootNeverCommit()throws Exception{
        String sha=sha(new byte[]{1});
        for(byte[] bytes:new byte[][]{null,new byte[0],new byte[2],new byte[SfcRepairLedger.CHUNK+1]})
            assertFalse(new SfcLocalWatchTransfer(1,sha).append(1,0,sha,bytes));
    }
    @Test void completedSnapshotRejectsAdditionalData()throws Exception{
        String sha=sha(new byte[]{1});var t=new SfcLocalWatchTransfer(1,sha);assertTrue(t.append(1,0,sha,new byte[]{1}));
        assertFalse(t.append(1,0,sha,new byte[]{1}));assertFalse(t.complete());assertThrows(IllegalStateException.class,t::take);
    }
    @Test void maximumSizedStateUsesBoundedChunksAndExactChecksum()throws Exception{
        byte[] bytes=new byte[SfcRepairLedger.MAX_STATE];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        String sha=sha(bytes);var t=new SfcLocalWatchTransfer(bytes.length,sha);
        for(int offset=0;offset<bytes.length;offset+=SfcRepairLedger.CHUNK){int end=Math.min(bytes.length,offset+SfcRepairLedger.CHUNK);
            assertTrue(t.append(bytes.length,offset,sha,java.util.Arrays.copyOfRange(bytes,offset,end)));}
        assertArrayEquals(bytes,t.take());
    }
}
