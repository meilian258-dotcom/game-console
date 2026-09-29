package cn.piq.fcarcade.netplay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class NetplaySaveTest {
    @TempDir Path directory;
    NetplaySaveState.Identity identity(){return NetplaySaveState.identity(NetplayProfile.fc(),"a".repeat(64),Map.of());}
    byte[] state(long frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(identity(),frame,new byte[]{1,2,3},new byte[]{0x5a},new byte[]{7}));}
    @Test void preservesStateBatteryRtcAndOwnsArrays(){
        byte[] nativeState={3,4},ram={5},rtc={6};var parts=new NetplaySaveState.Parts(identity(),99,nativeState,ram,rtc);
        nativeState[0]=99;ram[0]=99;rtc[0]=99;
        var got=NetplaySaveState.decode(NetplaySaveState.encode(parts),identity());
        assertEquals(99,got.frame());assertArrayEquals(new byte[]{3,4},got.state());assertArrayEquals(new byte[]{5},got.ram());assertArrayEquals(new byte[]{6},got.rtc());
        var seed=ByteBuffer.wrap(NetplaySaveState.nativeSeed(got));assertEquals(2,seed.getInt());assertEquals(1,seed.getInt());assertEquals(1,seed.getInt());assertEquals(3,seed.get());
    }
    @Test void everyTruncationAndCorruptByteRejected(){byte[] good=state(1);
        for(int length=0;length<good.length;length++){byte[] cut=Arrays.copyOf(good,length);assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(cut,identity()));}
        for(int at=0;at<good.length;at++){byte[] bad=good.clone();bad[at]^=1;assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(bad,identity()));}
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(Arrays.copyOf(good,good.length+1),identity()));
    }
    @Test void contentOptionsPortsAndBiosIdentitiesAreIsolated(){
        var p=NetplayProfile.fc();assertNotEquals(identity(),NetplaySaveState.identity(p,"b".repeat(64),Map.of()));
        assertNotEquals(identity(),NetplaySaveState.identity(NetplayProfile.fcZapper(),"a".repeat(64),Map.of()));
        assertNotEquals(identity(),NetplaySaveState.identity(p,"a".repeat(64),Map.of("pgm.zip","b".repeat(64))));
        var changed=new NetplayProfile(p.owner(),p.resource(),p.sha(),p.contentName(),Map.of("mesen_region","PAL"),p.device(),p.sampleRate(),p.maxRomBytes());
        assertNotEquals(identity(),NetplaySaveState.identity(changed,"a".repeat(64),Map.of()));
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(state(1),new NetplaySaveState.Identity("b".repeat(64),identity().content())));
    }
    @Test void transferRoundTripAndTrailingData()throws Exception{byte[] raw=state(2),packed=NetplaySaveTransfer.pack(raw);assertArrayEquals(raw,NetplaySaveTransfer.unpack(packed));
        assertThrows(IOException.class,()->NetplaySaveTransfer.unpack(Arrays.copyOf(packed,packed.length+1)));
        for(int n=0;n<packed.length;n++){byte[] shortData=Arrays.copyOf(packed,n);assertThrows(IOException.class,()->NetplaySaveTransfer.unpack(shortData));}
        byte[] bomb=packed.clone();ByteBuffer.wrap(bomb).putInt(Integer.MAX_VALUE);assertThrows(IOException.class,()->NetplaySaveTransfer.unpack(bomb));
    }
    @Test void chunkOffsetDuplicateAndBounds(){var a=new NetplaySaveTransfer.Assembly(4);a.append(0,new byte[]{1,2});
        assertThrows(IllegalArgumentException.class,()->a.append(0,new byte[]{3,4}));assertThrows(IllegalArgumentException.class,()->a.append(2,new byte[]{3,4,5}));
        assertThrows(IllegalStateException.class,a::finish);a.append(2,new byte[]{3,4});assertArrayEquals(new byte[]{1,2,3,4},a.finish());
        assertThrows(IllegalArgumentException.class,()->new NetplaySaveTransfer.Assembly(NetplaySaveTransfer.MAX_PACKED+1));
    }
    @Test void storeReopenBackupAndExclusiveWriter()throws Exception{
        Path slot=directory.resolve("中文存档");
        try(var s=new NetplaySaveStore(slot,identity())){assertNull(s.read());s.write(state(1));assertThrows(IOException.class,()->new NetplaySaveStore(slot,identity()));s.write(state(2));assertArrayEquals(state(1),Files.readAllBytes(slot.resolve("checkpoint.previous.bin")));}
        try(var s=new NetplaySaveStore(slot,identity())){assertArrayEquals(state(2),s.read());byte[] bad=state(3);bad[100]^=1;assertThrows(IllegalArgumentException.class,()->s.write(bad));assertArrayEquals(state(2),s.read());}
    }
    @Test void corruptedStoredFileNeverSilentlyReplaced()throws Exception{
        Path slot=directory.resolve("bad");try(var s=new NetplaySaveStore(slot,identity())){s.write(state(1));Files.write(slot.resolve("checkpoint.bin"),new byte[]{9});assertThrows(IOException.class,s::read);assertThrows(IOException.class,()->s.write(state(2)));assertArrayEquals(new byte[]{9},Files.readAllBytes(slot.resolve("checkpoint.bin")));}
    }
    @Test void wrongProfileReadIsRejected()throws Exception{
        Path slot=directory.resolve("identity");try(var s=new NetplaySaveStore(slot,identity())){s.write(state(1));}
        try(var s=new NetplaySaveStore(slot,new NetplaySaveState.Identity("b".repeat(64),identity().content()))){assertThrows(IOException.class,s::read);}
    }
    @Test void fcLegacyAndNetplayOwnershipNamesNeverCollide(){String owner="cartridge|"+UUID.randomUUID();
        assertNotEquals(FcNetplaySaves.key(false,owner),FcNetplaySaves.key(true,owner));
        for(var v:cn.piq.fcarcade.session.NesCoreVariant.values())assertNotEquals(v.saveKey(owner),FcNetplaySaves.key(false,owner));
        assertTrue(FcNetplaySaves.accepts(false,"a".repeat(64),state(1)));assertFalse(FcNetplaySaves.accepts(true,"a".repeat(64),state(1)));
    }
}
