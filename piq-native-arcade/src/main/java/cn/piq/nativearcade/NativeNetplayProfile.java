package cn.piq.nativearcade;

import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.netplay.NetplayProfile;
import java.util.*;

/** Common-side trusted profile: the server never loads a client backend class. */
public final class NativeNetplayProfile {
    public static final String CORE_SHA="73579C4C50D1F16F5D52A1E5BF4D81106C40962BC284E83E4A96DC7BB68FD424";
    public static final String CORE_RESOURCE="/native-runtime/win-x64-fbneo-pgm-v2/fbneo_libretro.dll";
    private NativeNetplayProfile(){}
    public static NetplayProfile profile(String name){
        if(!validGameName(name))throw new IllegalArgumentException("Invalid arcade ZIP name");
        return new NetplayProfile(NativeNetplayProfile.class,CORE_RESOURCE,CORE_SHA,name,
            Map.of("fbneo-samplerate","48000","fbneo-frameskip","0","fbneo-force-60hz","disabled","fbneo-hiscores","disabled","fbneo-diagnostic-input","Hold Start + L + R"),5,48000,64*1024*1024,4);
    }
    public static boolean validGameName(String name){return name!=null&&name.matches("[a-z0-9_]{1,32}\\.zip")&&!CabinetGameManifest.BIOS.contains(name)
        &&!name.substring(0,name.length()-4).toUpperCase(Locale.ROOT).matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]");}
}
