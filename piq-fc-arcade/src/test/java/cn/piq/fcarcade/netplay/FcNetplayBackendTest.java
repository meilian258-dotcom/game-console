package cn.piq.fcarcade.netplay;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FcNetplayBackendTest {
    @Test void additionalOfficialPadIsExplicitAndCannotBecomeGunOrJni() {
        var selected=FcNetplayBackend.launch(false,false,FcNetplayBackend.OFFICIAL_PAD);
        assertNotEquals(FcNetplayBackend.launch(false,false),selected);
        assertNotEquals(FcNetplayBackend.launch(false,true),selected);
        assertEquals(FcRetroarchPad.profile(),FcNetplayBackend.retroarchProfile(false,selected));
        assertEquals(NetplayProfile.fc(),FcNetplayBackend.retroarchProfile(false,FcNetplayBackend.launch(false,false)));
        assertEquals(FcNetplayBackend.launch(false,false),FcNetplayBackend.launch(false,false,""));
        assertDoesNotThrow(()->FcNetplayBackend.verify(false,false,selected));
        assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.verify(true,false,selected));
        assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.verify(false,true,selected));
        for(String unknown:new String[]{"unknown","FCEUMM-v1","../core.dll"})
            assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.launch(false,false,unknown));
        assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.launch(true,false,FcNetplayBackend.OFFICIAL_PAD));
        assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.launch(false,true,FcNetplayBackend.OFFICIAL_PAD));
        assertThrows(IllegalArgumentException.class,()->FcNetplayBackend.launch(false,false,null));
        assertEquals(257,FcRetroarchPad.profile().deviceForPort(0));
        assertEquals(257,FcRetroarchPad.profile().deviceForPort(1));
        assertEquals(48000,FcRetroarchPad.profile().sampleRate());
        assertEquals(2,FcRetroarchPad.profile().ports());
        assertNull(FcRetroarchPad.profile().jni());
    }

    @Test void newSaveCannotReadOrOverwriteAnyHistoricalIdentity() {
        String rom="a".repeat(64),owner="owner",variant=FcNetplayBackend.OFFICIAL_PAD;
        var identity=FcNetplaySaves.identity(false,false,variant,rom);
        byte[] state=NetplaySaveState.encode(new NetplaySaveState.Parts(identity,1,new byte[]{7},new byte[0],new byte[0]));
        assertTrue(FcNetplaySaves.accepts(false,false,variant,rom,state));
        assertFalse(FcNetplaySaves.accepts(true,false,variant,rom,state));
        assertFalse(FcNetplaySaves.accepts(false,true,variant,rom,state));
        for(boolean gun:new boolean[]{false,true})for(boolean jni:new boolean[]{false,true}){
            var oldIdentity=FcNetplaySaves.identity(gun,jni,rom);
            assertNotEquals(oldIdentity.profile(),identity.profile());
            assertEquals(oldIdentity.content(),identity.content());
            assertNotEquals(FcNetplaySaves.key(gun,jni,owner),FcNetplaySaves.key(false,false,variant,owner));
            assertFalse(FcNetplaySaves.accepts(gun,jni,rom,state));
            byte[] old=NetplaySaveState.encode(new NetplaySaveState.Parts(oldIdentity,1,new byte[]{8},new byte[0],new byte[0]));
            assertFalse(FcNetplaySaves.accepts(false,false,variant,rom,old));
        }
        assertTrue(FcNetplaySaves.ownerKeys(owner).contains(FcNetplaySaves.key(false,false,variant,owner)));
        assertEquals("core|nes-netplay-fceumm-v1|owner",FcNetplaySaves.key(false,false,variant,owner));
    }

    @Test void fourTrustedRoutesHaveDistinctFrozenContracts() {
        var selectors = new HashSet<NetplayLaunchSpec>();
        var fingerprints = new HashSet<String>();
        for (boolean gun : new boolean[]{false, true}) for (boolean jni : new boolean[]{false, true}) {
            var launch = FcNetplayBackend.launch(gun, jni);
            assertTrue(selectors.add(launch));
            assertTrue(fingerprints.add(launch.compatibilitySha()));
            assertTrue(launch.persistenceSupported());
            assertDoesNotThrow(() -> FcNetplayBackend.verify(gun, jni, launch));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(gun, !jni, launch));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(!gun, jni, launch));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(gun, jni, NetplayLaunchSpec.UNSPECIFIED));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(gun, jni, null));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(gun, jni,
                    new NetplayLaunchSpec(launch.variant(), "0".repeat(64), true)));
            assertThrows(IllegalArgumentException.class, () -> FcNetplayBackend.verify(gun, jni,
                    new NetplayLaunchSpec(launch.variant(), launch.compatibilitySha(), false)));
        }
    }

    @Test void retroarchReusesTheSharedTrustedVariantAndFixedRunner() {
        for (boolean gun : new boolean[]{false, true}) {
            var declaration = new NetplayVariant(gun ? "fc-retroarch-zapper-v1" : "fc-retroarch-pad-v1",
                    "Display labels do not affect compatibility", gun ? NetplayProfile.fcZapper() : NetplayProfile.fc(), true);
            assertEquals(declaration.launch(), FcNetplayBackend.launch(gun, false));
        }
        assertTrue(FcNetplayBackend.label(false).contains("RetroArch"));
        assertTrue(FcNetplayBackend.label(true).contains("JNI"));
    }

    @Test void launchContractDoesNotMigrateAnyHistoricalSaveDomain() {
        String rom = "a".repeat(64);
        for (boolean gun : new boolean[]{false, true}) {
            var ra = NetplaySaveState.identity(gun ? NetplayProfile.fcZapper() : NetplayProfile.fc(), rom, Map.of());
            var p = JniNetplaySession.profile(gun);
            var oldDescriptor = (gun ? "PIQ-JNI-Netplay-Zapper-v1\n" : "PIQ-JNI-Netplay-v2\n")
                    + p.cores().get("windows-x64").sha256() + "\n" + p.name() + "\n" + p.extension()
                    + "\n" + p.fullPath() + "\n" + p.devices() + "\n" + p.options();
            var oldJni = new NetplaySaveState.Identity(NetplaySaveState.hash(oldDescriptor.getBytes(StandardCharsets.UTF_8)), ra.content());
            FcNetplayBackend.launch(gun, false);
            FcNetplayBackend.launch(gun, true);
            assertEquals(ra, FcNetplaySaves.identity(gun, false, rom));
            assertEquals(oldJni, FcNetplaySaves.identity(gun, true, rom));
            assertNotEquals(ra.profile(), oldJni.profile());
            var save = NetplaySaveState.encode(new NetplaySaveState.Parts(oldJni, 1, new byte[]{1}, new byte[0], new byte[0]));
            assertFalse(FcNetplaySaves.accepts(gun, false, rom, save));
            assertTrue(FcNetplaySaves.accepts(gun, true, rom, save));
        }
        assertEquals("core|nes-netplay-v1|owner", FcNetplaySaves.key(false, false, "owner"));
        assertEquals("core|nes-jni-netplay-v2|owner", FcNetplaySaves.key(false, true, "owner"));
    }
}
