package cn.piq.fcarcade.netplay;

import cn.piq.retro.libretro.LibretroProfile;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetplayVariantTest {
    private NetplayProfile profile(String sha,Map<String,String> options,int ports){return new NetplayProfile(getClass(),"/core/test.dll",sha,"contract.zip",options,1,48000,1024,ports);}
    private NetplayProfile ra(){return profile("a".repeat(64),Map.of("z","enabled","a","disabled"),4);}
    private NetplayProfile jni(){var p=ra();return p.withJni(new LibretroProfile("Test","zip",true,List.of(1,1,1,1),false,p.options(),Map.of("windows-x64",new LibretroProfile.Artifact(p.resource(),p.sha()))));}
    @Test void trustedCopyPreservesLegacySaveIdentityAndIgnoresOnlyContentName(){
        var old=jni().withJniAspect(NetplayProfile.JniAspect.PRESENTED);var variant=new NetplayVariant("legacy","JNI",old,true);
        var copy=variant.profile("dino.zip");assertEquals(old.jni(),copy.jni());assertEquals(old.jniAspect(),copy.jniAspect());
        assertDoesNotThrow(()->variant.verify(variant.launch(),copy));
        var original=new NetplayProfile(old.owner(),old.resource(),old.sha(),"dino.zip",old.options(),old.device(),old.sampleRate(),old.maxRomBytes(),old.ports(),old.jni(),old.jniAspect());
        assertEquals(NetplaySaveState.identity(original,"b".repeat(64),Map.of("bios.zip","c".repeat(64))),NetplaySaveState.identity(copy,"b".repeat(64),Map.of("bios.zip","c".repeat(64))));
    }
    @Test void fingerprintRejectsBackendCoreOptionsLimitsVariantAndPersistenceChanges(){
        var trusted=new NetplayVariant("retroarch-stock-v1","RA",ra(),false);var grant=trusted.launch();
        for(var different:List.of(jni(),profile("b".repeat(64),ra().options(),4),profile(ra().sha(),Map.of("z","disabled"),4),profile(ra().sha(),ra().options(),2),
                new NetplayProfile(getClass(),ra().resource(),ra().sha(),"dino.zip",ra().options(),2,48000,1024,4),
                new NetplayProfile(getClass(),ra().resource(),ra().sha(),"dino.zip",ra().options(),1,44100,1024,4),
                new NetplayProfile(getClass(),ra().resource(),ra().sha(),"dino.zip",ra().options(),1,48000,2048,4)))
            assertThrows(IllegalArgumentException.class,()->trusted.verify(grant,different));
        assertThrows(IllegalArgumentException.class,()->trusted.verify(new NetplayLaunchSpec("legacy",grant.compatibilitySha(),false),ra()));
        assertThrows(IllegalArgumentException.class,()->trusted.verify(new NetplayLaunchSpec(grant.variant(),grant.compatibilitySha(),true),ra()));
        assertThrows(IllegalArgumentException.class,()->trusted.verify(NetplayLaunchSpec.UNSPECIFIED,ra()));
    }
    @Test void sortedOptionsAndContentNamesAreDeterministic(){
        var first=new NetplayVariant("retroarch-stock-v1","RA",ra(),false);
        var other=new LinkedHashMap<String,String>();other.put("a","disabled");other.put("z","enabled");
        var second=new NetplayVariant(first.id(),"Other display label",profile(ra().sha().toUpperCase(Locale.ROOT),other,4),false);
        assertEquals(first.launch(),second.launch());assertDoesNotThrow(()->first.verify(second.launch(),second.profile("mslug.zip")));
    }
    @Test void unsupportedSavesAndMalformedNetworkSelectorsFailClosed(){
        var variant=new NetplayVariant("retroarch-stock-v1","RA",ra(),false);assertDoesNotThrow(()->variant.requireSaveMode(0));
        for(int mode:new int[]{-1,1,2,3})assertThrows(IllegalArgumentException.class,()->variant.requireSaveMode(mode));
        for(String id:List.of("","../core.dll","Legacy","a".repeat(49),"ra\n"))assertThrows(IllegalArgumentException.class,()->new NetplayLaunchSpec(id,"a".repeat(64),false));
        assertThrows(IllegalArgumentException.class,()->new NetplayLaunchSpec("retroarch-stock-v1","",false));
        assertThrows(IllegalArgumentException.class,()->new NetplayLaunchSpec("legacy","A".repeat(64),true));
    }
}
