// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;

import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Descriptor-only: no runtime, DLL, ROM or save is opened by these tests. */
class SfcNetplayProfileTest {
    // Measured from the previously delivered full SFC alpha.52 JAR, SHA-256
    // 7bc66655f4331c554fe859d092dd00ec5e870b48222653021f64d3dcca800209.
    // The measurement used profile() and a synthetic all-'a' ROM hash, not game content.
    private static final String LEGACY_PROFILE="4787a2d6734ae94d835021519d2244812ac85e1e7458b6a583b2863e04febe5c";
    private static final String SYNTHETIC_CONTENT="44c2336fedab8ff6a85c74c2b94165377b0981f526adb9487895ca6314165e86";
    private static final String LEGACY_CONFIG="""
            mesen-s_aspect_ratio = "Auto"
            mesen-s_blend_high_res = "disabled"
            mesen-s_cubic_interpolation = "disabled"
            mesen-s_hle_coprocessor = "enabled"
            mesen-s_ntsc_filter = "Disabled"
            mesen-s_overclock = "None"
            mesen-s_overclock_type = "Before NMI"
            mesen-s_overscan_horizontal = "None"
            mesen-s_overscan_vertical = "None"
            mesen-s_ramstate = "All 0s"
            mesen-s_region = "NTSC"
            mesen-s_superfx_overclock = "100%"
            """;

    @Test void historicalProfileSaveIdentityMatchesDeliveredJarGolden(){
        for(var p:List.of(SfcNetplayProfile.profile(),SfcNetplayProfile.legacy().template())){
            var identity=NetplaySaveState.identity(p,"a".repeat(64),Map.of());
            assertEquals(LEGACY_PROFILE,identity.profile());assertEquals(SYNTHETIC_CONTENT,identity.content());
            assertEquals(LEGACY_CONFIG,p.config());
            assertEquals("/core/sfc-libretro/windows-x64/mesen-s_libretro.dll",p.resource());
            assertEquals("8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42",p.sha());
            assertEquals("content.sfc",p.contentName());assertEquals(257,p.device());assertEquals(2,p.ports());
            assertEquals(48000,p.sampleRate());assertEquals("Mesen-S",p.jni().name());
            assertEquals("sfc",p.jni().extension());assertFalse(p.jni().fullPath());assertFalse(p.jni().mesenGun());
            assertEquals(List.of(257,257),p.jni().devices());assertEquals(p.options(),p.jni().options());
        }
    }

    @Test void currentCoreHasIndependentSaveIdentityButSameContentDomain(){
        var p=SfcNetplayProfile.current().template();var identity=NetplaySaveState.identity(p,"a".repeat(64),Map.of());
        assertNotEquals(LEGACY_PROFILE,identity.profile());assertEquals(SYNTHETIC_CONTENT,identity.content());
        assertNotEquals(SfcNetplayProfile.current().launch(),SfcNetplayProfile.legacy().launch());
        assertNotEquals(SfcNetplayProfile.buildKey(SfcNetplayProfile.current()),SfcNetplayProfile.buildKey(SfcNetplayProfile.legacy()));
        assertEquals("sfc-snes9x-v1",SfcNetplayProfile.current().id());assertEquals("sfc-mesen-legacy",SfcNetplayProfile.legacy().id());
    }

    @Test void currentUsesPinnedUnmodifiedWindowsCoreAndTwoJoypads(){
        var p=SfcNetplayProfile.current().template();var runtime=p.jni();
        assertEquals("/core/sfc-snes9x/windows-x64/snes9x_libretro.dll",p.resource());
        assertEquals("5ce01616184f255623405793e192f79aef5089fa796b29bc11ddc5e052060124",p.sha());
        assertEquals("content.sfc",p.contentName());assertEquals(1,p.device());assertEquals(2,p.ports());
        assertEquals(48000,p.sampleRate());assertEquals(32*1024*1024+512,p.maxRomBytes());
        assertEquals(NetplayProfile.JniAspect.RAW,p.jniAspect());
        assertEquals("Snes9x",runtime.name());assertEquals("sfc",runtime.extension());
        assertFalse(runtime.fullPath());assertFalse(runtime.mesenGun());assertEquals(List.of(1,1),runtime.devices());
        assertEquals(Set.of("windows-x64"),runtime.cores().keySet());
        var artifact=runtime.cores().get("windows-x64");
        assertEquals(p.resource(),artifact.resource());assertEquals(p.sha(),artifact.sha256());assertEquals(3840000,artifact.maxBytes());
        assertEquals(p.options(),runtime.options());assertEquals(12,p.options().size());
        assertEquals("disabled",p.options().get("snes9x_randomize_memory"));
        assertEquals("disabled",p.options().get("snes9x_overclock_cycles"));
        assertEquals("disabled",p.options().get("snes9x_echo_buffer_hack"));
        assertEquals("enabled",p.options().get("snes9x_block_invalid_vram_access"));
    }

    @Test void registryReturnsExactRegisteredVariantsAndBuildKeys(){
        for(var variant:List.of(SfcNetplayProfile.current(),SfcNetplayProfile.legacy())){
            assertSame(variant,SfcNetplayProfile.resolve(variant.launch()));
            assertSame(variant,SfcNetplayProfile.resolveBuild(SfcNetplayProfile.buildKey(variant)));
            assertEquals("sfc-jni-v1:"+variant.launch().compatibilitySha(),SfcNetplayProfile.buildKey(variant));
            assertTrue(variant.persistenceSupported());
            assertDoesNotThrow(()->variant.verify(variant.launch(),variant.template()));
            assertDoesNotThrow(()->variant.verify(variant.launch(),variant.profile("same-content.sfc")));
        }
    }

    @Test void unsupportedOrTamperedLaunchCannotFallBackToEitherCore(){
        var current=SfcNetplayProfile.current();var launch=current.launch();
        for(var bad:List.of(NetplayLaunchSpec.UNSPECIFIED,
                new NetplayLaunchSpec("unregistered-core",launch.compatibilitySha(),true),
                new NetplayLaunchSpec(launch.variant(),"0".repeat(64),true),
                new NetplayLaunchSpec(launch.variant(),launch.compatibilitySha(),false)))
            assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolve(bad));
        assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolve(null));
        for(String bad:new String[]{null,"","sfc-jni-v1:"+"0".repeat(64),LibretroSfcCore.BUILD+":jni-v1",launch.variant()})
            assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.resolveBuild(bad));
        assertThrows(IllegalArgumentException.class,()->current.verify(launch,SfcNetplayProfile.legacy().template()));
        assertThrows(IllegalArgumentException.class,()->SfcNetplayProfile.legacy().verify(launch,current.template()));
    }

    @Test void localOptionMutationInvalidatesSelectedVariantInsteadOfSwitchingCore(){
        var current=SfcNetplayProfile.current();var p=current.template();
        var changedOptions=new java.util.TreeMap<>(p.options());changedOptions.put("snes9x_randomize_memory","enabled");
        var jni=p.jni();var changedJni=new cn.piq.retro.libretro.LibretroProfile(jni.name(),jni.extension(),jni.fullPath(),jni.devices(),jni.mesenGun(),changedOptions,jni.cores());
        var changed=p.withJni(changedJni);
        assertThrows(IllegalArgumentException.class,()->current.verify(current.launch(),changed));
    }
}
