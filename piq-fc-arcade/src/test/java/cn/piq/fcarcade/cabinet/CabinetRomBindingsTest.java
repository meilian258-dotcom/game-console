package cn.piq.fcarcade.cabinet;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
class CabinetRomBindingsTest {
    @TempDir Path directory;
    CabinetRomBindings.Key key(String context,String dim,UUID id,String backend){return new CabinetRomBindings.Key(context,dim,id,backend);}
    @Test void persistentKeysIsolateWorldServerPortDimensionIdentityAndBackend()throws Exception{
        Path file=directory.resolve("selection.dat"),rom=directory.resolve("chosen.sfc");var store=new CabinetRomBindings(file);var id=UUID.randomUUID();
        var selected=key("server:example:25565","minecraft:overworld",id,"piq:sfc");assertTrue(store.load(selected).isEmpty());assertFalse(Files.exists(file));
        store.remember(selected,rom);assertEquals(rom,new CabinetRomBindings(file).load(selected).orElseThrow());
        for(var other:new CabinetRomBindings.Key[]{key("server:example:25566","minecraft:overworld",id,"piq:sfc"),key("world:example:25565","minecraft:overworld",id,"piq:sfc"),key("server:example:25565","minecraft:the_nether",id,"piq:sfc"),key("server:example:25565","minecraft:overworld",UUID.randomUUID(),"piq:sfc"),key("server:example:25565","minecraft:overworld",id,"piq:mame")})assertTrue(store.load(other).isEmpty());
    }
    @Test void updatingOneBindingPreservesOthersAndDoesNotReadRomContent()throws Exception{
        var store=new CabinetRomBindings(directory.resolve("selection.dat"));var a=key("world:a","minecraft:overworld",UUID.randomUUID(),"piq:sfc");var b=key("world:b","minecraft:overworld",UUID.randomUUID(),"piq:sfc");
        store.remember(a,directory.resolve("first.sfc"));store.remember(b,directory.resolve("second.sfc"));store.remember(a,directory.resolve("changed.sfc"));
        assertEquals(directory.resolve("changed.sfc"),store.load(a).orElseThrow());assertEquals(directory.resolve("second.sfc"),store.load(b).orElseThrow());assertFalse(Files.exists(directory.resolve("changed.sfc")));
    }
    @Test void countLimitRejectsNewBindingWithoutDestroyingOldFile()throws Exception{
        Path file=directory.resolve("selection.dat");var store=new CabinetRomBindings(file);var first=key("world:a","minecraft:overworld",new UUID(0,0),"piq:sfc");
        for(int i=0;i<256;i++)store.remember(key("world:a","minecraft:overworld",new UUID(0,i),"piq:sfc"),directory.resolve("game.sfc"));
        byte[] before=Files.readAllBytes(file);assertThrows(IOException.class,()->store.remember(key("world:a","minecraft:overworld",new UUID(0,256),"piq:sfc"),directory.resolve("extra.sfc")));assertArrayEquals(before,Files.readAllBytes(file));
        store.remember(first,directory.resolve("replacement.sfc"));assertEquals(directory.resolve("replacement.sfc"),store.load(first).orElseThrow());
    }
    @Test void corruptAndOversizedFilesAreNeverSilentlyOverwritten()throws Exception{
        Path file=directory.resolve("selection.dat");var store=new CabinetRomBindings(file);var selected=key("world:a","minecraft:overworld",UUID.randomUUID(),"piq:sfc");
        for(byte[] invalid:new byte[][]{new byte[]{1,2,3},new byte[CabinetRomBindings.MAX_BYTES+1]}){Files.write(file,invalid);assertThrows(IOException.class,()->store.load(selected));assertThrows(IOException.class,()->store.remember(selected,directory.resolve("game.sfc")));assertArrayEquals(invalid,Files.readAllBytes(file));}
    }
    @Test void trailingBytesAndDuplicateKeysAreRejected()throws Exception{
        Path file=directory.resolve("selection.dat");var store=new CabinetRomBindings(file);var selected=key("world:a","minecraft:overworld",UUID.randomUUID(),"piq:sfc");
        store.remember(selected,directory.resolve("game.sfc"));Files.write(file,new byte[]{42},StandardOpenOption.APPEND);assertThrows(IOException.class,()->store.load(selected));
        try(var output=new DataOutputStream(Files.newOutputStream(file))){output.writeInt(0x50495231);output.writeShort(2);for(int i=0;i<2;i++){output.writeUTF(selected.digest());output.writeUTF(directory.resolve("game.sfc").toString());}}
        assertThrows(IOException.class,()->store.load(selected));
    }
    @Test void invalidKeysRejectControlCharactersAndUnqualifiedIds(){assertThrows(IllegalArgumentException.class,()->key("world:a\nb","minecraft:overworld",UUID.randomUUID(),"piq:sfc"));assertThrows(IllegalArgumentException.class,()->key("world:a","overworld",UUID.randomUUID(),"piq:sfc"));assertThrows(IllegalArgumentException.class,()->key("world:a","minecraft:overworld",UUID.randomUUID(),"SFC"));}
    @Test void symlinkStoreNeverOverwritesItsTarget()throws Exception{
        Path actual=directory.resolve("actual.dat"),link=directory.resolve("link.dat");Files.write(actual,new byte[]{1,2,3});
        try{Files.createSymbolicLink(link,actual);}catch(IOException|UnsupportedOperationException|SecurityException unavailable){assumeTrue(false,"Host cannot create symlinks");return;}
        var store=new CabinetRomBindings(link);var selected=key("world:a","minecraft:overworld",UUID.randomUUID(),"piq:sfc");assertThrows(IOException.class,()->store.remember(selected,directory.resolve("game.sfc")));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(actual));
    }
}
