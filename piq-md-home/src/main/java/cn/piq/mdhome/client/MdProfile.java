// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.retro.libretro.*;
import java.util.*;
public final class MdProfile {
    public enum Core { GENESIS_PLUS_GX, BLASTEM }
    public static final String COMMIT="c2838c7dc4236fc2fe94e5dbd08b41486067918e";
    public static final String SHA="9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7";
    public static final String LEGACY_SHA="3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016";
    public static LibretroProfile profile(){return profile(Core.GENESIS_PLUS_GX);}
    public static LibretroProfile profile(Core core){
        Objects.requireNonNull(core);
        if(core==Core.GENESIS_PLUS_GX)return new LibretroProfile("Genesis Plus GX","md",true,List.of(513,513),false,
            Map.of("genesis_plus_gx_system_hw","mega drive / genesis","genesis_plus_gx_region_detect","auto",
                "genesis_plus_gx_bios","disabled","genesis_plus_gx_lock_on","disabled",
                "genesis_plus_gx_frameskip","disabled","genesis_plus_gx_overscan","disabled",
                "genesis_plus_gx_render","single field"),
            Map.of("windows-x64",new LibretroProfile.Artifact("/core/windows-x64/genesis_plus_gx_libretro.dll",SHA)));
        return new LibretroProfile("BlastEm","md",true,List.of(1,1),false,
        Map.of("blastem_model","md1va3","blastem_megawifi","off","blastem_ram_init","zero","blastem_region","U","blastem_io_1","gamepad6.1","blastem_io_2","gamepad6.2"),
        Map.of("windows-x64",new LibretroProfile.Artifact("/core/windows-x64/blastem_libretro.dll",LEGACY_SHA)));}
    /** Preserve the existing user bindings; only the core's RetroPad layout differs. */
    public static int input(Core core,int canonical){
        if((canonical&~4095)!=0)throw new IllegalArgumentException("MD input bits");
        if(core==Core.BLASTEM)return canonical;
        int result=canonical&0x2fc; // directions, Start, Mode, Y
        if((canonical&1)!=0)result|=2;       // A: B -> Y
        if((canonical&256)!=0)result|=1;     // B: A -> B
        if((canonical&2048)!=0)result|=256;  // C: R -> A
        if((canonical&2)!=0)result|=1024;    // X: Y -> L
        if((canonical&1024)!=0)result|=2048; // Z: L -> R
        return result;
    }
    public static String saveNamespace(Core core,LibretroRuntimes.Backend backend){
        String prefix=backend==LibretroRuntimes.Backend.JNI_TRIAL?"jni-v1/":"process-v1/";
        if(core==Core.BLASTEM)return prefix+LEGACY_SHA; // alpha.1/.2 path is deliberately unchanged
        var p=profile(core);
        String contract=p.name()+"\n"+p.devices()+"\n"+new TreeMap<>(p.options());
        return prefix+"gx-v1/"+SHA+"/"+cn.piq.fcarcade.client.privateplay.PrivateSaveStore.sha256(contract.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private MdProfile(){}
}
