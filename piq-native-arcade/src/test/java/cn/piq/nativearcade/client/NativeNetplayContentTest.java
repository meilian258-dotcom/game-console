package cn.piq.nativearcade.client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeNetplayContentTest {
    @Test void serviceIsReservedOnEveryGameProfile(){for(String game:List.of("kov.zip","dino.zip","kof97.zip","contra.zip"))assertEquals("Hold Start + L + R",NativeNetplayContent.profile(game).options().get("fbneo-diagnostic-input"));}
    @Test void netplayDeclaresFourPortsWithoutLiftingLegacySnapshotLimits(){
        var p=NativeNetplayContent.profile("kov.zip");assertEquals(4,p.ports());for(int port=0;port<4;port++)assertEquals(5,p.deviceForPort(port));
        assertThrows(IllegalArgumentException.class,()->p.deviceForPort(4));
    }
    @TempDir Path tmp;
    @Test void missingFileNeverStarts(){assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("other.zip")));}
    @Test void arbitrarySafeNamesAreNotGameWhitelisted(){for(String s:List.of("dino.zip","kov.zip","kov115.zip","kof98.zip","mslug3.zip","anygame.zip"))assertDoesNotThrow(()->NativeNetplayContent.profile(s));}
    @Test void rejectPathsBiosDevicesAndRenamedNonZip(){for(String s:List.of("../dino.zip","D:/dino.zip","dino.exe","pgm.zip","neogeo.zip","qsound.zip","qsound_hle.zip","con.zip","COM1.zip","dino.zip "))assertThrows(IllegalArgumentException.class,()->NativeNetplayContent.profile(s),s);}
    @Test void profilePinsCoreAndDeterministicOptions(){var p=NativeNetplayContent.profile("dino.zip");assertEquals(48000,p.sampleRate());assertEquals(NativeNetplayContent.CORE_SHA,p.sha());assertEquals(5,p.device());assertEquals("disabled",p.options().get("fbneo-hiscores"));assertEquals("0",p.options().get("fbneo-frameskip"));}
    private Path zip(String name)throws Exception{var path=tmp.resolve(name);try(var z=new java.util.zip.ZipOutputStream(Files.newOutputStream(path))){z.putNextEntry(new java.util.zip.ZipEntry("dummy.bin"));z.write(new byte[32]);z.closeEntry();}return path;}
    @Test void collectsPgmAlongsideGameButNotOtherGamesOrDll()throws Exception{Path game=zip("kov.zip");zip("pgm.zip");zip("unrelated.zip");Files.write(tmp.resolve("unsafe.dll"),new byte[22]);var c=NativeNetplayContent.load(game);assertEquals(Set.of("pgm.zip"),c.auxiliary().keySet());assertArrayEquals(Files.readAllBytes(game),c.rom());}
    @Test void gameDoesNotRequireNeoGeoBios()throws Exception{assertTrue(NativeNetplayContent.load(zip("dino.zip")).auxiliary().isEmpty());}
    @Test void rejectBogusZipAndDirectory()throws Exception{Files.write(tmp.resolve("dino.zip"),new byte[24]);assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("dino.zip")));Files.createDirectory(tmp.resolve("kov.zip"));assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(tmp.resolve("kov.zip")));}
    @Test void rejectOversizedCompanion()throws Exception{Path game=zip("kov.zip");try(var f=new java.io.RandomAccessFile(tmp.resolve("pgm.zip").toFile(),"rw")){f.setLength(16L*1024*1024+1);}assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(game));}
    @Test void rejectInvalidCompanionRatherThanSilentlySkipping()throws Exception{Path game=zip("kov.zip");Files.createDirectory(tmp.resolve("pgm.zip"));assertThrows(java.io.IOException.class,()->NativeNetplayContent.load(game));}
}
