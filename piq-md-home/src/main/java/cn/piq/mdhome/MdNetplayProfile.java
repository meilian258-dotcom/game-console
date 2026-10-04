// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome;

import cn.piq.fcarcade.netplay.NetplayProfile;
import cn.piq.fcarcade.netplay.NetplaySaveState;
import cn.piq.mdhome.client.MdRom;
import cn.piq.retro.libretro.LibretroProfile;
import java.util.*;

/** Trusted MD native contract; never derived from a server-supplied core path. */
public final class MdNetplayProfile {
    /** 2026-10-03 r7: pinned state/audio/SRAM/cold-join and four real JNI sessions passed. */
    public static final boolean AVAILABLE=true;
    public static final String UNAVAILABLE="MD JNI Netplay 的核心状态、音频和多端恢复验证尚未通过；现有串流仍可使用。";
    public static final String RESOURCE="/core/windows-x64/genesis_plus_gx_piq_netplay_libretro.dll";
    public static final String SHA="8200a6d5e7c39f60afefd8e87e369d253f737de6a73bf8d920d2ab81888a5fb5";
    public static final String VERSION="v1.7.4c2838c7-piqnp1";
    private static final Map<String,String> OPTIONS=Map.of(
            "genesis_plus_gx_system_hw","mega drive / genesis","genesis_plus_gx_region_detect","auto",
            "genesis_plus_gx_bios","disabled","genesis_plus_gx_lock_on","disabled",
            "genesis_plus_gx_frameskip","disabled","genesis_plus_gx_overscan","disabled",
            "genesis_plus_gx_render","single field");
    private MdNetplayProfile(){}
    /** Independent native/save contract. A future MEDIA option change must not silently change Netplay. */
    public static LibretroProfile runtime(){return new LibretroProfile("Genesis Plus GX PIQ Netplay","md",true,
            List.of(513,513),false,OPTIONS,Map.of("windows-x64",new LibretroProfile.Artifact(RESOURCE,SHA)));}
    public static NetplayProfile profile(){
        return new NetplayProfile(MdNetplayProfile.class,RESOURCE,
                SHA,"md_netplay_v1.md",OPTIONS,513,48000,MdRom.MAX,2).withJni(runtime());
    }
    public static NetplaySaveState.Identity identity(String rom){return NetplaySaveState.identity(profile(),rom,Map.of());}
}
