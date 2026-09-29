import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;

/** Final archives in an isolated server-only loader. Never starts an emulator or a world. */
public final class NativeSync33CommonProbe {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static final class CommonLoader extends URLClassLoader {
        final Set<String> forbidden=new TreeSet<>();
        CommonLoader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            if(name.startsWith("net.minecraft.client.")||name.startsWith("net.neoforged.neoforge.client.")
                    ||name.startsWith("cn.piq.fcarcade.client.")||name.startsWith("cn.piq.nativearcade.client.")
                    ||name.startsWith("cn.piq.nativearcade.bridge.")||name.startsWith("com.sun.jna.")
                    ||name.startsWith("org.lwjgl.")||name.startsWith("io.github.kawamuray.wasmtime.")){
                forbidden.add(name);throw new ClassNotFoundException("Server-only: "+name);
            }
            return super.loadClass(name,resolve);
        }
    }
    public static void main(String[] args)throws Exception {
        var urls=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(Path::of)
                .filter(p->!Files.isDirectory(p)).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
        try(var loader=new CommonLoader(urls)){
            var nativeMod=Class.forName("cn.piq.nativearcade.NativeArcadeMod",true,loader);
            var registry=Class.forName("cn.piq.fcarcade.cabinet.CabinetBackends",true,loader);
            check(Path.of(nativeMod.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[1]).toRealPath()),"exact Native archive");
            check(Path.of(registry.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(Path.of(args[0]).toRealPath()),"exact FC archive");
            nativeMod.getMethod("registerBackend").invoke(null);
            var id=nativeMod.getField("BACKEND_ID").get(null);var idType=id.getClass();
            check((int)registry.getMethod("maxPlayers",idType).invoke(null,id)==4,"media keeps four ports");
            check((int)registry.getMethod("syncMaxPlayers",idType).invoke(null,id)==2,"experiment only two ports");
            check((boolean)registry.getMethod("hostSnapshotSync",idType).invoke(null,id),"explicit host snapshot policy");
            check((boolean)registry.getMethod("supportsSync",idType).invoke(null,id),"registered sync capability");
            var fingerprint=(String)registry.getMethod("expectedSyncCompatibility",idType).invoke(null,id);
            check(fingerprint!=null&&!fingerprint.isBlank()&&fingerprint.length()<=256,"bounded fixed fingerprint");
            try{nativeMod.getMethod("registerBackend").invoke(null);throw new AssertionError("duplicate accepted");}
            catch(InvocationTargetException expected){check(expected.getCause()instanceof IllegalArgumentException,"duplicate rejected");}
            for(var name:List.of("CabinetSyncPolicy","CabinetSyncNetwork","CabinetSynchronizer","CabinetRooms","CabinetSyncSettings","CabinetSyncRecoveryWindow"))
                Class.forName("cn.piq.fcarcade.cabinet."+name,false,loader);
            check(loader.forbidden.isEmpty(),"no client/JNA/native bridge load attempt");
            System.out.println(new com.google.gson.Gson().toJson(Map.of("ok",true,"assertions",checks,"final_jar_only",true,"client_native_attempts",loader.forbidden,"fingerprint",fingerprint)));
        }
    }
}
