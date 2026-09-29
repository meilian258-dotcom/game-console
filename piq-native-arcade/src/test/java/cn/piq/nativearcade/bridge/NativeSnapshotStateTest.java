package cn.piq.nativearcade.bridge;

import cn.piq.nativearcade.NativeSnapshotProfile;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class NativeSnapshotStateTest {
    static final String ROM=NativeSnapshotProfile.ROMS.get("kof97.zip");
    static byte[] sample()throws IOException{return NativeSnapshotState.encode(32,ROM,new byte[]{0,1,2,-1,44});}
    @Test void canonicalEnvelopePreservesEveryOpaqueByte()throws Exception{
        byte[] payload=new byte[3334118];for(int i=0;i<payload.length;i++)payload[i]=(byte)(i*31);
        byte[] encoded=NativeSnapshotState.encode(32,ROM,payload);assertEquals(payload.length+148,encoded.length);
        var decoded=NativeSnapshotState.decode(encoded,ROM);assertEquals(32,decoded.internalFrame());assertArrayEquals(payload,decoded.payload());
        assertArrayEquals(encoded,NativeSnapshotState.encode(decoded.internalFrame(),ROM,decoded.payload()));
    }
    @Test void decoderCopiesItsOpaquePayload()throws Exception{
        byte[] encoded=sample();var decoded=NativeSnapshotState.decode(encoded,ROM);encoded[148]=99;decoded.payload()[0]=88;assertEquals(0,decoded.payload()[0]);
    }
    @Test void wrongGameAndCaseAreRejected()throws Exception{
        byte[] encoded=sample();assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,NativeSnapshotProfile.ROMS.get("mslug2.zip")));
        assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,ROM.toLowerCase(java.util.Locale.ROOT)));
    }
    @Test void allIdentityAndDigestBytesAreChecked()throws Exception{
        for(int offset=16;offset<144;offset++){byte[] encoded=sample();encoded[offset]^=1;assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,ROM),"offset="+offset);}
    }
    @Test void magicVersionSizeAndTruncationFailClosed()throws Exception{
        byte[] original=sample();for(int n=0;n<original.length;n++){byte[] truncated=Arrays.copyOf(original,n);assertThrows(IOException.class,()->NativeSnapshotState.decode(truncated,ROM));}
        for(int offset:new int[]{0,4,144}){byte[] encoded=sample();encoded[offset]^=64;assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,ROM));}
        assertThrows(IOException.class,()->NativeSnapshotState.decode(Arrays.copyOf(original,original.length+1),ROM));
    }
    @Test void wrongPayloadFailsFullDigest()throws Exception{byte[] encoded=sample();encoded[150]^=1;assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,ROM));}
    @Test void payloadBoundsAreInclusiveAndNoEmptyState()throws Exception{
        assertThrows(IOException.class,()->NativeSnapshotState.encode(32,ROM,new byte[0]));
        assertThrows(IOException.class,()->NativeSnapshotState.encode(32,ROM,new byte[NativeSnapshotState.MAX_BYTES-147]));
        byte[] max=NativeSnapshotState.encode(32,ROM,new byte[NativeSnapshotState.MAX_BYTES-148]);assertEquals(NativeSnapshotState.MAX_BYTES,max.length);assertEquals(max.length-148,NativeSnapshotState.decode(max,ROM).payload().length);
    }
    @Test void preBootstrapFrameIsNeverAnAcceptedState()throws Exception{
        for(long frame:new long[]{-1,0,31})assertThrows(IOException.class,()->NativeSnapshotState.encode(frame,ROM,new byte[]{1}));
        byte[] encoded=sample();ByteBuffer.wrap(encoded).putLong(8,31);assertThrows(IOException.class,()->NativeSnapshotState.decode(encoded,ROM));
    }
    @Test void outerLogicalFrameMustMatchInternalExactly()throws Exception{
        byte[] value=sample();assertEquals(32,NativeSnapshotState.decode(value,ROM,0).internalFrame());
        for(long frame:new long[]{-1,1,32,Long.MAX_VALUE-31,Long.MAX_VALUE})assertThrows(IOException.class,()->NativeSnapshotState.decode(value,ROM,frame));
        byte[] later=NativeSnapshotState.encode(1832,ROM,new byte[]{7});assertEquals(1832,NativeSnapshotState.decode(later,ROM,1800).internalFrame());
    }
    @Test void profileIsTwoGamesTwoPlayersAndIndependentRuntime(){
        assertEquals(2,NativeSnapshotProfile.ROMS.size());assertEquals(2,NativeSnapshotProfile.PLAYERS);assertEquals(1800,NativeSnapshotProfile.SNAPSHOT_INTERVAL);
        assertNotEquals("runtime",NativeSnapshotProfile.DIRECTORY);assertEquals(64,NativeSnapshotProfile.PROFILE_SHA.length());
        assertEquals("piq:neogeo-snapshot-v1:"+NativeSnapshotProfile.PROFILE_SHA,NativeSnapshotProfile.COMPATIBILITY_ID);
    }
}
