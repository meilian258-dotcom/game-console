package cn.piq.fcarcade.netplay;

import java.util.Map;

/** Isolated FC Netplay ABI; ownership stays with the existing personal slot or cartridge. */
public final class FcNetplaySaves {
    private FcNetplaySaves(){}
    public static String prefix(boolean gun){return gun?"core|nes-netplay-zapper-v1|":"core|nes-netplay-v1|";}
    public static String key(boolean gun,String owner){return prefix(gun)+owner;}
    public static String jniPrefix(){return "core|nes-jni-netplay-v1|";}
    public static String key(boolean gun,boolean jni,String owner){if(jni&&gun)throw new IllegalArgumentException("JNI Netplay 光枪尚未支持");return jni?jniPrefix()+owner:key(gun,owner);}
    public static NetplaySaveState.Identity jniIdentity(String rom){
        var original=identity(false,rom);
        var p=JniNetplaySession.profile();
        var descriptor="PIQ-JNI-Netplay-v1\n"+p.cores().get("windows-x64").sha256()+"\n"
                +p.name()+"\n"+p.extension()+"\n"+p.fullPath()+"\n"+p.devices()+"\n"+p.options();
        return new NetplaySaveState.Identity(NetplaySaveState.hash(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)),original.content());
    }
    public static NetplaySaveState.Identity identity(boolean gun,boolean jni,String rom){if(jni&&gun)throw new IllegalArgumentException("JNI Netplay 光枪尚未支持");return jni?jniIdentity(rom):identity(gun,rom);}
    public static boolean accepts(boolean gun,boolean jni,String rom,byte[] state){try{NetplaySaveState.decode(state,identity(gun,jni,rom));return true;}catch(IllegalArgumentException bad){return false;}}
    public static NetplaySaveState.Identity identity(boolean gun,String rom){return NetplaySaveState.identity(gun?NetplayProfile.fcZapper():NetplayProfile.fc(),rom,Map.of());}
    public static boolean accepts(boolean gun,String rom,byte[] state){try{NetplaySaveState.decode(state,identity(gun,rom));return true;}catch(IllegalArgumentException bad){return false;}}
}
