package cn.piq.fcarcade.netplay;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcNetplaySavesTest {
    private static final String ROM="a".repeat(64);
    // Read from the frozen FC76.57 JAR without loading a native core. These are
    // independent fixtures, not values recomputed from the profile under test.
    private static final String CONTENT="44c2336fedab8ff6a85c74c2b94165377b0981f526adb9487895ca6314165e86";
    private static final String[][] PROFILES={
        {"4595853789e3e573e812f746fc89804ddf71413bcc0c34cba225b202b94a71d3",
         "5729ff12ef29494a2bebed48837f0b2793464784f6137fee179c9034b6b2446c"},
        {"7c4f467a2a7bbdc1e6b4e4183d6118b9b8f45cfd4f1d17c3feddbbfcb0866314",
         "a6b75a7993bbe231a2d7f581ed18a204ac5f4374aceba645483b2ef9688ce008"}
    };

    @Test void historicalFourDomainsKeepExactProfileContentAndKeyFixtures() {
        String[][] prefixes={{"core|nes-netplay-v1|","core|nes-jni-netplay-v2|"},
                {"core|nes-netplay-zapper-v1|","core|nes-jni-netplay-zapper-v1|"}};
        for(int gun=0;gun<2;gun++)for(int jni=0;jni<2;jni++) {
            var expected=new NetplaySaveState.Identity(PROFILES[gun][jni],CONTENT);
            assertEquals(expected,FcNetplaySaves.identity(gun==1,jni==1,ROM));
            assertEquals(prefixes[gun][jni]+"owner",FcNetplaySaves.key(gun==1,jni==1,"owner"));
            byte[] saved=NetplaySaveState.encode(new NetplaySaveState.Parts(expected,9,new byte[]{1,2},new byte[]{3},new byte[0]));
            assertTrue(FcNetplaySaves.accepts(gun==1,jni==1,ROM,saved));
            assertFalse(FcNetplaySaves.accepts(gun==1,jni==0,ROM,saved));
            assertFalse(FcNetplaySaves.accepts(gun==0,jni==1,ROM,saved));
        }
        assertEquals(FcNetplaySaves.identity(false,false,ROM),FcNetplaySaves.identity(false,ROM));
        assertEquals(FcNetplaySaves.identity(false,true,ROM),FcNetplaySaves.jniIdentity(ROM));
    }

    @Test void immutableDomainEnumerationIncludesJniLightGunWithoutChangingOwnerText() {
        var historical=List.of("core|nes-netplay-v1|","core|nes-netplay-zapper-v1|",
                "core|nes-jni-netplay-v2|","core|nes-jni-netplay-zapper-v1|");
        assertTrue(FcNetplaySaves.allPrefixes().containsAll(historical));
        assertEquals(5,FcNetplaySaves.allPrefixes().size());
        assertTrue(FcNetplaySaves.allPrefixes().contains("core|nes-netplay-fceumm-v1|"));
        assertEquals(FcNetplaySaves.allPrefixes().size(),FcNetplaySaves.allPrefixes().stream().distinct().count());
        String owner="cartridge|12345678-1234-5678-90ab-123456789abc";
        assertEquals(FcNetplaySaves.allPrefixes().stream().map(prefix->prefix+owner).toList(),FcNetplaySaves.ownerKeys(owner));
        assertThrows(UnsupportedOperationException.class,()->FcNetplaySaves.allPrefixes().clear());
        assertThrows(UnsupportedOperationException.class,()->FcNetplaySaves.ownerKeys(owner).clear());
    }
}
