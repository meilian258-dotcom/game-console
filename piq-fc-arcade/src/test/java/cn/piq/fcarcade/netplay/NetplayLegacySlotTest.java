package cn.piq.fcarcade.netplay;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NetplayLegacySlotTest {
    @TempDir Path root;
    final NetplaySaveState.Identity identity=new NetplaySaveState.Identity("a".repeat(64),"b".repeat(64));
    byte[] bytes(int frame){return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,frame,new byte[]{1,2},new byte[]{3},new byte[0]));}
    @Test void listingMissingDoesNotCreateAndCannotResumeEmpty()throws Exception{
        Path p=root.resolve("missing");assertEquals("",NetplayLegacySlot.inspect(p,identity));assertFalse(Files.exists(p));
        assertThrows(IOException.class,()->NetplayLegacySlot.lease(p,identity,"",true));
        try(var lease=NetplayLegacySlot.lease(p,identity,"",false)){assertNull(lease.read());}
    }
    @Test void freshSelectionDoesNotDeleteOldAndSuccessfulWriteRetainsBackup()throws Exception{
        Path p=root.resolve("slot");try(var store=new NetplaySaveStore(p,identity)){store.write(bytes(1));}
        String version=NetplayLegacySlot.inspect(p,identity);
        try(var lease=NetplayLegacySlot.lease(p,identity,version,false)){assertNull(lease.read());assertArrayEquals(bytes(1),NetplaySaveStore.readOnly(p,identity));}
        assertEquals(version,NetplayLegacySlot.inspect(p,identity));
        try(var lease=NetplayLegacySlot.lease(p,identity,version,false)){lease.write(bytes(2));}
        assertArrayEquals(bytes(2),NetplaySaveStore.readOnly(p,identity));assertArrayEquals(bytes(1),Files.readAllBytes(p.resolve("checkpoint.previous.bin")));
    }
    @Test void staleChoiceIsRejectedUnderWriterLockAndLockIsReleased()throws Exception{
        Path p=root.resolve("slot");try(var store=new NetplaySaveStore(p,identity)){store.write(bytes(1));}
        String version=NetplayLegacySlot.inspect(p,identity);
        try(var store=new NetplaySaveStore(p,identity)){store.write(bytes(2));}
        assertThrows(IOException.class,()->NetplayLegacySlot.lease(p,identity,version,true));
        try(var lease=NetplayLegacySlot.lease(p,identity,NetplayLegacySlot.inspect(p,identity),true)){assertArrayEquals(bytes(2),lease.read());assertThrows(IOException.class,()->NetplayLegacySlot.lease(p,identity,version,false));}
    }
    @Test void corruptAndWrongIdentityNeverOverwriteOriginal()throws Exception{
        Path p=root.resolve("slot");try(var store=new NetplaySaveStore(p,identity)){store.write(bytes(1));}
        var other=new NetplaySaveState.Identity("c".repeat(64),identity.content());
        assertThrows(IOException.class,()->NetplayLegacySlot.inspect(p,other));
        Files.write(p.resolve("checkpoint.bin"),new byte[]{9});
        assertThrows(IOException.class,()->NetplayLegacySlot.lease(p,identity,"",false));
        assertArrayEquals(new byte[]{9},Files.readAllBytes(p.resolve("checkpoint.bin")));
    }
}
