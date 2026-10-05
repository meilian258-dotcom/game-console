// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.retro.libretro.*;
import java.util.*;
public final class MdProfile {
    /** BLASTEM is a rejection-only compatibility token, never an executable profile. */
    public enum Core { GENESIS_PLUS_GX, BLASTEM }
    public static final String COMMIT="c2838c7dc4236fc2fe94e5dbd08b41486067918e";
    public static final String SHA="9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7";
    public static final String RETIRED_CORE_MESSAGE="BlastEm 已退役，本版本不能启动该核心；旧存档原样保留，不会切换 Genesis Plus GX 或自动转换旧档。需要旧进度请先备份，再使用原配套历史版本。";
    public static Core resolve(String id){
        Objects.requireNonNull(id);
        return switch(id.toLowerCase(Locale.ROOT)){
            case "genesis","genesis_plus_gx"->Core.GENESIS_PLUS_GX;
            case "blastem"->throw new IllegalArgumentException(RETIRED_CORE_MESSAGE);
            default->throw new IllegalArgumentException("未知 MD 核心："+id+"；当前仅支持 genesis。不会自动回退。");
        };
    }
    public static void requireActive(Core core){
        if(Objects.requireNonNull(core)!=Core.GENESIS_PLUS_GX)throw new IllegalArgumentException(RETIRED_CORE_MESSAGE);
    }
    public static LibretroProfile profile(){return profile(Core.GENESIS_PLUS_GX);}
    public static LibretroProfile profile(Core core){
        requireActive(core);
        return new LibretroProfile("Genesis Plus GX","md",true,List.of(513,513),false,
            Map.of("genesis_plus_gx_system_hw","mega drive / genesis","genesis_plus_gx_region_detect","auto",
                "genesis_plus_gx_bios","disabled","genesis_plus_gx_lock_on","disabled",
                "genesis_plus_gx_frameskip","disabled","genesis_plus_gx_overscan","disabled",
                "genesis_plus_gx_render","single field"),
            Map.of("windows-x64",new LibretroProfile.Artifact("/core/windows-x64/genesis_plus_gx_libretro.dll",SHA)));}
    /** Preserve the existing user bindings; only the core's RetroPad layout differs. */
    public static int input(Core core,int canonical){
        requireActive(core);
        if((canonical&~4095)!=0)throw new IllegalArgumentException("MD input bits");
        int result=canonical&0x2fc; // directions, Start, Mode, Y
        if((canonical&1)!=0)result|=2;       // A: B -> Y
        if((canonical&256)!=0)result|=1;     // B: A -> B
        if((canonical&2048)!=0)result|=256;  // C: R -> A
        if((canonical&2)!=0)result|=1024;    // X: Y -> L
        if((canonical&1024)!=0)result|=2048; // Z: L -> R
        return result;
    }
    public static String saveNamespace(Core core,LibretroRuntimes.Backend backend){
        requireActive(core);
        String prefix=backend==LibretroRuntimes.Backend.JNI_TRIAL?"jni-v1/":"process-v1/";
        var p=profile(core);
        String contract=p.name()+"\n"+p.devices()+"\n"+new TreeMap<>(p.options());
        return prefix+"gx-v1/"+SHA+"/"+cn.piq.fcarcade.client.privateplay.PrivateSaveStore.sha256(contract.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private MdProfile(){}
}
