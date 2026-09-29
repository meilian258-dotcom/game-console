package cn.piq.fcarcade.cabinet;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CabinetPgmManifestTest {
    private CabinetGameManifest.Entry file(String name,int n){return new CabinetGameManifest.Entry(name,Integer.toHexString(n).repeat(64),100);}
    @Test void allFourDeclaredCompanionsTransferWithinOriginalByteBudget(){
        var m=new CabinetGameManifest("piq_native_arcade:mame",List.of(file("kov.zip",1),file("pgm.zip",2),file("qsound.zip",3),file("qsound_hle.zip",4),file("neogeo.zip",5)));
        var p=new CabinetGameUploadPlan(m,31);assertEquals(500,p.missingBytes());for(int i=0;i<5;i++){assertEquals(i,p.nextFile());p.accepted(i,0,100);}p.requireComplete();assertEquals(128*1024*1024,CabinetGameManifest.MAX_TOTAL);
    }
    @Test void biosCannotBeSelectedAsGame(){for(String s:CabinetGameManifest.BIOS)assertThrows(IllegalArgumentException.class,()->new CabinetGameManifest("piq_native_arcade:mame",List.of(file(s,1))));}
    @Test void arbitraryParentAndExecutableAreStillNotAutoUploaded(){for(String s:List.of("parent.zip","helper.exe","core.dll","../pgm.zip"))assertThrows(IllegalArgumentException.class,()->new CabinetGameManifest("piq_native_arcade:mame",List.of(file("kov.zip",1),file(s,2))));}
}
