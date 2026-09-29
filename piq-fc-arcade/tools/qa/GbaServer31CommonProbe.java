import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import com.google.gson.Gson;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.reflect.*;

/** Actual archive discovery, then common registration in a loader unable to load game-client/native bridge classes. */
public final class GbaServer31CommonProbe {
    private static int assertions;
    private static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static boolean compatible(Collection<IModInfo> mods){
        var versions=new HashMap<String,String>(Map.of("minecraft","1.21.1","neoforge","21.1.236"));
        for(var mod:mods)if(versions.putIfAbsent(mod.getModId(),mod.getVersion().toString())!=null)return false;
        for(var mod:mods)for(var dep:mod.getDependencies())if(dep.getType()==IModInfo.DependencyType.REQUIRED){var found=versions.get(dep.getModId());if(found==null||!dep.getVersionRange().containsVersion(new DefaultArtifactVersion(found)))return false;}return true;
    }
    private static final class CommonLoader extends URLClassLoader {
        final Set<String> forbiddenAttempts=new TreeSet<>(),ownedLoaded=new TreeSet<>();
        CommonLoader(URL[] urls){super(urls,ClassLoader.getPlatformClassLoader());}
        @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {
            if(name.startsWith("net.minecraft.client.")||name.startsWith("net.neoforged.neoforge.client.")||name.startsWith("cn.piq.fcarcade.client.")||name.startsWith("cn.piq.retro.client.")||name.startsWith("cn.piq.gba.client.")||name.startsWith("cn.piq.gba.bridge.")||name.startsWith("com.sun.jna.")||name.startsWith("org.lwjgl.")||name.startsWith("io.github.kawamuray.wasmtime.")){
                forbiddenAttempts.add(name);throw new ClassNotFoundException("Server-side probe blocks "+name);
            }
            if(name.startsWith("cn.piq."))ownedLoaded.add(name);return super.loadClass(name,resolve);
        }
    }
    public static void main(String[] args)throws Exception {
        var fc=Path.of(args[0]).toRealPath();var gba=Path.of(args[1]).toRealPath();
        var contents=new ArrayList<JarContents>();var files=new ArrayList<ModFile>();var groups=new ArrayList<List<IModInfo>>();var details=new ArrayList<Map<String,Object>>();
        try{
            var classOwners=new HashMap<String,Path>();
            for(var path:Arrays.stream(args).map(Path::of).map(p->{try{return p.toRealPath();}catch(Exception e){throw new RuntimeException(e);}}).toList()){
                try(var zip=new ZipFile(path.toFile())){for(var entry:zip.stream().toList())if(entry.getName().endsWith(".class"))check(classOwners.putIfAbsent(entry.getName(),path)==null,"one class owner "+entry.getName());}
                var content=JarContents.of(path);contents.add(content);var parsed=new JarModsDotTomlModFileReader().read(content,ModFileDiscoveryAttributes.DEFAULT);check(parsed instanceof ModFile,"real FML reader");
                var file=(ModFile)parsed;files.add(file);check(file.identifyMods(),"real identifyMods");check(file.getFilePath().toRealPath().equals(path),"exact archive origin");var mods=List.copyOf(file.getModInfos());groups.add(mods);
                var scan=file.compileContent();var annotations=new TreeSet<String>();for(var annotation:scan.getAnnotations())if(annotation.annotationType().getClassName().equals("net.neoforged.fml.common.Mod"))annotations.add(String.valueOf(annotation.annotationData().get("value")));
                var ids=new TreeSet<String>();for(var mod:mods)ids.add(mod.getModId());check(ids.equals(annotations),"declared mods equal real scanned entry annotations");details.add(Map.of("ids",ids,"classes",scan.getClasses().size()));
            }
            check(groups.get(0).size()==1&&groups.get(0).getFirst().getModId().equals("piq_fc_arcade"),"FC main only");check(groups.get(1).size()==1&&groups.get(1).getFirst().getModId().equals("piq_gba"),"GBA addon only");
            check(groups.get(1).getFirst().getVersion().toString().equals("0.1.0-alpha.2"),"GBA alpha2 contract");check(compatible(groups.get(0)),"FC standalone");check(!compatible(groups.get(1)),"GBA missing FC rejected");
            var all=new ArrayList<IModInfo>(groups.get(0));all.addAll(groups.get(1));check(compatible(all),"actual FML/Maven dependency pair");
            if(groups.size()==4){check(groups.get(2).stream().map(IModInfo::getModId).collect(java.util.stream.Collectors.toSet()).equals(Set.of("piq_sfc_home","piq_sfc_arcade")),"frozen dual-mod SFC discovered");check(groups.get(3).size()==1&&groups.get(3).getFirst().getModId().equals("piq_native_arcade"),"frozen Native discovered");
                for(int i=2;i<4;i++){check(!compatible(groups.get(i)),"legacy addon missing main refused");var pair=new ArrayList<IModInfo>(groups.get(0));pair.addAll(groups.get(i));check(compatible(pair),"legacy actual Maven range accepts FC31");all.addAll(groups.get(i));}
                check(compatible(all),"all four archives / five mod IDs coexist");
            }
            all.add(groups.get(1).getFirst());check(!compatible(all),"duplicate addon rejected");
            var urls=Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator)).map(Path::of).filter(p->!Files.isDirectory(p)).map(p->{try{return p.toUri().toURL();}catch(Exception e){throw new RuntimeException(e);}}).toArray(URL[]::new);
            Set<String> loaded;
            try(var loader=new CommonLoader(urls)){
                var mod=Class.forName("cn.piq.gba.GbaMod",true,loader);var registry=Class.forName("cn.piq.fcarcade.cabinet.CabinetBackends",true,loader);
                check(Path.of(mod.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(gba),"isolated exact GBA class");check(Path.of(registry.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath().equals(fc),"isolated exact FC registry");
                var busType=loader.loadClass("net.neoforged.bus.api.IEventBus");var calls=new ArrayList<String>();
                Object bus=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{busType},(proxy,method,arguments)->{calls.add(method.getName());if(method.getReturnType()==boolean.class)return false;if(method.getReturnType()==int.class)return 0;return null;});
                mod.getConstructor(busType).newInstance(bus);check(calls.contains("addListener"),"actual common entry registers only listener");
                mod.getMethod("registerBackend").invoke(null);Object id=mod.getField("BACKEND").get(null);var find=registry.getMethod("find",id.getClass());Object entry=find.invoke(null,id);
                check(entry!=null, "actual registry contains GBA");check(!(boolean)entry.getClass().getMethod("localOnly").invoke(entry),"server permitted metadata");check((int)registry.getMethod("maxPlayers",id.getClass()).invoke(null,id)==1,"actual GBA one-seat registration");
                try{mod.getMethod("registerBackend").invoke(null);throw new AssertionError("duplicate register accepted");}catch(InvocationTargetException expected){check(expected.getCause()instanceof IllegalArgumentException,"duplicate registration rejected");}
                for(String name:List.of("CabinetRooms","CabinetRoomLedger","CabinetRoomNetwork","WatchService","WatchLedger","WatchNetwork"))Class.forName("cn.piq.fcarcade.cabinet."+name,false,loader);
                check(loader.forbiddenAttempts.isEmpty(),"common path did not attempt any client/bridge/native load");loaded=Set.copyOf(loader.ownedLoaded);
            }
            try(var zip=new ZipFile(gba.toFile())){check(zip.getEntry("cn/piq/gba/bridge/GbaCore.class")==null&&zip.getEntry("cn/piq/gba/bridge/GbaWorker.class")==null,"native core and worker absent from addon");check(zip.stream().noneMatch(e->e.getName().endsWith(".dll")||e.getName().startsWith("com/sun/jna/")),"no native DLL/JNA owned by Minecraft addon");}
            System.out.println(new Gson().toJson(Map.of("ok",true,"assertions",assertions,"production_origin","final-jar-only","actual_fml_reader",true,"actual_annotation_scan",true,"actual_common_constructor_and_registration",true,"client_and_native_load_attempts",0,"isolated_common_loaded",loaded,"discovery",details,"minecraft_or_native_core_started",false)));
        }finally{for(var file:files)file.getSecureJar().close();for(var content:contents)content.close();}
    }
}
