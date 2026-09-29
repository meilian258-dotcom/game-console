// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;
import cn.piq.fcarcade.netplay.NetplayProfile;
import java.util.Map;
public final class SfcNetplayProfile {
    private SfcNetplayProfile(){}
    public static NetplayProfile profile(){return new NetplayProfile(SfcNetplayProfile.class,
        "/core/sfc-libretro/windows-x64/mesen-s_libretro.dll","8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42","content.sfc",
        Map.of("mesen-s_region","NTSC","mesen-s_ramstate","All 0s","mesen-s_overscan_vertical","None","mesen-s_overscan_horizontal","None","mesen-s_overclock","None","mesen-s_hle_coprocessor","enabled"),257,48000,32*1024*1024+512);}
}
