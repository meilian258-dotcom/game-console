package cn.piq.nativearcade;

import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.netplay.NetplayProfile;
import java.util.*;

/** Common-side trusted profile: the server never loads a client backend class. */
public final class NativeNetplayProfile {
    public static final String CORE_SHA="3E0AB5DB5898E9E75E1704CAA703D6F4E4CB269C4F971AB498B434F22D18F885";
    public static final String CORE_RESOURCE="/native-runtime/win-x64-fbneo-state-v3/fbneo_libretro.dll";
    private NativeNetplayProfile(){}
    public static NetplayProfile profile(String name){
        if(!validGameName(name))throw new IllegalArgumentException("Invalid arcade ZIP name");
        return new NetplayProfile(NativeNetplayProfile.class,CORE_RESOURCE,CORE_SHA,name,
            Map.of(),1,48000,64*1024*1024,4).withJni(runtime()).withJniAspect(NetplayProfile.JniAspect.PRESENTED);
    }
    public static cn.piq.retro.libretro.LibretroProfile runtime(){
        return new cn.piq.retro.libretro.LibretroProfile("FinalBurn Neo","zip",true,List.of(1,1,1,1),false,
            Map.of("fbneo-samplerate","48000","fbneo-fixed-frameskip","0","fbneo-force-60hz","disabled",
                "fbneo-allow-patched-romsets","disabled","fbneo-diagnostic-input","Hold Start + L + R"),
            Map.of("windows-x64",new cn.piq.retro.libretro.LibretroProfile.Artifact(CORE_RESOURCE,CORE_SHA)));
    }
    public static boolean validGameName(String name){return name!=null&&name.matches("[a-z0-9_]{1,32}\\.zip")&&!CabinetGameManifest.BIOS.contains(name)
        &&!name.substring(0,name.length()-4).toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");}
}
