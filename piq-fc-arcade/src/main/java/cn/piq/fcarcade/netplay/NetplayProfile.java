// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.*;

/** Trusted addon declaration, never constructed from a network packet or a downloaded manifest. */
public record NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                             Map<String,String> options,int device,int sampleRate,int maxRomBytes,int ports,
                             cn.piq.retro.libretro.LibretroProfile jni,JniAspect jniAspect) {
    /** The pinned core's reported DAR space, not its framebuffer pixel ratio. */
    public enum JniAspect { RAW, PRESENTED }
    /** Preserve the existing addon constructor and its unrotated-DAR default. */
    public NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                          Map<String,String> options,int device,int sampleRate,int maxRomBytes,int ports,
                          cn.piq.retro.libretro.LibretroProfile jni) {
        this(owner,resource,sha,contentName,options,device,sampleRate,maxRomBytes,ports,jni,JniAspect.RAW);
    }
    public NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                          Map<String,String> options,int device,int sampleRate,int maxRomBytes,int ports) {
        this(owner,resource,sha,contentName,options,device,sampleRate,maxRomBytes,ports,null);
    }
    public NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                          Map<String,String> options,int device,int sampleRate,int maxRomBytes) {
        this(owner,resource,sha,contentName,options,device,sampleRate,maxRomBytes,2);
    }
    public NetplayProfile {
        Objects.requireNonNull(owner);Objects.requireNonNull(resource);Objects.requireNonNull(sha);
        Objects.requireNonNull(jniAspect);
        if(!resource.startsWith("/")||!sha.matches("[a-fA-F0-9]{64}")||!safeName(contentName)
                ||device<1||device>65535||ports<1||ports>4||(sampleRate!=44100&&sampleRate!=48000)||maxRomBytes<16||maxRomBytes>64*1024*1024)
            throw new IllegalArgumentException("Netplay profile");
        options=Map.copyOf(options);
        if(options.size()>128)throw new IllegalArgumentException("Too many core options");
        for(var e:options.entrySet())if(!e.getKey().matches("[a-zA-Z0-9_-]{1,100}")||!e.getValue().matches("[a-zA-Z0-9 _().%/+-]{1,100}"))throw new IllegalArgumentException("Core option");
        if(jni!=null) {
            var artifact=jni.cores().get("windows-x64");
            if(artifact==null||!artifact.resource().equals(resource)||!artifact.sha256().equalsIgnoreCase(sha)
                    ||jni.devices().size()!=ports||!jni.options().equals(options)||jni.mesenGun()
                    ||!contentName.endsWith("."+jni.extension())||jni.devices().stream().anyMatch(d->d!=device))
                throw new IllegalArgumentException("JNI Netplay declaration does not match pinned core/input/options");
        }
    }
    /** Trusted addon declaration; not deserialized from content or network packets. */
    public NetplayProfile withJni(cn.piq.retro.libretro.LibretroProfile runtime){
        return new NetplayProfile(owner,resource,sha,contentName,runtime.options(),device,sampleRate,maxRomBytes,ports,runtime,jniAspect);
    }
    /** Trusted adapter metadata only; deliberately excluded from emulation/save identity. */
    public NetplayProfile withJniAspect(JniAspect space){
        return new NetplayProfile(owner,resource,sha,contentName,options,device,sampleRate,maxRomBytes,ports,jni,space);
    }
    public float rawJniAspect(float reportedAspect,int clockwiseRotation){
        if(!Float.isFinite(reportedAspect)||reportedAspect<=0||clockwiseRotation<0||clockwiseRotation>3)
            throw new IllegalArgumentException("JNI presentation metadata");
        float raw=jniAspect==JniAspect.PRESENTED&&(clockwiseRotation&1)!=0?1f/reportedAspect:reportedAspect;
        if(!Float.isFinite(raw)||raw<=0)throw new IllegalArgumentException("JNI raw aspect");
        return raw;
    }
    public static boolean safeName(String name){return name!=null&&name.matches("[a-zA-Z0-9_-]{1,64}\\.(nes|sfc|smc|zip)");}
    public String config(){var out=new StringBuilder();new TreeMap<>(options).forEach((k,v)->out.append(k).append(" = \"").append(v).append("\"\n"));return out.toString();}
    public static NetplayProfile fc(){return new NetplayProfile(NetplayProcess.class,"/core/libretro/windows-x64/mesen_libretro.dll",
        "2b3fbe286995c80ebbc85239fd28c8fa07b1011cc69c7f9021816429e3473885","content.nes",
        Map.of("mesen_region","NTSC","mesen_ramstate","All 0s (Default)","mesen_overscan_left","0","mesen_overscan_right","0","mesen_overscan_up","0","mesen_overscan_down","0"),1,44100,16*1024*1024);}
    public static NetplayProfile fcZapper(){var p=fc();return new NetplayProfile(p.owner,p.resource,p.sha,p.contentName,p.options,262,p.sampleRate,p.maxRomBytes);}
    public boolean isFcZapper(){return device==262&&owner==NetplayProcess.class&&resource.equals(fc().resource)&&sha.equals(fc().sha);}
    public int deviceForPort(int port){if(port<0||port>=ports)throw new IllegalArgumentException("Port");return isFcZapper()&&port==0?257:device;}
}
