package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import cn.piq.retro.libretro.LibretroProfile;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CabinetOperatingFilesTest {
    private final NetplaySaveState.Identity game=new NetplaySaveState.Identity("a".repeat(64),"b".repeat(64));
    @Test void separateIdentityRejectsGameStateCrossCoreAndCrossContent(){
        var identity=CabinetOperatingFiles.identity(game);assertNotEquals(game.profile(),identity.profile());assertEquals(game.content(),identity.content());
        byte[] bytes={1,2,3},envelope=CabinetOperatingFiles.pack(identity,bytes);assertArrayEquals(bytes,CabinetOperatingFiles.unpack(identity,envelope));
        assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.unpack(game,envelope));
        assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.unpack(CabinetOperatingFiles.identity(new NetplaySaveState.Identity("c".repeat(64),game.content())),envelope));
        assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.unpack(CabinetOperatingFiles.identity(new NetplaySaveState.Identity(game.profile(),"d".repeat(64))),envelope));
        assertNull(CabinetOperatingFiles.unpack(identity,null));
    }
    @Test void progressOrMemoryPayloadCannotMasqueradeAsOperatingFile(){
        var id=CabinetOperatingFiles.identity(game);
        for(var p:List.of(new NetplaySaveState.Parts(id,1,new byte[]{1},new byte[0],new byte[0]),new NetplaySaveState.Parts(id,0,new byte[]{1},new byte[]{2},new byte[0]),new NetplaySaveState.Parts(id,0,new byte[]{1},new byte[0],new byte[]{2})))
            assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.unpack(id,NetplaySaveState.encode(p)));
        assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.pack(id,new byte[0]));
        assertThrows(IllegalArgumentException.class,()->CabinetOperatingFiles.pack(id,new byte[CabinetOperatingFiles.MAX_BYTES+1]));
    }
    private NetplayProfile profile(String sha,boolean jni){
        var p=new NetplayProfile(getClass(),"/native-runtime/test.dll",sha,"kov.zip",Map.of(),1,48000,1024,4);
        return jni?p.withJni(new LibretroProfile("FinalBurn Neo","zip",true,List.of(1,1,1,1),false,Map.of(),Map.of("windows-x64",new LibretroProfile.Artifact(p.resource(),p.sha())))):p;
    }
    @Test void onlyExplicitPinnedJniCoreUsesFsAdapter(){
        String sha="9876".repeat(16);CabinetOperatingFiles.registerFbneoJni(sha);
        assertTrue(CabinetOperatingFiles.supported(profile(sha,true)));assertEquals("fbneo/kov.fs",CabinetOperatingFiles.relative(profile(sha,true)));
        assertFalse(CabinetOperatingFiles.supported(profile(sha,false)));assertFalse(CabinetOperatingFiles.supported(profile("6789".repeat(16),true)));
    }
}
