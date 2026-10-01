package cn.piq.retro.libretro;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LibretroArtifactBudgetTest {
    @Test void oldConstructorsRetainBoundedDefault(){
        assertEquals(256L*1024*1024,new LibretroProfile.Artifact("/core/test.dll","a".repeat(64)).maxBytes());
    }
    @Test void trustedLargeCoreStillHasHardLimitAndIdentity(){
        var artifact=new LibretroProfile.Artifact("/native-runtime/mame.dll","b".repeat(64),372431360);
        assertEquals(372431360,artifact.maxBytes());assertEquals("b".repeat(64),artifact.sha256());
        for(long bytes:new long[]{-1,0,512L*1024*1024+1,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,()->new LibretroProfile.Artifact("/core/test.dll","a".repeat(64),bytes));
        assertThrows(IllegalArgumentException.class,()->new LibretroProfile.Artifact("../outside.dll","a".repeat(64),372431360));
    }
}
