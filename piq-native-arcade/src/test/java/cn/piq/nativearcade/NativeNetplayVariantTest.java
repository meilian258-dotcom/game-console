package cn.piq.nativearcade;

import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Pure contracts: no DLL or emulator is started. */
class NativeNetplayVariantTest {
    @Test void exactLegacyProfileAndSaveIdentityRemainTheDefault(){
        var historical=new NetplayProfile(NativeNetplayProfile.class,
            "/native-runtime/win-x64-fbneo-state-v3/fbneo_libretro.dll",
            "3E0AB5DB5898E9E75E1704CAA703D6F4E4CB269C4F971AB498B434F22D18F885","dino.zip",
            Map.of(),1,48000,96*1024*1024,4).withJni(NativeNetplayProfile.runtime())
            .withJniAspect(NetplayProfile.JniAspect.PRESENTED);
        var current=NativeNetplayProfile.profile("dino.zip");
        var explicit=NativeNetplayProfile.profile("dino.zip",NativeNetplayProfile.LEGACY);
        assertEquals(historical,current);assertEquals(current,explicit);
        String content=NetplaySaveState.hash(new byte[]{1,2,3});
        assertEquals(NetplaySaveState.identity(historical,content,Map.of()),NetplaySaveState.identity(explicit,content,Map.of()));
    }
    @Test void stockUsesPinnedOriginalWithoutJniOrPersistence(){
        var variant=NativeNetplayProfile.variant(NativeNetplayProfile.RETROARCH_STOCK);
        var p=variant.profile("dino.zip");
        assertNull(p.jni());assertFalse(variant.persistenceSupported());
        assertEquals("52343F65453C0E1FC06B3AA818B48CA4FED18361F7F336A684E9E3CFFA5F5F3C",p.sha());
        assertEquals("/native-runtime/win-x64-fbneo-stock-v1/fbneo_libretro.dll",p.resource());
        assertEquals(4,p.ports());assertEquals(48000,p.sampleRate());assertEquals(96*1024*1024,p.maxRomBytes());
        for(int port=0;port<4;port++)assertEquals(1,p.deviceForPort(port));
        assertEquals(Map.of("fbneo-samplerate","48000","fbneo-fixed-frameskip","0","fbneo-force-60hz","disabled",
            "fbneo-allow-patched-romsets","disabled","fbneo-diagnostic-input","Hold Start + L + R"),p.options());
        assertDoesNotThrow(()->variant.requireSaveMode(0));
        assertThrows(IllegalArgumentException.class,()->variant.requireSaveMode(1));
        assertThrows(IllegalArgumentException.class,()->variant.requireSaveMode(2));
    }
    @Test void commonAndClientProfilesMustMatchAndLegacyGrantCannotStartStock(){
        var old=NativeNetplayProfile.variant(NativeNetplayProfile.LEGACY);
        var stock=NativeNetplayProfile.variant(NativeNetplayProfile.RETROARCH_STOCK);
        assertNotEquals(old.launch(),stock.launch());
        assertDoesNotThrow(()->stock.verify(stock.launch(),stock.profile("kov.zip")));
        assertThrows(IllegalArgumentException.class,()->stock.verify(old.launch(),stock.profile("dino.zip")));
        assertThrows(IllegalArgumentException.class,()->stock.verify(stock.launch(),old.profile("dino.zip")));
        assertEquals(List.of(NativeNetplayProfile.LEGACY,NativeNetplayProfile.RETROARCH_STOCK),
            NativeNetplayProfile.variants().stream().map(v->v.id()).toList());
    }
    @Test void unknownVariantAndUnsafeContentAreRejectedWithoutFallback(){
        for(String value:List.of("retroarch","JNI","state-v3","unknown","../x"))
            assertThrows(IllegalArgumentException.class,()->NativeNetplayProfile.profile("dino.zip",value));
        assertThrows(IllegalArgumentException.class,()->NativeNetplayProfile.variant(null));
        for(String name:List.of("../dino.zip","pgm.zip","qsound.zip","con.zip","dino.exe"))
            assertThrows(IllegalArgumentException.class,()->NativeNetplayProfile.profile(name,NativeNetplayProfile.RETROARCH_STOCK));
    }
    @Test void stockAndLegacySaveIdentitiesAreNeverInterchangeable(){
        String content=NetplaySaveState.hash(new byte[]{1,2,3});
        var old=NetplaySaveState.identity(NativeNetplayProfile.profile("dino.zip"),content,Map.of());
        var stock=NetplaySaveState.identity(NativeNetplayProfile.profile("dino.zip",NativeNetplayProfile.RETROARCH_STOCK),content,Map.of());
        assertNotEquals(old.profile(),stock.profile());assertEquals(old.content(),stock.content());
        byte[] oldState=NetplaySaveState.encode(new NetplaySaveState.Parts(old,5,new byte[]{1},new byte[0],new byte[0]));
        assertThrows(IllegalArgumentException.class,()->NetplaySaveState.decode(oldState,stock));
        assertEquals(5,NetplaySaveState.decode(oldState,old).frame());
    }
}
