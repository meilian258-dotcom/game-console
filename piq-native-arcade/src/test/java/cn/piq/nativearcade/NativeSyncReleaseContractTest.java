// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade;

import cn.piq.nativearcade.bridge.NativeSnapshotState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Promotion must not broaden the verified local-sync profile.
 * Expected values were independently read with javap from frozen Native alpha.17:
 * review-bundled42-v2/game_console_arcade-0.1.0-alpha.17.jar,
 * SHA-256 875CE76B8B987E533A50EF34612116FD06455CFFF4A9BE787BCFE0720F650B05.
 * These tests read compiled profile fields and exercise only the pure state envelope;
 * they do not initialize a native session, load a DLL, or read any game/BIOS files.
 * NativeSnapshotStateTest supplies the exhaustive envelope-corruption/bounds tests.
 */
class NativeSyncReleaseContractTest {
    private static final String KOF97_SHA =
        "804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F";
    private static final String MSLUG2_SHA =
        "1A82D65E88050FDC75DBCEA180E48802D54C67A4748558FC4BD51C0103D56E4A";
    private static final String BIOS_SHA =
        "E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC";
    private static final String PROFILE_SHA =
        "D569E1876BD29141843CBA6A666441227ECC707BC7D10C90E36E166B70AEFC28";

    // Reflection deliberately avoids javac inlining String/primitive ConstantValue fields.
    // Profile initialization only constructs immutable maps and hashes its fixed ASCII identity.
    private static Object field(String name) throws ReflectiveOperationException {
        return NativeSnapshotProfile.class.getField(name).get(null);
    }

    @Test void onlyTheTwoFrozenRomSetsAndExactZipSizesRemainAdmitted() throws Exception {
        assertEquals(Map.of("kof97.zip", KOF97_SHA, "mslug2.zip", MSLUG2_SHA), field("ROMS"));
        assertEquals(Map.of("kof97.zip", 28745740L, "mslug2.zip", 17473893L), field("ROM_BYTES"));
    }

    @Test void localSyncCapacityRemainsExactlyTwoSeats() throws Exception {
        assertEquals(2, field("PLAYERS"));
    }

    @Test void snapshotRuntimeKeepsItsIndependentDirectoryAndThreeFilenames() throws Exception {
        assertEquals("runtime-snapshot-v1", field("DIRECTORY"));
        assertEquals("piqneogeo_libretro.dll", field("CORE_NAME"));
        assertEquals("piq-snapshot-helper.jar", field("HELPER_NAME"));
        assertEquals("jna-5.14.0.jar", field("JNA_NAME"));
    }

    @Test void allThreeRuntimeArtifactsKeepTheirFrozenHashesAndSizes() throws Exception {
        assertEquals("E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201", field("CORE_SHA"));
        assertEquals(56494080L, field("CORE_BYTES"));
        assertEquals("F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97", field("HELPER_SHA"));
        assertEquals(43610L, field("HELPER_BYTES"));
        assertEquals("34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6", field("JNA_SHA"));
        assertEquals(1878533L, field("JNA_BYTES"));
    }

    @Test void biosIdentityRemainsTheFrozenExactSet() throws Exception {
        assertEquals(BIOS_SHA, field("BIOS_SHA"));
        assertEquals(953500L, field("BIOS_BYTES"));
    }

    @Test void profileFingerprintAndCompatibilityIdDoNotChangeWithTheDisplayName() throws Exception {
        assertEquals(PROFILE_SHA, field("PROFILE_SHA"));
        assertEquals("piq:neogeo-snapshot-v1:" + PROFILE_SHA, field("COMPATIBILITY_ID"));
    }

    @Test void bootstrapFrameRateAudioRateAndCheckpointCadenceRemainFrozen() throws Exception {
        assertEquals(32, field("BOOTSTRAP_FRAMES"));
        assertEquals(1800, field("SNAPSHOT_INTERVAL"));
        assertEquals(Double.doubleToLongBits(59.18560791015625d),
            Double.doubleToLongBits((Double) field("FPS")));
        assertEquals(Double.doubleToLongBits(48000.0d),
            Double.doubleToLongBits((Double) field("SAMPLE_RATE")));
    }

    @Test void pureEnvelopeBindsBothGamesToTheFrozenProfileAndBios() throws Exception {
        byte[] payload = {0, 1, 2, -1, 44};
        for (String romSha : new String[]{KOF97_SHA, MSLUG2_SHA}) {
            byte[] encoded = NativeSnapshotState.encode(32, romSha, payload);
            assertEquals(148 + payload.length, encoded.length);
            assertArrayEquals(HexFormat.of().parseHex(PROFILE_SHA), Arrays.copyOfRange(encoded, 16, 48));
            assertArrayEquals(HexFormat.of().parseHex(romSha), Arrays.copyOfRange(encoded, 48, 80));
            assertArrayEquals(HexFormat.of().parseHex(BIOS_SHA), Arrays.copyOfRange(encoded, 80, 112));
            assertArrayEquals(payload, NativeSnapshotState.decode(encoded, romSha, 0).payload());
            String otherRomSha = romSha.equals(KOF97_SHA) ? MSLUG2_SHA : KOF97_SHA;
            assertThrows(IOException.class, () -> NativeSnapshotState.decode(encoded, otherRomSha, 0));
            encoded[16] ^= 1;
            assertThrows(IOException.class, () -> NativeSnapshotState.decode(encoded, romSha, 0));
        }
        assertThrows(IOException.class,
            () -> NativeSnapshotState.encode(32, "0".repeat(64), payload));
    }

    @Test void firstCheckpointKeepsTheExactBootstrapOffset() throws Exception {
        byte[] payload = {7};
        assertThrows(IOException.class, () -> NativeSnapshotState.encode(31, KOF97_SHA, payload));
        byte[] checkpoint = NativeSnapshotState.encode(1832, KOF97_SHA, payload);
        assertEquals(1832L, NativeSnapshotState.decode(checkpoint, KOF97_SHA, 1800).internalFrame());
        for (long wrongLogicalFrame : new long[]{1799, 1801, 1832}) {
            assertThrows(IOException.class,
                () -> NativeSnapshotState.decode(checkpoint, KOF97_SHA, wrongLogicalFrame));
        }
    }
}
