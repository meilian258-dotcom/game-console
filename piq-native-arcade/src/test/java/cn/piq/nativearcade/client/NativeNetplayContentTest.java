package cn.piq.nativearcade.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeNetplayContentTest {
    @Test void serviceIsReservedOnEveryGameProfile(){for(String game:List.of("kov.zip","dino.zip","kof97.zip","contra.zip"))assertEquals("Hold Start + L + R",NativeNetplayContent.profile(game).options().get("fbneo-diagnostic-input"));}
    @Test void netplayDeclaresFourPortsWithoutLiftingLegacySnapshotLimits(){
        var p=NativeNetplayContent.profile("kov.zip");assertEquals(4,p.ports());for(int port=0;port<4;port++)assertEquals(1,p.deviceForPort(port));
        assertNotNull(p.jni());assertEquals(List.of(1,1,1,1),p.jni().devices());
        assertThrows(IllegalArgumentException.class,()->p.deviceForPort(4));
    }
    @TempDir Path tmp;
    @Test void missingFileNeverStarts(){assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("other.zip")));}
    @Test void arbitrarySafeNamesAreNotGameWhitelisted(){for(String s:List.of("dino.zip","kov.zip","kov115.zip","kof98.zip","mslug3.zip","anygame.zip"))assertDoesNotThrow(()->NativeNetplayContent.profile(s));}
    @Test void rejectPathsBiosDevicesAndRenamedNonZip(){for(String s:List.of("../dino.zip","D:/dino.zip","dino.exe","pgm.zip","neogeo.zip","qsound.zip","qsound_hle.zip","con.zip","COM1.zip","dino.zip "))assertThrows(IllegalArgumentException.class,()->NativeNetplayContent.profile(s),s);}
    @Test void profilePinsCoreAndDeterministicOptions(){var p=NativeNetplayContent.profile("dino.zip");assertEquals(48000,p.sampleRate());assertEquals(NativeNetplayContent.CORE_SHA,p.sha());assertEquals(1,p.device());assertFalse(p.options().containsKey("fbneo-hiscores"));assertEquals("0",p.options().get("fbneo-fixed-frameskip"));assertEquals("48000",p.options().get("fbneo-samplerate"));}
    @Test void changedStateLayoutCannotLoadOrOverwriteOldCoreIdentity(){
        var current=NativeNetplayContent.profile("kov.zip");
        var old=new cn.piq.fcarcade.netplay.NetplayProfile(current.owner(),"/native-runtime/win-x64-fbneo-pgm-v2/fbneo_libretro.dll",
                "73579C4C50D1F16F5D52A1E5BF4D81106C40962BC284E83E4A96DC7BB68FD424",current.contentName(),
                current.options(),current.device(),current.sampleRate(),current.maxRomBytes(),current.ports());
        var r=current.jni();
        old=old.withJni(new cn.piq.retro.libretro.LibretroProfile(r.name(),r.extension(),r.fullPath(),r.devices(),r.mesenGun(),r.options(),
                Map.of("windows-x64",new cn.piq.retro.libretro.LibretroProfile.Artifact(old.resource(),old.sha()))));
        String rom=cn.piq.fcarcade.netplay.NetplaySaveState.hash(new byte[]{1,2,3});
        var now=cn.piq.fcarcade.netplay.NetplaySaveState.identity(current,rom,Map.of());
        var before=cn.piq.fcarcade.netplay.NetplaySaveState.identity(old,rom,Map.of());
        assertNotEquals(before.profile(),now.profile());assertEquals(before.content(),now.content());
        byte[] saved=cn.piq.fcarcade.netplay.NetplaySaveState.encode(new cn.piq.fcarcade.netplay.NetplaySaveState.Parts(before,123,new byte[]{4},new byte[0],new byte[0]));
        assertThrows(IllegalArgumentException.class,()->cn.piq.fcarcade.netplay.NetplaySaveState.decode(saved,now));
        assertEquals(123,cn.piq.fcarcade.netplay.NetplaySaveState.decode(saved,before).frame());
        assertEquals(cn.piq.nativearcade.NativeNetplayProfile.CORE_SHA,NativeNetplayContent.CORE_SHA);
        assertTrue(NativeNetplayContent.CORE_RESOURCE.contains("state-v3"));
    }
    private Path zip(String name)throws Exception{var path=tmp.resolve(name);try(var z=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))){z.putNextEntry(new java.util.zip.ZipEntry("dummy.bin"));z.write(new byte[32]);z.closeEntry();}return path;}
    @Test void collectsPgmAlongsideGameButNotOtherGamesOrDll()throws Exception{Path game=zip("kov.zip");zip("pgm.zip");zip("unrelated.zip");Files.write(tmp.resolve("unsafe.dll"),new byte[22]);var c=NativeNetplayContent.load(game);assertEquals(Set.of("pgm.zip"),c.auxiliary().keySet());assertArrayEquals(Files.readAllBytes(game),c.rom());}
    @Test void gameDoesNotRequireNeoGeoBios()throws Exception{assertTrue(NativeNetplayContent.load(zip("dino.zip")).auxiliary().isEmpty());}
    @Test void rejectBogusZipAndDirectory()throws Exception{Files.write(tmp.resolve("dino.zip"),new byte[24]);assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("dino.zip")));Files.createDirectory(tmp.resolve("kov.zip"));assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("kov.zip")));}
    @Test void rejectOversizedCompanion()throws Exception{Path game=zip("kov.zip");try(var f=new java.io.RandomAccessFile(tmp.resolve("pgm.zip").toFile(),"rw")){f.setLength(16L*1024*1024+1);}assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(game));}
    @Test void rejectInvalidCompanionRatherThanSilentlySkipping()throws Exception{Path game=zip("kov.zip");Files.createDirectory(tmp.resolve("pgm.zip"));assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(game));}
}
