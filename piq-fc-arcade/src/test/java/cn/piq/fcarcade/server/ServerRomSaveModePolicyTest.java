package cn.piq.fcarcade.server;

import cn.piq.fcarcade.rom.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerRomSaveModePolicyTest {
    @TempDir Path directory;
    static byte[] rom(){byte[] b=new byte[16+16384+8192];b[0]='N';b[1]='E';b[2]='S';b[3]=0x1a;b[4]=1;b[5]=1;return b;}
    ServerRomLibrary library(){var l=new ServerRomLibrary(directory,Runnable::run,()->{},warning->fail(warning));l.store("save-mode.nes",RomRepository.sha256(rom()),rom());return l;}
    @Test void allModesPersistAndReuploadNeverResetsTheExplicitMode(){
        var library=library();String hash=RomRepository.sha256(rom());
        for(var mode:RomSaveMode.values()){library.setSaveMode(hash,mode);assertEquals(mode,library.saveMode(hash));assertEquals(mode,library().saveMode(hash));}
    }
    @Test void unknownRomAndNullDoNotCreateNewMetadata(){
        var library=library();String hash=RomRepository.sha256(rom());
        assertThrows(IllegalArgumentException.class,()->library.setSaveMode("f".repeat(64),RomSaveMode.PLAYER));
        assertThrows(IllegalArgumentException.class,()->library.setSaveMode(hash,null));assertEquals(RomSaveMode.NONE,library.saveMode(hash));
        assertFalse(Files.exists(directory.resolve("rom-metadata.properties")));
    }
    @Test void failedFirstWriteRestoresNoneWithoutDeletingTheObstruction()throws Exception{
        var library=library();String hash=RomRepository.sha256(rom());Path obstruction=Files.createDirectory(directory.resolve("rom-metadata.properties"));
        Files.writeString(obstruction.resolve("keep.txt"),"test-owned");
        assertThrows(IllegalStateException.class,()->library.setSaveMode(hash,RomSaveMode.PLAYER));
        assertEquals(RomSaveMode.NONE,library.saveMode(hash));assertEquals("test-owned",Files.readString(obstruction.resolve("keep.txt")));
    }
    @Test void failedChangeRestoresPreviousModeAndExistingDiskData()throws Exception{
        var library=library();String hash=RomRepository.sha256(rom());library.setSaveMode(hash,RomSaveMode.MACHINE);
        Path metadata=directory.resolve("rom-metadata.properties"),saved=directory.resolve("original.properties");Files.move(metadata,saved);byte[] before=Files.readAllBytes(saved);
        Files.createDirectory(metadata);Files.writeString(metadata.resolve("keep.txt"),"test-owned");
        assertThrows(IllegalStateException.class,()->library.setSaveMode(hash,RomSaveMode.NONE));
        assertEquals(RomSaveMode.MACHINE,library.saveMode(hash));assertArrayEquals(before,Files.readAllBytes(saved));
    }
}
