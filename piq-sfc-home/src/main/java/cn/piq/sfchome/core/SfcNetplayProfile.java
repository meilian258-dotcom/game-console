// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.sfchome.core;
import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.netplay.NetplayVariant;
import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import cn.piq.retro.libretro.LibretroProfile;
import java.util.List;
import java.util.Map;
public final class SfcNetplayProfile {
    private SfcNetplayProfile(){}
    /** Historical Mesen-S profile: its effective options and save identity must remain unchanged. */
    public static NetplayProfile profile(){return new NetplayProfile(SfcNetplayProfile.class,
        "/core/sfc-libretro/windows-x64/mesen-s_libretro.dll","8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42","content.sfc",
        Map.of("mesen-s_region","NTSC","mesen-s_ramstate","All 0s","mesen-s_overscan_vertical","None","mesen-s_overscan_horizontal","None","mesen-s_overclock","None","mesen-s_hle_coprocessor","enabled"),257,48000,32*1024*1024+512).withJni(runtime());}
    private static cn.piq.retro.libretro.LibretroProfile runtime(){
        var base=LibretroSfcCore.profile();var options=new java.util.TreeMap<>(base.options());options.put("mesen-s_region","NTSC");
        return new cn.piq.retro.libretro.LibretroProfile(base.name(),base.extension(),base.fullPath(),base.devices(),false,options,base.cores());
    }

    private static final class Variants {
        static final NetplayVariant LEGACY=new NetplayVariant("sfc-mesen-legacy","Mesen-S 旧进度",profile(),true);
        static final NetplayVariant CURRENT=new NetplayVariant("sfc-snes9x-v1","Snes9x 联机进度",snes9x(),true);
    }
    public static NetplayVariant current(){return Variants.CURRENT;}
    public static NetplayVariant legacy(){return Variants.LEGACY;}
    public static String buildKey(NetplayVariant variant){return "sfc-jni-v1:"+variant.launch().compatibilitySha();}
    /** The network chooses a registered key, never a DLL, runtime option or executable. */
    public static NetplayVariant resolve(NetplayLaunchSpec spec){
        if(spec!=null)for(var variant:List.of(current(),legacy()))if(variant.launch().equals(spec))return variant;
        throw new IllegalArgumentException("SFC 联机核心不匹配，请安装同批主包和完整 SFC 附属；不会自动切换核心");
    }
    public static NetplayVariant resolveBuild(String build){
        for(var variant:List.of(current(),legacy()))if(buildKey(variant).equals(build))return variant;
        throw new IllegalArgumentException("SFC 联机核心身份未知，请安装同批配套版本");
    }
    private static NetplayProfile snes9x(){
        String resource="/core/sfc-snes9x/windows-x64/snes9x_libretro.dll";
        String sha="5ce01616184f255623405793e192f79aef5089fa796b29bc11ddc5e052060124";
        var options=Map.ofEntries(
            Map.entry("snes9x_region","auto"),Map.entry("snes9x_randomize_memory","disabled"),
            Map.entry("snes9x_up_down_allowed","disabled"),Map.entry("snes9x_overclock_superfx","100%"),
            Map.entry("snes9x_overclock_cycles","disabled"),Map.entry("snes9x_reduce_sprite_flicker","disabled"),
            Map.entry("snes9x_block_invalid_vram_access","enabled"),Map.entry("snes9x_echo_buffer_hack","disabled"),
            Map.entry("snes9x_audio_interpolation","gaussian"),Map.entry("snes9x_blargg","disabled"),
            Map.entry("snes9x_overscan","disabled"),Map.entry("snes9x_mode7_hires","disabled"));
        var runtime=new LibretroProfile("Snes9x","sfc",false,List.of(1,1),false,options,
            Map.of("windows-x64",new LibretroProfile.Artifact(resource,sha,3840000)));
        return new NetplayProfile(SfcNetplayProfile.class,resource,sha,"content.sfc",options,1,48000,
            32*1024*1024+512).withJni(runtime);
    }
}
