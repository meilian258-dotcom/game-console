package cn.piq.mdhome.client;

import cn.piq.retro.libretro.LibretroRuntimes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MdCoreRetirementTest {
    @TempDir Path temporary;

    @Test void oldCommandAndConfigIdentifiersAreExplicitlyRejected(){
        for(String id:new String[]{"blastem","BLASTEM","BlastEm"})
            assertEquals(MdProfile.RETIRED_CORE_MESSAGE,assertThrows(IllegalArgumentException.class,()->MdProfile.resolve(id)).getMessage());
        assertThrows(IllegalArgumentException.class,()->MdProfile.resolve("unknown"));
        assertEquals(MdProfile.Core.GENESIS_PLUS_GX,MdProfile.resolve("genesis"));
        assertEquals(MdProfile.Core.GENESIS_PLUS_GX,MdProfile.resolve("GENESIS_PLUS_GX"));
    }

    @Test void legacyApiTokenCannotResolveAProfileInputOrSavePath(){
        assertEquals(MdProfile.RETIRED_CORE_MESSAGE,assertThrows(IllegalArgumentException.class,()->MdProfile.profile(MdProfile.Core.BLASTEM)).getMessage());
        assertThrows(IllegalArgumentException.class,()->MdProfile.input(MdProfile.Core.BLASTEM,0));
        for(var backend:LibretroRuntimes.Backend.values())
            assertThrows(IllegalArgumentException.class,()->MdProfile.saveNamespace(MdProfile.Core.BLASTEM,backend));
    }

    @Test void retiredStartupNeverClaimsOwnerOrTouchesOldProgress()throws Exception{
        Path saves=temporary.resolve("saves"),old=saves.resolve("jni-v1/3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016/old-state");
        Files.createDirectories(old.getParent());byte[] sentinel={4,8,15,16,23,42};Files.write(old,sentinel);
        boolean active=MdEngine.active();
        for(var backend:LibretroRuntimes.Backend.values()){
            assertEquals(MdProfile.RETIRED_CORE_MESSAGE,assertThrows(IllegalArgumentException.class,
                ()->new MdEngine(temporary.resolve("missing-rom.md"),saves,backend,MdProfile.Core.BLASTEM)).getMessage());
            assertEquals(active,MdEngine.active());assertArrayEquals(sentinel,Files.readAllBytes(old));
            Path absent=temporary.resolve("must-not-be-created");
            assertThrows(IllegalArgumentException.class,()->new MdEngine(temporary.resolve("missing-rom.md"),absent,backend,MdProfile.Core.BLASTEM,false,true));
            assertFalse(Files.exists(absent));
        }
        try(var paths=Files.walk(saves)){assertEquals(1,paths.filter(Files::isRegularFile).count());}
    }

    @Test void runtimeResourcesOmitRetiredDllButRetainLicensingEvidence(){
        assertNull(MdProfile.class.getResource("/core/windows-x64/blastem_libretro.dll"));
        assertNotNull(MdProfile.class.getResource("/core/windows-x64/genesis_plus_gx_libretro.dll"));
        assertNotNull(MdProfile.class.getResource("/core/windows-x64/genesis_plus_gx_piq_netplay_libretro.dll"));
        assertNotNull(MdProfile.class.getResource("/licenses/blastem/NOTICE.txt"));
    }
}
