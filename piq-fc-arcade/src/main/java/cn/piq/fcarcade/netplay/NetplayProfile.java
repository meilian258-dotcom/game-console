// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.netplay;

import java.util.*;

/** Trusted addon declaration, never constructed from a network packet or a downloaded manifest. */
public record NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                             Map<String,String> options,int device,int sampleRate,int maxRomBytes,int ports) {
    public NetplayProfile(Class<?> owner,String resource,String sha,String contentName,
                          Map<String,String> options,int device,int sampleRate,int maxRomBytes) {
        this(owner,resource,sha,contentName,options,device,sampleRate,maxRomBytes,2);
    }
    public NetplayProfile {
        Objects.requireNonNull(owner);Objects.requireNonNull(resource);Objects.requireNonNull(sha);
        if(!resource.startsWith("/")||!sha.matches("[a-fA-F0-9]{64}")||!safeName(contentName)
                ||device<1||device>65535||ports<1||ports>4||(sampleRate!=44100&&sampleRate!=48000)||maxRomBytes<16||maxRomBytes>64*1024*1024)
            throw new IllegalArgumentException("Netplay profile");
        options=Map.copyOf(options);
        if(options.size()>128)throw new IllegalArgumentException("Too many core options");
        for(var e:options.entrySet())if(!e.getKey().matches("[a-zA-Z0-9_-]{1,100}")||!e.getValue().matches("[a-zA-Z0-9 _().%/+-]{1,100}"))throw new IllegalArgumentException("Core option");
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
