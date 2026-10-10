package cn.piq.nativearcade;

import cn.piq.fcarcade.cabinet.CabinetGameManifest;
import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.netplay.NetplayLaunchSpec;
import cn.piq.fcarcade.netplay.NetplayVariant;
import java.util.*;

/** Common-side trusted profile: the server never loads a client backend class. */
public final class NativeNetplayProfile {
    public static final String CORE_SHA="3E0AB5DB5898E9E75E1704CAA703D6F4E4CB269C4F971AB498B434F22D18F885";
    public static final String CORE_RESOURCE="/native-runtime/win-x64-fbneo-state-v3/fbneo_libretro.dll";
    public static final String LEGACY=NetplayLaunchSpec.LEGACY;
    public static final String RETROARCH_STOCK="retroarch-stock-v1";
    public static final String STOCK_CORE_SHA="52343F65453C0E1FC06B3AA818B48CA4FED18361F7F336A684E9E3CFFA5F5F3C";
    public static final String STOCK_CORE_RESOURCE="/native-runtime/win-x64-fbneo-stock-v1/fbneo_libretro.dll";
    // Independent immutable contract: changes to JNI defaults must not change the stock variant.
    private static final Map<String,String> STOCK_OPTIONS=Map.of(
        "fbneo-samplerate","48000","fbneo-fixed-frameskip","0","fbneo-force-60hz","disabled",
        "fbneo-allow-patched-romsets","disabled","fbneo-diagnostic-input","Hold Start + L + R");
    private NativeNetplayProfile(){}
    /** The no-argument variant remains the exact legacy JNI profile and save identity. */
    public static NetplayProfile profile(String name){
        if(!validGameName(name))throw new IllegalArgumentException("Invalid arcade ZIP name");
        return new NetplayProfile(NativeNetplayProfile.class,CORE_RESOURCE,CORE_SHA,name,
            Map.of(),1,48000,cn.piq.retro.libretro.LibretroContentFiles.MAX_MAIN,4).withJni(runtime()).withJniAspect(NetplayProfile.JniAspect.PRESENTED);
    }
    public static List<NetplayVariant> variants(){
        return List.of(new NetplayVariant(LEGACY,"JNI · FBNeo state-v3（兼容默认）",profile("contract.zip"),true),
            new NetplayVariant(RETROARCH_STOCK,"RetroArch · 原版 FBNeo（实验，不存档）",stockProfile("contract.zip"),false));
    }
    /** Local trusted allowlist only; packets never select DLL paths or core options. */
    public static NetplayVariant variant(String id){
        for(var variant:variants())if(variant.id().equals(id))return variant;
        throw new IllegalArgumentException("不支持的街机 Netplay 后端："+id);
    }
    public static NetplayProfile profile(String name,String variant){
        if(!validGameName(name))throw new IllegalArgumentException("Invalid arcade ZIP name");
        return variant(variant).profile(name);
    }
    private static NetplayProfile stockProfile(String name){
        return new NetplayProfile(NativeNetplayProfile.class,STOCK_CORE_RESOURCE,STOCK_CORE_SHA,name,
            STOCK_OPTIONS,1,48000,cn.piq.retro.libretro.LibretroContentFiles.MAX_MAIN,4);
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
