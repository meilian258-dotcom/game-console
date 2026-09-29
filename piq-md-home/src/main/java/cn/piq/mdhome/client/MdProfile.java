// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.mdhome.client;
import cn.piq.retro.libretro.*;
import java.util.*;
public final class MdProfile {
    public static final String COMMIT="1e0de94dc7e669c0925a22c0fccf6cdc837af0a0";
    public static final String SHA="3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016";
    public static LibretroProfile profile(){return new LibretroProfile("BlastEm","md",true,List.of(1,1),false,
        Map.of("blastem_model","md1va3","blastem_megawifi","off","blastem_ram_init","zero","blastem_region","U","blastem_io_1","gamepad6.1","blastem_io_2","gamepad6.2"),
        Map.of("windows-x64",new LibretroProfile.Artifact("/core/windows-x64/blastem_libretro.dll",SHA)));}
    private MdProfile(){}
}
