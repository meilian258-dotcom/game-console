// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.nativearcade;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/** Public declaration only. Safe on a dedicated server: no client, JNA or process initialization. */
public final class NativeSnapshotProfile {
    private NativeSnapshotProfile(){}
    public static final String DIRECTORY="runtime-snapshot-v1";
    public static final String CORE_NAME="piqneogeo_libretro.dll",HELPER_NAME="piq-snapshot-helper.jar",JNA_NAME="jna-5.14.0.jar";
    public static final String CORE_SHA="E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201";
    public static final String HELPER_SHA="F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97";
    public static final String JNA_SHA="34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6";
    public static final String BIOS_SHA="E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC";
    public static final int BOOTSTRAP_FRAMES=32,PLAYERS=2,SNAPSHOT_INTERVAL=1800;
    public static final double FPS=59.18560791015625,SAMPLE_RATE=48000;
    public static final Map<String,String> ROMS=Map.of(
        "kof97.zip","804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F",
        "mslug2.zip","1A82D65E88050FDC75DBCEA180E48802D54C67A4748558FC4BD51C0103D56E4A");
    public static final Map<String,Long> ROM_BYTES=Map.of("kof97.zip",28745740L,"mslug2.zip",17473893L);
    public static final long BIOS_BYTES=953500,CORE_BYTES=56494080,HELPER_BYTES=43610,JNA_BYTES=1878533;
    public static final String PROFILE_SHA=hash(("PIQ-NEOGEO-SNAPSHOT-v1\n"+CORE_SHA+"\n"+HELPER_SHA+"\n"+JNA_SHA+"\n"+BIOS_SHA
        +"\nrtc=20000101000000\nTZ=UTC0\nbootstrap=32\nports=2\nfps=59.18560791015625\npcm=48000-stereo\n"
        +"helper-effective-defaults-fixed;read_config,write_config,autosave,cheats,threads,throttle,buttons_profiles=disabled\n"
        +"no-user-lua;reverb=0;no-live-microphone\n").getBytes(StandardCharsets.US_ASCII));
    public static final String COMPATIBILITY_ID="piq:neogeo-snapshot-v1:"+PROFILE_SHA;
    public static String hash(byte[] value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)).toUpperCase(java.util.Locale.ROOT);}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
