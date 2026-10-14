// SPDX-License-Identifier: GPL-3.0-or-later
package cn.piq.fcarcade.cabinet;

import cn.piq.fcarcade.netplay.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native NVRAM files travel in the existing bounded save envelope under an independent domain.
 * The payload is an upstream .fs file, NEVER passed to core.restore or nativeSeed. */
public final class CabinetOperatingFiles {
    public static final int MAX_BYTES=1024*1024;
    private static final Set<String> CORES=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private CabinetOperatingFiles(){}
    public static void registerFbneoJni(String sha){if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Pinned core SHA");CORES.add(sha);}
    public static boolean supported(NetplayProfile p){return p!=null&&p.jni()!=null&&CORES.contains(p.sha().toLowerCase(Locale.ROOT))&&p.contentName().matches("[a-z0-9_]{1,32}\\.zip");}
    public static String relative(NetplayProfile p){if(!supported(p))throw new IllegalArgumentException("No trusted NVRAM adapter for this core");return "fbneo/"+p.contentName().substring(0,p.contentName().length()-4)+".fs";}
    public static NetplaySaveState.Identity identity(NetplaySaveState.Identity game){return new NetplaySaveState.Identity(NetplaySaveState.hash(("PIQ-cabinet-fbneo-nvram-fs-v1\n"+game.profile()).getBytes(StandardCharsets.UTF_8)),game.content());}
    public static byte[] pack(NetplaySaveState.Identity identity,byte[] file){
        if(file==null||file.length<1||file.length>MAX_BYTES)throw new IllegalArgumentException("NVRAM file size");
        return NetplaySaveState.encode(new NetplaySaveState.Parts(identity,0,file,new byte[0],new byte[0]));
    }
    public static byte[] unpack(NetplaySaveState.Identity identity,byte[] envelope){
        if(envelope==null)return null;
        var p=NetplaySaveState.decode(envelope,identity);byte[] bytes=p.state();
        if(p.frame()!=0||p.ram().length!=0||p.rtc().length!=0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Not an operating-file envelope");
        return bytes;
    }
}
